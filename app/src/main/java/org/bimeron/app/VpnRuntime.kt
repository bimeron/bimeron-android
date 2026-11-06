package org.bimeron.app

import android.os.SystemClock
import androidx.annotation.StringRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Fixed VPN failure taxonomy. User-visible text is derived only from [messageRes];
 * the journal records only the code name, never exception or Core text.
 */
enum class VpnErrorCode(@StringRes val messageRes: Int) {
    PERMISSION_DENIED(R.string.vpn_error_permission_denied),
    INVALID_PROFILE(R.string.vpn_error_invalid_profile),
    PROFILE_CHANGED(R.string.vpn_error_profile_changed),
    CLEANUP_BLOCKED(R.string.vpn_error_cleanup_blocked),
    RECOVERY_STATE_FAILED(R.string.vpn_error_recovery_state_failed),
    CORE_INCOMPATIBLE(R.string.vpn_error_core_incompatible),
    NO_NETWORK(R.string.vpn_error_no_network),
    NETWORK_ERROR(R.string.vpn_error_network_error),
    TUN_FAILED(R.string.vpn_error_tun_failed),
    PACKET_IO(R.string.vpn_error_packet_io),
    CORE_CONNECT_FAILED(R.string.vpn_error_core_connect_failed),
    SSH_CONNECTION_FAILED(R.string.vpn_error_ssh_connection_failed),
    AUTH_FAILED(R.string.vpn_error_auth_failed),
    AUTH_UNANSWERED(R.string.vpn_error_auth_unanswered),
    AUTH_CANCELLED(R.string.vpn_error_auth_cancelled),
    RECOVERY_NEEDS_USER(R.string.vpn_error_recovery_needs_user),
    SSCONF_NETWORK_UNAVAILABLE(R.string.vpn_error_ssconf_network_unavailable),
    SSCONF_FETCH_FAILED(R.string.vpn_error_ssconf_fetch_failed),
    SSCONF_SNAPSHOT_INVALID(R.string.vpn_error_ssconf_snapshot_invalid),
    SSCONF_CONSENT_REQUIRED(R.string.vpn_error_ssconf_consent_required),
    START_FAILED(R.string.vpn_error_start_failed),
    STOP_FAILED(R.string.vpn_error_stop_failed),
}

data class VpnState(
    val state: String = "disconnected",
    val received: Long = 0,
    val sent: Long = 0,
    val message: String? = null,
    val activeProfileId: String? = null,
    val attemptProfileId: String? = null,
    val connectedAtElapsed: Long = 0,
    val code: VpnErrorCode? = null,
)

class AttemptGate {
    private val current = AtomicLong(0)
    @Synchronized fun next(): Long = current.incrementAndGet()
    @Synchronized fun accepts(token: Long): Boolean = current.get() == token
    @Synchronized fun commit(token: Long, action: () -> Unit) {
        if (accepts(token)) action()
    }
}

object VpnLive {
    val state = MutableStateFlow(VpnState())
    val protection = MutableStateFlow<Pair<Boolean, Boolean>?>(null)
    val gate = AttemptGate()
    // Each Start takes a fresh token, including switches from connected; clear once per attempt.
    private var journalAttempt = 0L

    fun publish(next: VpnState, token: Long) = gate.commit(token) {
        val previous = state.value
        state.value = next
        if (next.state == "connecting" && token != journalAttempt) {
            journalAttempt = token
            AppEvents.connectionStarting()
            // A restart from connecting (Apply) has no state change below; record the new attempt.
            if (previous.state == "connecting") AppEvents.add(R.string.journal_vpn_connecting, LogLevel.INFO)
        }
        if (previous.state != next.state || (next.state == "failed" && previous.code != next.code)) {
            when (next.state) {
                "connecting" -> AppEvents.add(R.string.journal_vpn_connecting, LogLevel.INFO)
                "connected" -> AppEvents.add(R.string.journal_vpn_connected, LogLevel.INFO)
                "reconnecting" -> AppEvents.add(R.string.journal_vpn_reconnecting, LogLevel.INFO)
                "stopping" -> AppEvents.add(R.string.journal_vpn_stopping, LogLevel.INFO)
                "disconnected" -> if (previous.state != "disconnected") AppEvents.add(R.string.journal_vpn_disconnected, LogLevel.INFO)
                // Only the fixed code name is journaled; message text is never recorded.
                "failed" -> next.code?.let { AppEvents.add(R.string.journal_vpn_failed_detail, LogLevel.ERROR, it.name) }
                    ?: AppEvents.add(R.string.journal_vpn_failed, LogLevel.ERROR)
            }
        }
    }

    internal fun publishFailure(code: VpnErrorCode, message: String, id: String?, token: Long) = gate.commit(token) {
        val previous = state.value
        // A callback or status tick may already have recorded the specific failure.
        if (previous.attemptProfileId == id && previous.code != null &&
            (previous.state == "failed" || previous.state == "disconnected")) {
            if (previous.state != "failed") publish(previous.copy(state = "failed"), token)
        } else {
            publish(VpnState("failed", message = message, attemptProfileId = id, code = code), token)
        }
    }

    internal fun publishCoreStatus(
        token: Long, id: String, rawState: String, received: Long, sent: Long,
        now: Long, message: String? = null, code: VpnErrorCode? = null,
    ): String = synchronized(gate) {
        if (!gate.accepts(token)) return@synchronized "disconnected"
        val previous = state.value
        if (previous.state == "failed" && previous.attemptProfileId == id) return@synchronized "failed"
        val next = normalizeCoreState(rawState)
        val active = next == "connected" || next == "reconnecting"
        val started = if (active) {
            previous.connectedAtElapsed.takeIf { it > 0 }
                ?: if (next == "connected") now else 0
        } else 0
        publish(VpnState(next, if (active) received.coerceAtLeast(0) else 0,
            if (active) sent.coerceAtLeast(0) else 0, message,
            if (active) id else null, id, started, code), token)
        next
    }

    fun serviceDestroyed(message: String) = synchronized(gate) {
        val token = gate.next()
        val previous = state.value
        if (previous.state != "failed" && previous.state != "disconnected") {
            publish(previous.copy(state = "failed", message = message, code = VpnErrorCode.STOP_FAILED), token)
        }
    }

    fun resetAfterProcessStart() = synchronized(gate) {
        gate.next()
        state.value = VpnState()
    }

    fun durationSeconds(now: Long = SystemClock.elapsedRealtime()): Long =
        state.value.takeIf { it.state == "connected" || it.state == "reconnecting" }?.connectedAtElapsed?.takeIf { it > 0 }?.let { ((now - it) / 1000).coerceAtLeast(0) } ?: 0
}

internal fun normalizeCoreState(state: String): String = when (state) {
    "disconnected", "connecting", "connected", "reconnecting", "stopping", "failed" -> state
    else -> "failed"
}

// Native stop is bounded by Go (60s); packet IO gets a separate bounded drain.
internal suspend fun finishPacketWorkers(
    workers: List<kotlinx.coroutines.Job>,
    timeoutMillis: Long = 2000,
    stop: () -> Unit,
): Boolean {
    val stopped = runCatching(stop).isSuccess
    val joined = kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) {
        workers.forEach { it.join() }
        true
    } ?: false
    return stopped && joined
}

// Process-owned: an unconfirmed cleanup requires process restart.
internal object NativeCleanupGate {
    @Volatile var blocked = false
        private set
    @Synchronized fun acquire(): Boolean {
        if (blocked) return false
        blocked = true
        return true
    }
    @Synchronized fun completed(confirmed: Boolean) {
        if (confirmed) blocked = false
    }
}

internal fun coreFailureCode(state: String, lastError: String?): VpnErrorCode? = when {
    lastError == "ssh_connection_failed" -> VpnErrorCode.SSH_CONNECTION_FAILED
    lastError?.contains("network", ignoreCase = true) == true -> VpnErrorCode.NETWORK_ERROR
    state == "failed" || state == "disconnected" || state == "reconnecting" -> VpnErrorCode.CORE_CONNECT_FAILED
    else -> null
}

/** Throws cancellation when the coroutine is cancelled or [token] is no longer the current attempt. */
internal suspend fun ensureSessionAttempt(token: Long) {
    currentCoroutineContext().ensureActive()
    if (!VpnLive.gate.accepts(token)) throw CancellationException("Stale VPN attempt")
}

/** Single store-backed read of the session input, guarded before and after by the attempt token. */
internal suspend fun readSessionInput(store: ProfileStore, id: String, token: Long): SessionInput {
    ensureSessionAttempt(token)
    val input = store.readForSession(id, stillValid = { VpnLive.gate.accepts(token) })
    ensureSessionAttempt(token)
    return input
}
