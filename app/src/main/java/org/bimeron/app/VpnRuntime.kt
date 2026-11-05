package org.bimeron.app

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicLong

data class VpnState(
    val state: String = "disconnected",
    val received: Long = 0,
    val sent: Long = 0,
    val message: String? = null,
    val activeProfileId: String? = null,
    val attemptProfileId: String? = null,
    val connectedAtElapsed: Long = 0,
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
        if (previous.state != next.state) {
            when (next.state) {
                "connecting" -> AppEvents.add(R.string.journal_vpn_connecting, LogLevel.INFO)
                "connected" -> AppEvents.add(R.string.journal_vpn_connected, LogLevel.INFO)
                "reconnecting" -> AppEvents.add(R.string.journal_vpn_reconnecting, LogLevel.INFO)
                "stopping" -> AppEvents.add(R.string.journal_vpn_stopping, LogLevel.INFO)
                "disconnected" -> if (previous.state != "disconnected") AppEvents.add(R.string.journal_vpn_disconnected, LogLevel.INFO)
                "failed" -> next.message?.let { AppEvents.add(R.string.journal_vpn_failed_detail, LogLevel.ERROR, it) }
                    ?: AppEvents.add(R.string.journal_vpn_failed, LogLevel.ERROR)
            }
        }
    }

    internal fun publishCoreStatus(
        token: Long, id: String, rawState: String, received: Long, sent: Long,
        now: Long, message: String? = null,
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
            if (active) id else null, id, started), token)
        next
    }

    fun serviceDestroyed(message: String) = synchronized(gate) {
        gate.next()
        val previous = state.value
        if (previous.state != "failed" && previous.state != "disconnected") {
            state.value = previous.copy(state = "failed", message = message)
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
