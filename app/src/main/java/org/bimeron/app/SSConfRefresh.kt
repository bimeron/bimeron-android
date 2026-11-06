package org.bimeron.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Fixed outcome of an explicit SSConf refresh. User-visible text comes only from [messageRes]. */
internal enum class SSConfRefreshResult(@StringRes val messageRes: Int, val success: Boolean) {
    REFRESHED(R.string.ssconf_refresh_done, true),
    /**
     * The store already holds a snapshot from a newer read (SUPERSEDED, e.g. a connection start fetched
     * one meanwhile). Not an error, and not offered for reconnect: that newer snapshot is the current one.
     */
    ALREADY_UPDATED(R.string.ssconf_refresh_already_updated, true),
    NOT_SAVED(R.string.ssconf_refresh_not_saved, false),
    PROFILE_CHANGED(R.string.ssconf_refresh_profile_changed, false),
    INVALID_PROFILE(R.string.ssconf_refresh_invalid_profile, false),
    CONSENT_REQUIRED(R.string.ssconf_refresh_consent_required, false),
    NETWORK_UNAVAILABLE(R.string.ssconf_refresh_network_unavailable, false),
    FETCH_FAILED(R.string.ssconf_refresh_fetch_failed, false),
    SNAPSHOT_INVALID(R.string.ssconf_refresh_snapshot_invalid, false),
    CORE_INCOMPATIBLE(R.string.ssconf_refresh_core_incompatible, false),
    FAILED(R.string.ssconf_refresh_failed, false);

    companion object {
        /** Maps a fetch failure code; anything unexpected is a fetch failure. */
        fun ofFetchFailure(code: VpnErrorCode?): SSConfRefreshResult = when (code) {
            VpnErrorCode.SSCONF_NETWORK_UNAVAILABLE -> NETWORK_UNAVAILABLE
            VpnErrorCode.CORE_INCOMPATIBLE -> CORE_INCOMPATIBLE
            VpnErrorCode.SSCONF_SNAPSHOT_INVALID -> SNAPSHOT_INVALID
            VpnErrorCode.SSCONF_CONSENT_REQUIRED -> CONSENT_REQUIRED
            else -> FETCH_FAILED
        }
    }
}

internal sealed interface SSConfRefreshState {
    data object Idle : SSConfRefreshState
    data class Running(val profileId: String) : SSConfRefreshState
    data class Finished(val profileId: String, val result: SSConfRefreshResult) : SSConfRefreshState
}

/**
 * Explicit, user-initiated SSConf snapshot refresh (contract §7.3).
 *
 * Reads the STORED profile, asks for VPN consent through [consent] when [needsConsent], fetches the body on
 * [io], validates it through Core and commits it with the read ticket. On a commit it only offers the
 * existing reconnect confirmation through [offerApply]. It never starts the service, creates a session,
 * publishes VPN state or advances the attempt gate.
 *
 * A newer refresh supersedes the previous one: the previous job is cancelled and its ticket's
 * `stillValid` turns false because the per-profile generation moved on. Main-thread owned except
 * [stillValid] checks, which may run on the store's IO thread.
 */
internal class SSConfRefreshCoordinator(
    private val scope: CoroutineScope,
    private val read: suspend (profileId: String, stillValid: () -> Boolean) -> SessionInput,
    private val commit: suspend (SnapshotTicket, ByteArray) -> SnapshotCommitResult,
    private val fetch: suspend (source: String) -> ByteArray,
    private val validate: suspend (source: String, body: ByteArray) -> Unit,
    private val needsConsent: () -> Boolean,
    private val offerApply: (profileId: String) -> Boolean,
    /** Fixed code carried by a fetch failure ([SSConfFailure]); null maps to SSCONF_FETCH_FAILED. */
    private val fetchFailure: (Exception) -> VpnErrorCode? = { (it as? SSConfFailure)?.code },
    private val io: CoroutineContext = Dispatchers.IO,
) {
    /** A pending consent request; it resolves only the refresh that created it. */
    class ConsentRequest internal constructor(val profileId: String) {
        internal val answer = CompletableDeferred<Boolean>()
        @Volatile internal var launched = false
        /** Set once the host was paused after the launch, i.e. the consent activity was in front. */
        @Volatile internal var pausedAfterLaunch = false
    }

    private val lock = Any()
    private val generations = HashMap<String, Long>()
    private var job: Job? = null
    private val mutableState = MutableStateFlow<SSConfRefreshState>(SSConfRefreshState.Idle)
    val state: StateFlow<SSConfRefreshState> = mutableState.asStateFlow()
    private val mutableConsent = MutableStateFlow<ConsentRequest?>(null)
    val consent: StateFlow<ConsentRequest?> = mutableConsent.asStateFlow()

    private fun current(profileId: String, generation: Long) = synchronized(lock) { generations[profileId] == generation }

    /** Every in-flight refresh, for any profile, becomes stale. */
    private fun bumpAll() = generations.keys.toList().forEach { generations[it] = generations.getValue(it) + 1 }

    fun refresh(profileId: String): Job {
        // A refresh of any profile supersedes the running one, so every outstanding ticket turns stale.
        val mine = synchronized(lock) {
            bumpAll()
            (generations[profileId] ?: 1L).also { generations[profileId] = it }
        }
        job?.cancel()
        mutableState.value = SSConfRefreshState.Running(profileId)
        // Lazy start: `job` is assigned before the body can run, even on an immediate dispatcher.
        val started = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext.job
            try {
                val result = run(profileId, mine)
                if (job === self) mutableState.value = SSConfRefreshState.Finished(profileId, result)
            } catch (cancelled: CancellationException) {
                if (job === self) mutableState.value = SSConfRefreshState.Idle
                throw cancelled
            } finally {
                if (job === self) job = null
            }
        }
        job = started
        started.start()
        return started
    }

    /** Ends the current refresh, including a pending consent request, and clears the result. */
    fun cancel() {
        synchronized(lock) { bumpAll() }
        job?.cancel()
        job = null
        mutableState.value = SSConfRefreshState.Idle
    }

    /** Marks [request] as launched; false if it was already launched or is no longer pending. */
    fun markLaunched(request: ConsentRequest): Boolean = synchronized(lock) {
        if (request.launched || mutableConsent.value !== request) return false
        request.launched = true
        true
    }

    /** Resolves a launched consent request. RESULT_OK resumes only that refresh. */
    fun answerConsent(granted: Boolean, request: ConsentRequest? = mutableConsent.value) {
        if (request != null && request.launched) request.answer.complete(granted)
    }

    /** Host lifecycle: the activity paused while [request] was launched. */
    fun consentPaused(request: ConsentRequest) {
        if (request.launched) request.pausedAfterLaunch = true
    }

    /**
     * Host lifecycle: the activity resumed. A result is always delivered before the resume that follows the
     * consent activity, so a launched request that was paused and is still open lost its result (for example
     * across an activity recreation) and ends as not granted instead of hanging. A resume without a prior
     * pause (before the consent activity appeared) does nothing.
     */
    fun consentResumed(request: ConsentRequest) {
        if (request.launched && request.pausedAfterLaunch) request.answer.complete(false)
    }

    private suspend fun awaitConsent(profileId: String): Boolean {
        val request = ConsentRequest(profileId)
        mutableConsent.value = request
        try {
            return request.answer.await()
        } finally {
            mutableConsent.compareAndSet(request, null)
        }
    }

    private suspend fun run(profileId: String, mine: Long): SSConfRefreshResult {
        val stillValid = { current(profileId, mine) }
        suspend fun ensureCurrent() {
            currentCoroutineContext().ensureActive()
            if (!stillValid()) throw CancellationException("Superseded refresh")
        }
        val input = try {
            read(profileId, stillValid)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return SSConfRefreshResult.INVALID_PROFILE
        }
        val source = input.ssconf?.source
        val ticket = input.ticket
        if (source == null || ticket == null) return SSConfRefreshResult.INVALID_PROFILE
        ensureCurrent()
        if (needsConsent() && !awaitConsent(profileId)) return SSConfRefreshResult.CONSENT_REQUIRED
        ensureCurrent()
        val body = try {
            withContext(io) { fetch(source) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            return SSConfRefreshResult.ofFetchFailure(fetchFailure(failure))
        } catch (_: LinkageError) {
            return SSConfRefreshResult.CORE_INCOMPATIBLE
        }
        ensureCurrent()
        if (body.size !in 1..SSConfSnapshots.MAX_BODY) return SSConfRefreshResult.SNAPSHOT_INVALID
        try {
            validate(source, body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return SSConfRefreshResult.SNAPSHOT_INVALID
        } catch (_: LinkageError) {
            return SSConfRefreshResult.CORE_INCOMPATIBLE
        }
        ensureCurrent()
        val committed = try {
            commit(ticket, body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return SSConfRefreshResult.FAILED
        }
        return when (committed) {
            is SnapshotCommitResult.Committed -> {
                // The snapshot is persisted; a superseded refresh does not offer the reconnect.
                if (stillValid()) offerApply(profileId)
                SSConfRefreshResult.REFRESHED
            }
            // The service would reload from disk, so a reconnect would not use this body.
            SnapshotCommitResult.NotPersisted -> SSConfRefreshResult.NOT_SAVED
            is SnapshotCommitResult.Conflict ->
                if (committed.reason == ConflictReason.SUPERSEDED) SSConfRefreshResult.ALREADY_UPDATED
                else SSConfRefreshResult.PROFILE_CHANGED
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Hosts the refresh-only VPN consent launcher. It is separate from the activity's connect permission
 * launcher and never touches `pendingPermissionId` or `VpnLive`. Leaving the host (other than through an
 * activity recreation) cancels the refresh. After a recreation the launcher's saved key receives the
 * pending result. The lost-result fallback is armed only by a pause observed after the launch (recorded in
 * the coordinator, so it survives recreation); see [SSConfRefreshCoordinator.consentResumed].
 */
@Composable internal fun SSConfRefreshConsentHost(refresh: SSConfRefreshCoordinator) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val request by refresh.consent.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        refresh.answerConsent(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(request) {
        val pending = request ?: return@LaunchedEffect
        if (refresh.markLaunched(pending)) {
            val intent = try { VpnService.prepare(context) } catch (_: Exception) {
                refresh.answerConsent(false, pending)
                return@LaunchedEffect
            }
            if (intent == null) {
                refresh.answerConsent(true, pending)
                return@LaunchedEffect
            }
            try { launcher.launch(intent) } catch (_: Exception) {
                refresh.answerConsent(false, pending)
                return@LaunchedEffect
            }
        }
    }
    // Registered after the launcher, so a result pending across recreation is delivered first.
    DisposableEffect(lifecycle, refresh) {
        val observer = LifecycleEventObserver { _, event ->
            val pending = refresh.consent.value ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_PAUSE -> refresh.consentPaused(pending)
                Lifecycle.Event.ON_RESUME -> refresh.consentResumed(pending)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(refresh) {
        onDispose { if (activity?.isChangingConfigurations != true) refresh.cancel() }
    }
}

/** "Refresh subscription" for a stored SSConf profile. Uses the stored source, so a dirty draft disables it. */
@Composable internal fun SSConfRefreshSection(model: AppModel, draft: EditorDraft, enabled: Boolean) {
    remember(draft.profile.text) {
        runCatching { SSConfSnapshots.sourceOf(ConfigDocument.parse(draft.profile.text)) }.getOrNull()
    } ?: return
    val state by model.ssconfRefresh.state.collectAsStateWithLifecycle()
    val id = draft.profile.id
    val running = (state as? SSConfRefreshState.Running)?.profileId == id
    val finished = (state as? SSConfRefreshState.Finished)?.takeIf { it.profileId == id }?.result
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { model.refreshSnapshot(id) }, enabled = enabled && !draft.dirty && !running,
            modifier = Modifier.fillMaxWidth()) {
            if (running) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(if (running) R.string.ssconf_refresh_in_progress else R.string.ssconf_refresh_action))
        }
        val (text, color) = when {
            running -> stringResource(R.string.ssconf_refresh_hint) to MaterialTheme.colorScheme.onSurfaceVariant
            draft.dirty -> stringResource(R.string.ssconf_refresh_save_first) to MaterialTheme.colorScheme.onSurfaceVariant
            finished != null -> stringResource(finished.messageRes) to
                if (finished.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            else -> stringResource(R.string.ssconf_refresh_hint) to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(text, fontSize = 12.sp, lineHeight = 18.sp, color = color)
    }
}
