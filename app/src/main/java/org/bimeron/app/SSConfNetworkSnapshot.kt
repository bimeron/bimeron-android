package org.bimeron.app

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Callback-owned state; no platform re-query can overwrite a newer callback.
 *
 * Android delivers the initial callbacks of each matching network one network at a time, so a
 * lease taken on the first network with DNS can be invalidated by the next network's initial
 * delivery. Capture therefore publishes only after [settleMillis] without a state change.
 * Readiness and the state revision are observed atomically, so a change that lands right after an
 * observation (even one that makes the state ready) can never be published without its own
 * quiet window. [afterObservation] is a test seam for exactly that gap.
 */
internal class SSConfNetworkSnapshot<N : Any>(
    private val settleMillis: Long = 300,
    private val afterObservation: () -> Unit = {},
) {
    data class Capture<N>(val network: N, val dns: String, val state: SSConfLeaseState)
    private val lock = Any()
    private val scores = linkedMapOf<N, Int>()
    private val dns = mutableMapOf<N, String>()
    private val updates = Channel<Unit>(Channel.CONFLATED)
    private var captured: Capture<N>? = null
    private var revision = 0L

    private fun selected() = scores.maxByOrNull { it.value }?.key
    private fun update(lost: N? = null, change: () -> Boolean) {
        val notify = synchronized(lock) {
            // Repeated identical callbacks (e.g. signal strength) must not extend settling.
            if (!change()) return
            revision++
            updates.trySend(Unit)
            captured?.takeIf { selected() != it.network || dns[it.network] != it.dns }?.let {
                it.state.invalidateDeferred(when {
                    lost == it.network -> SSConfDiagnostic.NETWORK_LOST
                    selected() != it.network -> SSConfDiagnostic.NETWORK_SELECTION
                    else -> SSConfDiagnostic.NETWORK_DNS
                }, scores.size, scores[selected()] ?: -1)
            }
        }
        notify?.invoke()
    }
    fun capabilities(network: N, score: Int?) = update {
        if (score == null) scores.remove(network) != null else scores.put(network, score) != score
    }
    fun properties(network: N, value: String) = update { dns.put(network, value) != value }
    fun lost(network: N) = update(lost = network) {
        val removedScore = scores.remove(network) != null
        dns.remove(network) != null || removedScore
    }

    private fun ready() = selected()?.let { dns.containsKey(it) } == true

    suspend fun capture(): Capture<N> = withTimeout(5_000) {
        while (true) {
            val observed = synchronized(lock) {
                // Drained wakeups are reflected in this observation; later changes signal again.
                updates.tryReceive()
                if (ready()) revision else null
            }
            afterObservation()
            if (observed == null) {
                updates.receive()
                continue
            }
            // Bounded by the outer timeout; every change restarts the quiet window.
            if (withTimeoutOrNull(settleMillis) { updates.receive() } != null) continue
            val result = synchronized(lock) {
                // Also catches a change whose wakeup raced the quiet-window timeout.
                if (revision != observed) null else {
                    val network = checkNotNull(selected())
                    val value = dns.getValue(network)
                    if (value.isEmpty()) throw SSConfNetworkUnavailableException()
                    Capture(network, value, SSConfLeaseState(
                        scores.size, scores.keys.count { !dns[it].isNullOrEmpty() }, scores.getValue(network),
                    )).also { captured = it }
                }
            }
            if (result != null) return@withTimeout result
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
}
