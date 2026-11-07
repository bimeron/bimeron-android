package org.bimeron.app

import android.os.SystemClock
import androidx.annotation.StringRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

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
    /** Core refused a known SSH host whose key changed (`ssh_host_key_mismatch`); never offered to the user. */
    HOST_KEY_MISMATCH(R.string.vpn_error_host_key_mismatch),
    /** The imported known_hosts trusts a certificate authority and the server key matched nothing (`ssh_host_key_ca_required`). */
    HOST_KEY_CA_REQUIRED(R.string.vpn_error_host_key_ca_required),
    /** An SSH session had neither an imported known_hosts file nor the accept-new opt-in. */
    SSH_KNOWN_HOSTS_MISSING(R.string.vpn_error_ssh_known_hosts_missing),
    /** The profile's stored trusted host keys could not be read or Core refused them; nothing was dialed. */
    TRUSTED_KEYS_UNREADABLE(R.string.vpn_error_trusted_keys_unreadable),
    /** An SSH file reference points outside the profile's imported files (decision 8); nothing was dialed. */
    SSH_FILE_OUTSIDE_APP(R.string.vpn_error_ssh_file_outside_app),
    RECOVERY_NEEDS_USER(R.string.vpn_error_recovery_needs_user),
    SSCONF_NETWORK_UNAVAILABLE(R.string.vpn_error_ssconf_network_unavailable),
    SSCONF_FETCH_FAILED(R.string.vpn_error_ssconf_fetch_failed),
    SSCONF_SNAPSHOT_INVALID(R.string.vpn_error_ssconf_snapshot_invalid),
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
     * A user cancel of attempt [token] ends it with AUTH_CANCELLED. User intent wins over an auth/SSH
     * failure code a racing status tick already published under the same token (it was derived from
     * the history before the cancel was recorded). Any other recorded code is kept, as in [publishFailure].
     */
    internal fun publishUserCancel(message: String, id: String?, token: Long) = gate.commit(token) {
        val previous = state.value
        val recorded = previous.attemptProfileId == id && previous.code != null &&
            (previous.state == "failed" || previous.state == "disconnected")
        if (!recorded || previous.code in USER_CANCEL_REPLACES) {
            publish(VpnState("failed", message = message, attemptProfileId = id, code = VpnErrorCode.AUTH_CANCELLED), token)
        } else if (previous.state != "failed") {
            publish(previous.copy(state = "failed"), token)
        }
    }

    /**
     * Publishes or withdraws the auth offer of attempt [token]. A stale token, an offer tagged with
     * another token, an offer while the attempt is already terminal, or an offer for which [stillCurrent]
     * is false has no effect. [stillCurrent] runs under the gate monitor.
     *
     * Lock order: the gate monitor may be held while taking an [AuthAttempt] lock (via [stillCurrent]),
     * never the reverse; callers must not hold an [AuthAttempt] lock when calling this.
     */
    internal fun publishAuth(token: Long, offer: AuthOffer?, stillCurrent: () -> Boolean = { true }) = gate.commit(token) {
        if (offer == null) auth.value = null
        else if (offer.token == token && state.value.state !in TERMINAL_STATES && stillCurrent()) auth.value = offer
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

/** Codes a status tick may infer from a stale auth history; a user cancel replaces them (§5.3). */
private val USER_CANCEL_REPLACES = setOf(
    VpnErrorCode.SSH_CONNECTION_FAILED, VpnErrorCode.AUTH_FAILED,
    VpnErrorCode.AUTH_UNANSWERED, VpnErrorCode.AUTH_CANCELLED,
)

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

/**
 * Explicit SSH host-key faults of an opted-in session (core ADR 0035). They name the cause, so they take
 * precedence over the [AuthHistory] guess. `ssh_host_key_unknown` (no offer could be made) stays generic.
 */
internal val SSH_HOST_KEY_FAULTS = mapOf(
    "ssh_host_key_mismatch" to VpnErrorCode.HOST_KEY_MISMATCH,
    "ssh_host_key_rejected" to VpnErrorCode.AUTH_CANCELLED,
    "ssh_host_key_unanswered" to VpnErrorCode.AUTH_UNANSWERED,
    "ssh_host_key_unknown" to VpnErrorCode.SSH_CONNECTION_FAILED,
    // Forgetting app-owned keys cannot help here, so it gets its own message instead of HOST_KEY_MISMATCH.
    "ssh_host_key_ca_required" to VpnErrorCode.HOST_KEY_CA_REQUIRED,
    // Core ADR 0036 (reported with or without the opt-in): SSH without imported known_hosts and without the opt-in.
    "ssh_known_hosts_missing" to VpnErrorCode.SSH_KNOWN_HOSTS_MISSING,
)

internal fun coreFailureCode(
    state: String, lastError: String?, history: AuthHistory = AuthHistory.NONE,
): VpnErrorCode? = when {
    lastError != null && lastError in SSH_HOST_KEY_FAULTS -> SSH_HOST_KEY_FAULTS.getValue(lastError)
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

/** Fixed code published for an exception that ended a session attempt; never derived from its text. */
internal fun sessionFailureCode(error: Exception): VpnErrorCode = when (error) {
    is BimeronVpnService.SessionFailure -> error.code
    is SSConfFailure -> error.code
    else -> VpnErrorCode.START_FAILED
}

/**
 * Native session creation order for one attempt (contract §7.2 step 3). When [snapshot] is set
 * (an SSConf profile) it runs first, before [acquireNative], [canStartVpn] and any session factory,
 * and the session is created only through [newSessionWithSnapshot]. Otherwise [newSession] is used.
 * [acquireNative] records ownership of the native cleanup gate when it returns true.
 */
internal suspend fun <S, P : Any> createCoreSession(
    snapshot: (suspend () -> P)?,
    ensureAttempt: suspend () -> Unit,
    acquireNative: () -> Boolean,
    canStartVpn: () -> Boolean,
    newSession: () -> S,
    newSessionWithSnapshot: (P) -> S,
): S {
    val resolved = snapshot?.invoke()
    ensureAttempt()
    if (!acquireNative()) throw BimeronVpnService.SessionFailure(VpnErrorCode.CLEANUP_BLOCKED)
    if (!canStartVpn()) throw BimeronVpnService.SessionFailure(VpnErrorCode.CORE_INCOMPATIBLE)
    ensureAttempt()
    return if (resolved != null) newSessionWithSnapshot(resolved) else newSession()
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
 * A user cancel ends attempt [token] whatever its state (connecting, reconnecting or connected) and
 * whether or not Core accepted `cancelAuth`: AUTH_CANCELLED is published under the token (replacing an
 * auth/SSH code a racing tick published) and the owning run job is cancelled, which takes the normal
 * finally/Command.Finished path (auth cleared, recovery cleared, no reconnect fallback).
 */
internal fun stopAfterUserCancel(id: String, token: Long, message: String, owner: kotlinx.coroutines.Job?) {
    VpnLive.publishUserCancel(message, id, token)
    owner?.cancel()
}

/** The Core auth surface of a started session; wraps `Session` so the attempt logic runs without JNI. */
internal interface CoreSessionPort {
    fun pendingAuth(): String?
    fun replyAuth(sessionId: String, requestId: String, answers: String)
    fun cancelAuth(sessionId: String, requestId: String)
}

/**
 * Auth state of one attempt token. [poll] runs on the attempt's status coroutine (never concurrently with
 * itself); [deliver] runs on IO from [VpnAuthEndpoint]. Answers pass straight to Core and are never stored.
 *
 * [lock] is a leaf lock: nothing else (in particular the gate monitor) is taken while it is held.
 */
internal class AuthAttempt(
    private val token: Long,
    private val port: CoreSessionPort,
    private val isOpen: () -> Boolean,
    private val onCancelled: () -> Unit,
    /**
     * Runs on IO, outside every lock, after Core accepted `["accept"]` for a host-key offer. Receives the
     * `knownHostsLine` of the validated offer this attempt published under the same ids, never UI input.
     * Must not throw; the session proceeds whatever it does.
     */
    private val onHostKeyAccepted: (knownHostsLine: String) -> Unit = {},
) : AuthSink {
    private val lock = Any()
    private var offered: Pair<String, String>? = null
    /** knownHostsLine per published host-key offer, at most Core's eight rounds; removed when answered. */
    private val hostKeyLines = LinkedHashMap<Pair<String, String>, String>()
    private val answered = mutableSetOf<Pair<String, String>>()
    var history = AuthHistory.NONE
        get() = synchronized(lock) { field }
        private set

    fun poll() {
        val wire = try { port.pendingAuth() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        val request = try { SshAuthenticationController.parse(wire) } catch (_: Exception) { null }
        // Decide under the leaf lock; publish after releasing it (the gate monitor is never taken under it).
        val key = request?.let { it.sessionId to it.requestId }
        synchronized(lock) {
            if (key == offered) return
            offered?.let { if (it !in answered && history != AuthHistory.CANCELLED_BY_USER) history = AuthHistory.WITHDRAWN }
            offered = key
            if (key != null && wire != null && history != AuthHistory.CANCELLED_BY_USER) history = AuthHistory.OFFERED
            request?.hostKey?.let { offer ->
                if (key != null && wire != null) {
                    if (hostKeyLines.size >= 8) hostKeyLines.remove(hostKeyLines.keys.first())
                    hostKeyLines[key] = offer.knownHostsLine
                }
            }
        }
        if (key == null || wire == null) {
            VpnLive.publishAuth(token, null)
            return
        }
        // Re-checked under the gate: the offer is still this attempt's current one and the user has not
        // cancelled since the decision (a cancel also makes the state terminal, which publishAuth checks).
        VpnLive.publishAuth(token, AuthOffer(token, key.first, key.second, wire)) {
            synchronized(lock) { offered == key && history != AuthHistory.CANCELLED_BY_USER }
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
            // Recorded before Core is called, so a status tick that sees the resulting SSH failure
            // already derives AUTH_CANCELLED.
            synchronized(lock) {
                answered.add(key)
                history = AuthHistory.CANCELLED_BY_USER
            }
            // The endpoint validated token, registration and offer: the user's cancel stops the attempt
            // even if Core rejects it (e.g. it already withdrew the request). A Core exception still
            // propagates, so the endpoint reports REJECTED.
            synchronized(lock) { hostKeyLines.remove(key) }
            try {
                port.cancelAuth(sessionId, requestId)
            } finally {
                onCancelled()
            }
        } else {
            port.replyAuth(sessionId, requestId, answers)
            val accepted = synchronized(lock) {
                answered.add(key)
                // A newer offer seen meanwhile keeps OFFERED; otherwise the reply is the last event.
                if (history != AuthHistory.CANCELLED_BY_USER && (offered == null || offered == key)) history = AuthHistory.REPLIED
                hostKeyLines.remove(key)
            }
            // Core accepted the reply, so the user's trust is final: persist only the offer's own line.
            if (accepted != null && answers == SshAuthenticationController.HOST_KEY_ACCEPT) {
                try { onHostKeyAccepted(accepted) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            }
        }
        return true
    }
}

/** Protocols Core starts without any SSH handshake, named as `[AccessKey]` source keys (§6). */
internal val NON_INTERACTIVE_PROTOCOLS = setOf(
    "SS", "SSConf", "SOCKS5", "HTTP", "MASQUE", "VLESS", "VMess", "Trojan", "Naive", "Hysteria2", "AnyTLS", "TUIC",
)

/** Allowlist name of each structured `Type =` form; SSH and unknown types have none. */
private val NON_INTERACTIVE_FORMS: Map<ProtocolFormDescriptor, String> = mapOf(
    ShadowsocksForm to "SS", Socks5Form to "SOCKS5", VlessProtocolForm to "VLESS", HttpConnectForm to "HTTP",
    NaiveH2Form to "Naive", MasqueForm to "MASQUE", TrojanProtocolForm to "Trojan",
)

/**
 * Conservative: true unless the profile is positively known to be non-interactive (§6).
 * Core installs the SSH prompt for every SSH profile, so any SSH marker, any unknown protocol
 * and any input that does not parse cleanly counts as possibly interactive.
 */
internal fun mayNeedInteractiveAuth(input: SessionInput?): Boolean {
    if (input == null) return true
    return try {
        val text = Conf.read(input.bytes)
        val profile = Conf.parse(text)
        val document = ConfigDocument.parse(text)
        val access = document.values("AccessKey")
        when {
            access.isEmpty() -> true
            access.keys.any { it.equals("SSH", ignoreCase = true) } -> true
            access.entries.any { it.key.equals("Type", ignoreCase = true) && coreTrim(it.value).equals("ssh", ignoreCase = true) } -> true
            access.values.any { coreTrim(it).startsWith("ssh://", ignoreCase = true) } -> true
            else -> recoveryProtocol(document, profile) !in NON_INTERACTIVE_PROTOCOLS
        }
    } catch (_: Exception) {
        true
    }
}

private val KILL_SWITCH_ON = Regex("(?m)^\\s*KillSwitch\\s*=\\s*true\\s*$")

/**
 * The UI's start rule (MainActivity, AppModel): Android runs only `Mode = vpn` without the Core kill switch.
 * Legacy profiles outside it can still be loaded, so a recovery start checks it as well.
 */
internal fun runsOnAndroid(input: SessionInput?): Boolean {
    if (input == null) return false
    return try {
        val profile = Conf.parse(Conf.read(input.bytes))
        profile.mode == "vpn" && !KILL_SWITCH_ON.containsMatchIn(profile.text)
    } catch (_: Exception) {
        false
    }
}

/** A recovery start proceeds only for a profile that is non-interactive and runs on Android. */
internal fun mustSkipRecovery(input: SessionInput?): Boolean = mayNeedInteractiveAuth(input) || !runsOnAndroid(input)

/** Go `unicode.IsSpace`, which `strings.TrimSpace` uses: Unicode White_Space, including U+0085. */
private fun Char.isGoSpace(): Boolean = this in '\u0009'..'\u000d' || this == ' ' || this == '\u0085' || this == '\u00a0' ||
    this == '\u1680' || this in '\u2000'..'\u200a' || this == '\u2028' || this == '\u2029' || this == '\u202f' ||
    this == '\u205f' || this == '\u3000'

/**
 * A value as Core dispatches it: Core trims Go white space, strips one leading BOM and trims again
 * (`core: internal/config/config.go:530`). Stripping any run of both is a superset, so a value Core
 * would treat as `ssh://` is never missed (conservative).
 */
internal fun coreTrim(value: String): String = value.trim { it == '\uFEFF' || it.isGoSpace() }

/** Allowlist name of the profile's protocol, or null when it cannot be determined without doubt. */
private fun recoveryProtocol(document: ConfigDocument, profile: Profile): String? {
    val source = document.source() ?: return NON_INTERACTIVE_FORMS[ProtocolForms.descriptor(document)]
    // Both parsers must agree on the single source key.
    if (source.first != profile.protocol) return null
    return when (source.first) {
        // A generic key resolves to its scheme's source field; an unsupported scheme throws.
        "Key" -> Conf.keyField(source.second)
        // An external file is not part of the session input.
        "File" -> null
        else -> source.first
    }
}

/**
 * Service effects of a start, behind a seam so the recovery wiring is testable on the JVM (§6, §13.1).
 * The service implements it with the store, its preferences, the notification manager and the journal.
 */
internal interface RecoveryStartPort {
    /** The single store-backed read of [id] under [token]; any failure is null. A real cancellation propagates. */
    suspend fun read(id: String, token: Long): SessionInput?
    /** Clears the persisted recovery id; false when it could not be saved. */
    suspend fun clearRecovery(): Boolean
    val destroyed: Boolean
    fun postNotification()
    fun cancelNotification()
    /** Journals the skip with a fixed [message]. */
    fun recordSkip(@StringRes message: Int)
    fun publishSkipped(token: Long)
}

internal sealed interface StartPlan {
    /** Start the session. [input] is the classified recovery read, reused by the session; null for a user start. */
    data class Proceed(val input: SessionInput?) : StartPlan
    /** A possibly interactive recovery was skipped and its effects were applied; leave the foreground. */
    data object Skipped : StartPlan
    /** Another attempt took the gate while the profile was read; it owns the state, so leave silently. */
    data object Superseded : StartPlan
    /** The service was destroyed meanwhile; stop consuming commands. */
    data object Destroyed : StartPlan
}

/**
 * The profile an always-on start (`VpnService.SERVICE_INTERFACE`, sent by Android on boot, user unlock,
 * package update or when always-on is turned on) connects: the interrupted session's recovery id, else
 * the profile the user last selected. Anything that is not a profile UUID is ignored; null means no target.
 */
internal fun alwaysOnTarget(recoveryId: String?, selectedId: String?): String? =
    sequenceOf(recoveryId, selectedId).firstOrNull { it != null && runCatching { UUID.fromString(it) }.isSuccess }

/** Called for every accepted start: a user start retires a pending "Tap to reconnect" notification. */
internal fun noteStartCommand(recovery: Boolean, port: RecoveryStartPort) {
    if (!recovery) port.cancelNotification()
}

/**
 * Plans a start. A user start is never read or classified here. A recovery start (anything but the
 * explicit user START) reads the profile once, classifies that read off Main on [classifier], and
 * proceeds only when the profile is positively non-interactive: recovery never shows UI, so a possible
 * auth prompt skips the start instead (§6, §11.14). An ssconf profile without a persisted snapshot is
 * skipped the same way, because a recovery never fetches (§7.2). The proceeding plan carries the classified
 * input, so the session starts exactly what was classified.
 */
internal suspend fun planStart(
    recovery: Boolean,
    id: String,
    token: Long,
    port: RecoveryStartPort,
    classify: (SessionInput?) -> Boolean = ::mustSkipRecovery,
    classifier: CoroutineContext = Dispatchers.Default,
): StartPlan {
    if (!recovery) return StartPlan.Proceed(null)
    val input = port.read(id, token)
    val interactive = withContext(classifier) { classify(input) }
    currentCoroutineContext().ensureActive()
    if (port.destroyed) return StartPlan.Destroyed
    if (!VpnLive.gate.accepts(token)) return StartPlan.Superseded
    val noSnapshot = input != null && recoveryLacksSnapshot(input)
    if (!interactive && input != null && !noSnapshot) return StartPlan.Proceed(input)
    port.clearRecovery()
    currentCoroutineContext().ensureActive()
    if (port.destroyed) return StartPlan.Destroyed
    port.postNotification()
    port.recordSkip(if (!interactive && noSnapshot) R.string.journal_recovery_ssconf_unavailable else R.string.journal_recovery_needs_user)
    port.publishSkipped(token)
    return StartPlan.Skipped
}

/**
 * Reuses a classified recovery read instead of reading again, so a concurrent edit cannot slip between
 * classification and start. Fails closed: a stale attempt or a stale ticket is a cancellation, and an
 * input for another profile is rejected; it never falls back to an unclassified read.
 */
internal suspend fun reuseClassifiedInput(input: SessionInput, id: String, token: Long): SessionInput {
    ensureSessionAttempt(token)
    if (input.ticket?.stillValid?.invoke() == false) throw CancellationException("Stale recovery input")
    require(input.id.toString() == id) { "Classified input is for another profile" }
    return input
}

/**
 * The session's input: a recovery start reuses its [classified] read and never calls [read]; a user
 * start (null [classified]) performs its single [read]. There is no other path to a session input.
 */
/**
 * A recovery never fetches (§7.2), so an ssconf profile without a persisted snapshot cannot start unattended
 * and is skipped like a possibly interactive one. A persisted body that Core rejects is caught by the session.
 */
internal fun recoveryLacksSnapshot(input: SessionInput): Boolean = input.ssconf != null && input.snapshot == null

/** A start is a recovery exactly when [planStart] handed the session its classified read; a user start has none. */
internal fun isRecoveryStart(classified: SessionInput?): Boolean = classified != null

internal suspend fun sessionInputFor(
    classified: SessionInput?,
    id: String,
    token: Long,
    read: suspend () -> SessionInput,
): SessionInput = if (classified != null) reuseClassifiedInput(classified, id, token) else read()

/** The published state of a skipped recovery: a fixed code and message, no profile id or traffic. */
internal fun recoverySkippedState(text: (Int) -> String): VpnState = failureState(VpnErrorCode.RECOVERY_NEEDS_USER, text)

/**
 * Accept-new host-key opt-in bytes for [input] (core ADR 0035), or null for no opt-in. Every possibly SSH
 * profile opts in (the conservative [sshCapable] classification), even with no stored keys, so an unknown
 * host is offered instead of failing; an imported KnownHostsFile stays trusted alongside. Runs before the
 * native session is created, guarded by [token] on both sides.
 *
 * Fails closed: if the store cannot be read (I/O error, symlink, or any line that is not a valid offer line)
 * the attempt ends with [VpnErrorCode.TRUSTED_KEYS_UNREADABLE] before any native session. Opting in with no
 * keys instead would turn a changed key for a trusted host into a "New server" offer. Forget recovers.
 */
internal suspend fun trustedHostKeysFor(
    input: SessionInput,
    token: Long,
    read: suspend (java.util.UUID) -> ByteArray,
    sshCapable: (SessionInput) -> Boolean = ::mayNeedInteractiveAuth,
): ByteArray? {
    ensureSessionAttempt(token)
    if (!sshCapable(input)) return null
    val bytes = try {
        read(input.id)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        throw BimeronVpnService.SessionFailure(VpnErrorCode.TRUSTED_KEYS_UNREADABLE)
    }
    ensureSessionAttempt(token)
    return bytes
}

/**
 * Starts a created session: the host-key opt-in, when [trusted] is set, always precedes [start]. If Core
 * refuses the stored lines the attempt fails with [VpnErrorCode.TRUSTED_KEYS_UNREADABLE] and [start] is never
 * called. (Starting without the opt-in could not prompt, since Core then refuses unknown hosts or reports
 * `ssh_known_hosts_missing`, but it would hide the actual cause and its remedy, Forget.)
 */
internal fun startCoreSession(trusted: ByteArray?, optIn: (ByteArray) -> Unit, start: () -> Unit) {
    if (trusted != null) {
        try { optIn(trusted) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { throw BimeronVpnService.SessionFailure(VpnErrorCode.TRUSTED_KEYS_UNREADABLE) }
    }
    start()
}
