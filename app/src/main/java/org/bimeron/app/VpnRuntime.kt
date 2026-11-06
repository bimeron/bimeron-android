package org.bimeron.app

import android.os.SystemClock
import androidx.annotation.StringRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
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
    /** The single pending SSH auth offer (I1). Written only through [publishAuth] or under the gate lock. */
    val auth = MutableStateFlow<AuthOffer?>(null)
    // Each Start takes a fresh token, including switches from connected; clear once per attempt.
    private var journalAttempt = 0L

    fun publish(next: VpnState, token: Long) = gate.commit(token) {
        val previous = state.value
        state.value = next
        // I3: a terminal state, or any state of a newer attempt, never keeps an older auth offer.
        if (next.state in TERMINAL_STATES || auth.value?.token != token) auth.value = null
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
            if (active) id else null, id, started,
            // A failure code describes a terminal state only; active states never carry one.
            code.takeIf { next == "failed" || next == "disconnected" }), token)
        next
    }

    /**
     * Publishes or withdraws the auth offer of attempt [token]. A stale token, an offer tagged with
     * another token, or an offer while the attempt is already terminal has no effect.
     */
    internal fun publishAuth(token: Long, offer: AuthOffer?) = gate.commit(token) {
        if (offer == null) auth.value = null
        else if (offer.token == token && state.value.state !in TERMINAL_STATES) auth.value = offer
    }

    /** Starts a new attempt; the previous attempt's offer and auth registration are dropped atomically. */
    internal fun nextAttempt(): Long = synchronized(gate) {
        clearAuthLocked()
        gate.next()
    }

    /** Clears the auth offer and registration under the gate lock, independent of any token. */
    internal fun clearAuth() = synchronized(gate) { clearAuthLocked() }

    private fun clearAuthLocked() {
        auth.value = null
        VpnAuthEndpoint.unregisterAll()
    }

    fun serviceDestroyed(message: String) = synchronized(gate) {
        clearAuthLocked()
        val token = gate.next()
        val previous = state.value
        if (previous.state != "failed" && previous.state != "disconnected") {
            publish(previous.copy(state = "failed", message = message, code = VpnErrorCode.STOP_FAILED), token)
        }
    }

    fun resetAfterProcessStart() = synchronized(gate) {
        clearAuthLocked()
        gate.next()
        state.value = VpnState()
    }

    fun durationSeconds(now: Long = SystemClock.elapsedRealtime()): Long =
        state.value.takeIf { it.state == "connected" || it.state == "reconnecting" }?.connectedAtElapsed?.takeIf { it > 0 }?.let { ((now - it) / 1000).coerceAtLeast(0) } ?: 0
}

private val TERMINAL_STATES = setOf("failed", "disconnected", "stopping")

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

/** Builds a failed state whose message is resolved only from [code]'s string resource. */
internal fun failureState(code: VpnErrorCode, text: (Int) -> String, id: String? = null) =
    VpnState("failed", message = text(code.messageRes), attemptProfileId = id, code = code)

/**
 * Failure code for a Stop/Finished teardown, or null when teardown succeeded.
 * CLEANUP_BLOCKED always wins: the next start is blocked, so that is the actionable state.
 * A failed recovery save keeps an existing specific [prior] code instead of overwriting the cause.
 */
internal fun teardownFailureCode(cleanupBlocked: Boolean, recoveryCleared: Boolean, prior: VpnErrorCode?): VpnErrorCode? = when {
    cleanupBlocked -> VpnErrorCode.CLEANUP_BLOCKED
    !recoveryCleared -> prior ?: VpnErrorCode.RECOVERY_STATE_FAILED
    else -> null
}

internal fun coreFailureCode(
    state: String, lastError: String?, history: AuthHistory = AuthHistory.NONE,
): VpnErrorCode? = when {
    lastError == "ssh_connection_failed" -> authFailureCode(history)
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

/** Non-secret. Prompts are server-provided text; answers never appear here. */
data class AuthOffer(
    /** AttemptGate token of the owning attempt. */
    val token: Long,
    /** Core opaque id, 48 hex. */
    val sessionId: String,
    /** Core opaque id, 48 hex. */
    val requestId: String,
    /** Exact pendingAuth JSON, already accepted by [SshAuthenticationController.parse]. */
    val wire: String,
)

enum class AuthResult { ACCEPTED, STALE, REJECTED, UNAVAILABLE }

/** Implemented by the owning attempt; [answers] == null means cancel. Returns false when the session is gone. */
internal fun interface AuthSink {
    suspend fun deliver(sessionId: String, requestId: String, answers: String?): Boolean
}

/**
 * The only path from the UI to Core auth. Every call is checked under the gate lock against the current
 * token, the registered attempt and the published offer; Core runs off Main and its errors carry no text.
 */
object VpnAuthEndpoint {
    // Guarded by the VpnLive.gate monitor.
    private var registeredToken = 0L
    private var registeredSink: AuthSink? = null

    internal fun register(token: Long, sink: AuthSink) = synchronized(VpnLive.gate) {
        if (VpnLive.gate.accepts(token)) {
            registeredToken = token
            registeredSink = sink
        }
    }

    /** No-op unless [token] owns the current registration, so a late call cannot drop a newer sink. */
    internal fun unregister(token: Long) = synchronized(VpnLive.gate) {
        if (registeredSink != null && registeredToken == token) unregisterAll()
    }

    internal fun unregisterAll() = synchronized(VpnLive.gate) {
        registeredToken = 0
        registeredSink = null
    }

    suspend fun reply(token: Long, sessionId: String, requestId: String, answersWire: String): AuthResult =
        deliver(token, sessionId, requestId, answersWire)

    suspend fun cancel(token: Long, sessionId: String, requestId: String): AuthResult =
        deliver(token, sessionId, requestId, null)

    private suspend fun deliver(token: Long, sessionId: String, requestId: String, answers: String?): AuthResult {
        val sink = synchronized(VpnLive.gate) {
            val offer = VpnLive.auth.value
            registeredSink.takeIf {
                VpnLive.gate.accepts(token) && registeredToken == token && offer != null &&
                    offer.token == token && offer.sessionId == sessionId && offer.requestId == requestId
            }
        } ?: return AuthResult.STALE
        return try {
            if (withContext(Dispatchers.IO) { sink.deliver(sessionId, requestId, answers) }) AuthResult.ACCEPTED
            else AuthResult.UNAVAILABLE
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Core text is dropped: it is never logged, published or rethrown.
            AuthResult.REJECTED
        }
    }
}

/** What the user did with auth prompts in the current SSH handshake; chooses the failure code only (§10). */
internal enum class AuthHistory { NONE, OFFERED, REPLIED, CANCELLED_BY_USER, WITHDRAWN }

/**
 * Core reports every SSH failure as `ssh_connection_failed`; the code is a host-side guess.
 * OFFERED means a prompt was still visible and unanswered when the failure was seen. The prompt
 * blocks the handshake, so the failure ended it unanswered (Core timeout or a withdrawal racing
 * the status read): AUTH_UNANSWERED, the same as WITHDRAWN.
 */
internal fun authFailureCode(history: AuthHistory): VpnErrorCode = when (history) {
    AuthHistory.NONE -> VpnErrorCode.SSH_CONNECTION_FAILED
    AuthHistory.REPLIED -> VpnErrorCode.AUTH_FAILED
    AuthHistory.OFFERED, AuthHistory.WITHDRAWN -> VpnErrorCode.AUTH_UNANSWERED
    AuthHistory.CANCELLED_BY_USER -> VpnErrorCode.AUTH_CANCELLED
}

/** Status tick: 200 ms before connected; 500 ms after, or 250 ms while this attempt shows an offer. */
internal fun statusTickMillis(connected: Boolean, token: Long): Long = when {
    !connected -> 200
    VpnLive.auth.value?.token == token -> 250
    else -> 500
}

/**
 * A user cancel ends attempt [token] whatever its state (connecting, reconnecting or connected):
 * AUTH_CANCELLED is published under the token and the owning run job is cancelled, which takes the
 * normal finally/Command.Finished path (auth cleared, recovery cleared, no reconnect fallback).
 */
internal fun stopAfterUserCancel(id: String, token: Long, message: String, owner: kotlinx.coroutines.Job?) {
    VpnLive.publishFailure(VpnErrorCode.AUTH_CANCELLED, message, id, token)
    owner?.cancel()
}

/** The Core auth surface of a started session; wraps `Session` so the attempt logic runs without JNI. */
internal interface CoreSessionPort {
    fun pendingAuth(): String?
    fun replyAuth(sessionId: String, requestId: String, answers: String)
    fun cancelAuth(sessionId: String, requestId: String)
}

/**
 * Auth state of one attempt token. [poll] runs on the attempt's status coroutine; [deliver] runs on IO
 * from [VpnAuthEndpoint]. Answers pass straight to Core and are never stored.
 */
internal class AuthAttempt(
    private val token: Long,
    private val port: CoreSessionPort,
    private val isOpen: () -> Boolean,
    private val onCancelled: () -> Unit,
) : AuthSink {
    private val lock = Any()
    private var offered: Pair<String, String>? = null
    private val answered = mutableSetOf<Pair<String, String>>()
    var history = AuthHistory.NONE
        get() = synchronized(lock) { field }
        private set

    fun poll() {
        val wire = try { port.pendingAuth() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        val request = try { SshAuthenticationController.parse(wire) } catch (_: Exception) { null }
        synchronized(lock) {
            val key = request?.let { it.sessionId to it.requestId }
            if (key == offered) return
            offered?.let { if (it !in answered && history != AuthHistory.CANCELLED_BY_USER) history = AuthHistory.WITHDRAWN }
            offered = key
            if (key == null || wire == null) {
                VpnLive.publishAuth(token, null)
                return
            }
            if (history != AuthHistory.CANCELLED_BY_USER) history = AuthHistory.OFFERED
            VpnLive.publishAuth(token, AuthOffer(token, key.first, key.second, wire))
        }
    }

    /** Core reached `connected`: the handshake is done, so a later SSH failure starts from NONE. */
    fun connected() = synchronized(lock) {
        if (history != AuthHistory.CANCELLED_BY_USER) history = AuthHistory.NONE
        answered.clear()
        // An offer still visible at connect is moot; its later disappearance is not a withdrawal.
        offered?.let { answered.add(it) }
    }

    override suspend fun deliver(sessionId: String, requestId: String, answers: String?): Boolean {
        if (!isOpen()) return false
        val key = sessionId to requestId
        if (answers == null) {
            port.cancelAuth(sessionId, requestId)
            synchronized(lock) {
                answered.add(key)
                history = AuthHistory.CANCELLED_BY_USER
            }
            onCancelled()
        } else {
            port.replyAuth(sessionId, requestId, answers)
            synchronized(lock) {
                answered.add(key)
                // A newer offer seen meanwhile keeps OFFERED; otherwise the reply is the last event.
                if (history != AuthHistory.CANCELLED_BY_USER && (offered == null || offered == key)) history = AuthHistory.REPLIED
            }
        }
        return true
    }
}
