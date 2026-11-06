package org.bimeron.app

import android.app.Application
import android.content.Context
import android.content.Intent
import java.util.UUID
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val Context.preferences by preferencesDataStore("settings")
private val themeKey = stringPreferencesKey("theme")
private val selectedKey = stringPreferencesKey("selected_profile")

class AppModel(application: Application) : AndroidViewModel(application) {
    private val storeReady = MutableStateFlow(false)
    @Volatile private var initializedStore: ProfileStore? = null
    val store: ProfileStore get() = checkNotNull(initializedStore) { "Profiles are loading" }
    private val commits = EditorCommits { store }
    private suspend fun loadProfiles(): Pair<List<Profile>, Boolean> {
        val ready = withContext(Dispatchers.IO) { ProfileStore.get(getApplication()) }
        initializedStore = ready
        storeReady.value = true
        val (stored, warning) = ready.load()
        commits.loaded(stored)
        return stored.map { it.profile } to warning
    }
    private val coordinator = AppModelOperations(
        scope = viewModelScope,
        load = { loadProfiles() },
        saveProfile = { text, id -> store.save(text, id) },
        saveDraft = { text, id, draft -> store.save(text, id, (draft as? EditorDraft)?.takeIf { it.profile.id == id && it.source.text == text }?.resources) },
        deleteProfile = { store.delete(it) },
        saveCommitted = { text, id, draft, publish -> commits.save(text, id, draft as? EditorDraft, publish) },
        deleteCommitted = { id, draft, publish -> commits.delete(id, draft as? EditorDraft, publish) },
        checkDelete = { id, draft -> commits.checkDelete(id, draft as? EditorDraft) },
        readSelection = { application.preferences.data.first()[selectedKey] },
        writeSelection = { id ->
            application.preferences.edit { settings ->
                if (id == null) settings.remove(selectedKey) else settings[selectedKey] = id
            }
        },
        runtime = VpnLive.state,
        stop = {
            application.startService(Intent(application, BimeronVpnService::class.java).setAction(BimeronVpnService.STOP))
        },
        log = { AppEvents.add(it) },
        journal = { message, level -> AppEvents.add(message, level) },
        strings = { id, args -> application.string(id, args) },
    )
    var editorDraft: EditorDraft?
        get() = coordinator.editorDraft as EditorDraft?
        set(value) {
            val old = editorDraft
            coordinator.editorDraft = value
            if (old != null && old !== value) viewModelScope.launch(Dispatchers.IO + NonCancellable) {
                old.resources.dispose(store.resources)
            }
        }
    /** The only production entry point for a new draft: it pins the revision the editor shows. */
    fun openEditor(profile: Profile): EditorDraft =
        EditorDraft(profile, commits.baseRevision(profile)).also { editorDraft = it }
    val profiles = coordinator.profiles
    val selected = coordinator.selected
    val pendingSwitch = coordinator.pendingSwitch
    val pendingApply = coordinator.pendingApply
    val loading = combine(coordinator.loading, storeReady) { loading, ready -> loading || !ready }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val warning = coordinator.warning
    val error = coordinator.error
    val busy = coordinator.busy
    val expanded = MutableStateFlow(false)
    var pendingPermissionId: String? = null
    val logs = AppEvents.entries
    val theme = application.preferences.data.map { it[themeKey] ?: "system" }

    override fun onCleared() {
        editorDraft = null
        super.onCleared()
    }

    fun log(message: String, level: LogLevel = LogLevel.INFO) = AppEvents.add(message, level)
    fun refresh() = coordinator.refresh()
    fun select(id: String) = coordinator.select(id)
    fun requestSelect(id: String) = coordinator.requestSelect(id)
    fun confirmSwitch(start: (String) -> Unit) = coordinator.confirmSwitch(start)
    fun cancelSwitch() = coordinator.cancelSwitch()
    fun clearPendingApply() = coordinator.clearPendingApply()
    fun applyPending(start: (String) -> Unit) = coordinator.applyPending(start)
    fun save(text: String, id: String?, event: String, configurationChanged: Boolean = false, done: () -> Unit) =
        coordinator.save(text, id, event, configurationChanged, done)
    fun delete(id: String, done: () -> Unit) = coordinator.delete(id, done)
    fun clearError() = coordinator.clearError()

    fun toggleExpanded() { expanded.value = !expanded.value }
    fun collapse() { expanded.value = false }
    fun setTheme(value: String) = viewModelScope.launch { getApplication<Application>().preferences.edit { it[themeKey] = value } }

    fun addKey(name: String, key: String, done: () -> Unit) {
        val app = getApplication<Application>()
        try { save(Conf.fromKey(name, key), null, app.getString(R.string.profile_added), done = done) }
        catch (e: ProfileError) { error.value = e.text(app) }
    }

}

internal class AppModelOperations(
    private val scope: CoroutineScope,
    private val load: suspend () -> Pair<List<Profile>, Boolean>,
    private val saveProfile: suspend (String, String?) -> Profile,
    private val deleteProfile: suspend (String) -> Unit,
    private val readSelection: suspend () -> String?,
    private val writeSelection: suspend (String?) -> Unit,
    private val runtime: StateFlow<VpnState>,
    private val stop: () -> Unit,
    private val log: (String) -> Unit,
    private val saveDraft: (suspend (String, String?, Any?) -> Profile)? = null,
    private val saveCommitted: (suspend (String, String?, Any?, suspend (Profile) -> Unit) -> Unit)? = null,
    private val deleteCommitted: (suspend (String, Any?, suspend () -> Unit) -> Unit)? = null,
    // Cheap delete precondition run before stopping the VPN; the commit itself stays authoritative.
    private val checkDelete: (String, Any?) -> Unit = { _, _ -> },
    private val journal: (String, LogLevel) -> Unit = { message, _ -> log(message) },
    // Resolves user-visible string resources; the default (resource id) serves plain JVM tests.
    private val strings: (Int, Array<out Any>) -> String = { id, _ -> "#$id" },
) {
    private fun resource(id: Int) = strings(id, emptyArray())

    // Bumped on every replacement so a draft set again later is still a different editor session.
    private var draftSession = 0L
    var editorDraft: Any? = null
        set(value) { if (field !== value) draftSession++; field = value }
    /**
     * True while the editor state captured when an operation was requested is still shown: same draft
     * object, same session and no further edits. Only then may a result close it or set its error.
     */
    private fun stillCurrent(draft: Any?): () -> Boolean {
        val session = draftSession
        val generation = (draft as? EditorDraft)?.editGeneration
        return { draftSession == session && editorDraft === draft && (draft as? EditorDraft)?.editGeneration == generation }
    }
    val profiles = MutableStateFlow<List<Profile>>(emptyList())
    val selected = MutableStateFlow<String?>(null)
    val pendingSwitch = MutableStateFlow<String?>(null)
    val pendingApply = MutableStateFlow<String?>(null)
    val loading = MutableStateFlow(true)
    val warning = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    private var initialSelectionLoaded = false
    private val operations = Mutex()
    private var switchGeneration = 0L
    private var applySession: VpnState? = null
    private var busyCount = 0
    private var loadingCount = 0

    private fun operation(load: Boolean = false, work: suspend () -> Unit): kotlinx.coroutines.Job {
        synchronized(operations) {
            if (load) loadingCount++ else busyCount++
            loading.value = loadingCount > 0
            busy.value = busyCount > 0
        }
        return scope.launch { operations.withLock { work() } }.also { job ->
            job.invokeOnCompletion {
                synchronized(operations) {
                    if (load) loadingCount-- else busyCount--
                    loading.value = loadingCount > 0
                    busy.value = busyCount > 0
                }
            }
        }
    }

    private fun validApply(runtime: VpnState): Boolean =
        runtime.state in setOf("connected", "reconnecting") &&
            runtime.activeProfileId == pendingApply.value &&
            applySession?.activeProfileId == runtime.activeProfileId &&
            applySession?.connectedAtElapsed == runtime.connectedAtElapsed

    init {
        refresh()
        scope.launch {
            runtime.collect { runtime ->
                if (!validApply(runtime)) clearPendingApply()
            }
        }
    }


    fun refresh() = operation(load = true) {
        try {
            val (items, hasWarning) = load()
            val savedId = if (!initialSelectionLoaded) readSelection() else selected.value
            profiles.value = items
            if (items.none { it.id == pendingSwitch.value }) cancelSwitch()
            warning.value = hasWarning
            val resolved = savedId?.takeIf { id -> items.any { it.id == id } } ?: items.firstOrNull()?.id
            persistSelection(resolved)
            initialSelectionLoaded = true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error.value = resource(R.string.profiles_load_failed)
        }
    }

    private suspend fun persistSelection(id: String?) {
        writeSelection(id)
        selected.value = id
    }

    fun select(id: String): kotlinx.coroutines.Job {
        cancelSwitch()
        return scope.launch {
            operations.withLock {
            try {
                if (profiles.value.any { it.id == id }) persistSelection(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error.value = resource(R.string.profile_select_failed)
            }
            }
        }
    }

    fun requestSelect(id: String) {
        cancelSwitch()
        if (profiles.value.none { it.id == id }) return
        val state = runtime.value
        if (state.state in setOf("permission", "connecting", "stopping")) return
        if (state.state in setOf("connected", "reconnecting") && state.activeProfileId != null) {
            if (id == selected.value) return
            pendingSwitch.value = id
        } else select(id)
    }

    fun cancelSwitch() { switchGeneration++; pendingSwitch.value = null }

    fun confirmSwitch(start: (String) -> Unit) {
        val id = pendingSwitch.value ?: return
        val generation = ++switchGeneration
        val session = runtime.value
        scope.launch { operations.withLock {
            try {
                if (generation != switchGeneration || pendingSwitch.value != id) return@withLock
                val profile = profiles.value.firstOrNull { it.id == id }
                if (profile == null || !sameModelSession(runtime.value, session)) {
                    cancelSwitch()
                    return@withLock
                }
                val previous = selected.value
                try {
                    persistSelection(id)
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { persistSelection(previous) }
                    throw e
                }
                if (generation != switchGeneration || pendingSwitch.value != id ||
                    !sameModelSession(runtime.value, session)) {
                    withContext(NonCancellable) { persistSelection(previous) }
                    if (generation == switchGeneration) cancelSwitch()
                    return@withLock
                }
                pendingSwitch.value = null
                if (profile.mode == "vpn") start(id)
                else stop()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error.value = resource(R.string.profile_switch_failed)
            }
        } }
    }

    fun clearPendingApply() { pendingApply.value = null; applySession = null }
    fun applyPending(start: (String) -> Unit) {
        val id = pendingApply.value ?: return
        val valid = validApply(runtime.value)
        clearPendingApply()
        val profile = profiles.value.firstOrNull { it.id == id }
        if (valid && profile?.mode == "vpn" &&
            !Regex("(?m)^\\s*KillSwitch\\s*=\\s*true\\s*$").containsMatchIn(profile.text)) start(id)
    }

    fun save(text: String, id: String?, event: String, configurationChanged: Boolean = false, done: () -> Unit): kotlinx.coroutines.Job {
        val draft = editorDraft
        val current = stillCurrent(draft)
        // Creations (imports, added keys) never depend on whatever editor draft happens to be open.
        val editorBound = id != null && draft != null
        return operation {
            // A queued editor save whose draft was replaced or edited meanwhile is dropped, not committed.
            if (editorBound && !current()) return@operation
            error.value = null
            try {
                if (id != null && profiles.value.none { it.id == id }) throw ProfileError(R.string.profile_no_longer_exists)
                val publish: suspend (Profile) -> Unit = { profile -> withContext(NonCancellable) {
                    // Persistence is committed. Refresh/preferences failures must not report a failed save.
                    profiles.value = profiles.value.filterNot { it.id == profile.id } + profile
                    try {
                        val (items, hasWarning) = load()
                        profiles.value = items
                        if (items.none { it.id == pendingSwitch.value }) cancelSwitch()
                        warning.value = hasWarning
                        val vpnBusy = runtime.value.state in setOf("permission", "connecting", "connected", "reconnecting", "stopping")
                        if (id == null && !vpnBusy) persistSelection(profile.id)
                        if (configurationChanged && runtime.value.state in setOf("connected", "reconnecting") && id == runtime.value.activeProfileId) {
                            applySession = runtime.value
                            pendingApply.value = id
                        }
                    } catch (_: Exception) { warning.value = true }
                    runCatching { log(event) }
                    if (!editorBound || current()) runCatching { done() }.onFailure { warning.value = true }
                } }
                if (saveCommitted != null) saveCommitted.invoke(text, id, draft, publish)
                else publish(saveDraft?.invoke(text, id, draft) ?: saveProfile(text, id))
            } catch (e: ProfileError) {
                if (!editorBound || current()) error.value = e.text(strings)
                journal(resource(R.string.journal_profile_save_error), LogLevel.ERROR)
                if (e.isEditConflict) reloadAfterConflict()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (!editorBound || current()) error.value = resource(R.string.profile_save_failed)
                journal(resource(R.string.journal_profile_save_error), LogLevel.ERROR)
            }
        }
    }

    fun delete(id: String, done: () -> Unit): kotlinx.coroutines.Job {
        val draft = editorDraft
        val current = stillCurrent(draft)
        return operation {
            if (draft != null && !current()) return@operation
            error.value = null
            try {
                checkDelete(id, draft)
                val vpn = runtime.value
                if (id == vpn.activeProfileId || id == vpn.attemptProfileId) {
                    stop()
                    val stopped = withTimeoutOrNull(15_000) {
                        runtime.first { it.state == "disconnected" || it.state == "failed" }
                    }
                    if (stopped?.state != "disconnected") throw ProfileError(R.string.profile_delete_vpn_stop_failed)
                }
                val publish: suspend () -> Unit = { withContext(NonCancellable) {
                    profiles.value = profiles.value.filterNot { it.id == id }
                    try {
                        val (items, hasWarning) = load()
                        profiles.value = items
                        if (items.none { it.id == pendingSwitch.value }) cancelSwitch()
                        warning.value = hasWarning
                        if (selected.value == id || items.none { it.id == selected.value }) persistSelection(items.firstOrNull()?.id)
                        if (pendingApply.value == id) pendingApply.value = null
                    } catch (_: Exception) { warning.value = true }
                    runCatching { log(resource(R.string.journal_profile_deleted)) }
                    if (current()) runCatching { done() }.onFailure { warning.value = true }
                } }
                if (deleteCommitted != null) deleteCommitted.invoke(id, draft, publish)
                else { deleteProfile(id); publish() }
            } catch (e: ProfileError) {
                if (draft == null || current()) error.value = e.text(strings)
                journal(resource(R.string.journal_profile_delete_error), LogLevel.ERROR)
                if (e.isEditConflict) reloadAfterConflict()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (draft == null || current()) error.value = resource(R.string.profile_delete_failed)
                journal(resource(R.string.journal_profile_delete_error), LogLevel.ERROR)
            }
        }
    }

    fun clearError() { error.value = null }

    /**
     * After a conflict the profile changed through another path (possibly another model instance on the
     * same store), so reload what reopening the editor pins. Never touches the open editor draft.
     */
    private suspend fun reloadAfterConflict() = withContext(NonCancellable) {
        try {
            val (items, hasWarning) = load()
            profiles.value = items
            if (items.none { it.id == pendingSwitch.value }) cancelSwitch()
            warning.value = hasWarning
            if (items.none { it.id == selected.value }) persistSelection(items.firstOrNull()?.id)
        } catch (_: Exception) { warning.value = true }
    }
}

internal fun sameModelSession(a: VpnState, b: VpnState): Boolean =
    a.state == b.state && a.activeProfileId == b.activeProfileId &&
        a.attemptProfileId == b.attemptProfileId && a.connectedAtElapsed == b.connectedAtElapsed

/** Recover commits even when dispatching the operation's return observes cancellation. */
internal suspend fun publishStoreCommit(store: ProfileStore, requestId: UUID,
    operation: suspend () -> Unit, publish: suspend (CommitReceipt) -> Unit) {
    try { operation() }
    finally { withContext(NonCancellable) { store.committedReceipt(requestId)?.let { publish(it) } } }
}

/**
 * Editor commits are compare-and-swap against the revision the draft was opened at (contract §8.3/§8.4),
 * never against the revision current at save time, so A->B->A changes elsewhere are detected.
 * Updates and deletes need the editor's draft for that id, unchanged since the request, with a base
 * revision; otherwise they conflict. Only creation (id == null) commits with expected == null.
 * A conflict throws [R.string.profile_edit_conflict] and leaves the draft and its imported SSH
 * resources untouched; AppModelOperations then reloads so that reopening pins the latest revision. After a successful save the editor closes; if it stays open (stale result),
 * its base is not advanced: every commit takes a new store revision, so reusing the old base conflicts.
 * Main-thread owned.
 */
internal val ProfileError.isEditConflict get() = resId == R.string.profile_edit_conflict

internal class EditorCommits(private val store: () -> ProfileStore) {
    private val publications = ProfileReceiptPublications()
    private val stored = mutableMapOf<String, StoredProfile>()

    fun loaded(items: List<StoredProfile>) {
        stored.clear()
        items.forEach { stored[it.id] = it }
    }

    /** The revision [profile] was loaded at, or null when the shown profile is not the stored one. */
    fun baseRevision(profile: Profile): ProfileRevision? = stored[profile.id]?.takeIf { it.profile == profile }?.revision

    private fun conflict() = ProfileError(R.string.profile_edit_conflict)

    /** Fails early, before the VPN is stopped, when a delete from [draft] would certainly conflict. */
    fun checkDelete(id: String, draft: EditorDraft?) { base(id, draft, null) }

    private fun base(id: String, draft: EditorDraft?, text: String?): ProfileRevision {
        if (draft == null || draft.profile.id != id || (text != null && draft.source.text != text)) throw conflict()
        return draft.baseRevision ?: throw conflict()
    }

    suspend fun save(text: String, id: String?, draft: EditorDraft?, publish: suspend (Profile) -> Unit) {
        val expected = id?.let { base(it, draft, text) }
        val resources = if (id != null) draft?.resources else null
        val store = store()
        val uuid = id?.let(UUID::fromString) ?: UUID.randomUUID()
        val requestId = UUID.randomUUID()
        val normalized = withContext(Dispatchers.IO) { resources?.resolve(text, store.resources) ?: text }
        val profile = ConfigDocument.parse(normalized).validate().copy(id = uuid.toString())
        publishStoreCommit(store, requestId, operation = {
            if (store.compareAndSave(requestId, uuid, expected, normalized, resources) is CommitResult.Conflict) throw conflict()
        }) { receipt ->
            if (publications.accept(receipt, stored[profile.id]?.revision)) {
                stored[profile.id] = StoredProfile(profile, receipt.revision!!, receipt.digest!!)
                publish(profile)
            }
        }
    }

    suspend fun delete(id: String, draft: EditorDraft?, publish: suspend () -> Unit) {
        val expected = base(id, draft, null)
        val store = store()
        val uuid = UUID.fromString(id)
        val requestId = UUID.randomUUID()
        publishStoreCommit(store, requestId, operation = {
            if (store.compareAndDelete(requestId, uuid, expected) is CommitResult.Conflict) throw conflict()
        }) { receipt ->
            if (publications.accept(receipt, stored[id]?.revision, expected)) {
                stored.remove(id)
                publish()
            }
        }
    }
}

/** Keep tombstones so a late save receipt cannot resurrect a deleted profile. Main-thread owned. */
internal class ProfileReceiptPublications {
    private data class Published(val revision: ProfileRevision, val deleted: Boolean)
    private val latest = mutableMapOf<UUID, Published>()
    private val requests = mutableSetOf<UUID>()
    fun accept(receipt: CommitReceipt, observed: ProfileRevision?, deletedBase: ProfileRevision? = null): Boolean {
        if (receipt.requestId in requests) return false
        val revision = receipt.revision ?: deletedBase ?: return false
        val previous = latest[receipt.profileId] ?: observed?.let { Published(it, false) }
        val deleted = receipt.kind == CommitReceipt.Kind.DELETED
        if (previous != null && (previous.revision.epoch != revision.epoch ||
            previous.revision.counter > revision.counter ||
            (previous.revision.counter == revision.counter && (!deleted || previous.deleted)))) return false
        requests.add(receipt.requestId)
        latest[receipt.profileId] = Published(revision, deleted)
        return true
    }
}
