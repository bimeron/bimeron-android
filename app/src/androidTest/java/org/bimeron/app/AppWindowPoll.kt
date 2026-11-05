package org.bimeron.app

/** Pure polling seam: a missing app window is never evidence of absence. */
internal class AppWindowPoll<N>(
    private val roots: () -> List<N>,
    private val now: () -> Long,
    private val idle: (Long) -> Unit,
    private val find: (N, (N) -> Boolean) -> N?,
) {
    fun isAbsent(predicate: (N) -> Boolean): Boolean {
        val current = roots()
        return current.isNotEmpty() && current.all { find(it, predicate) == null }
    }

    fun waitNode(timeout: Long, predicate: (N) -> Boolean): N? {
        var match: N? = null
        await(timeout) {
            match = roots().firstNotNullOfOrNull { find(it, predicate) }
            match != null
        }
        return match
    }

    fun waitAbsent(timeout: Long, predicate: (N) -> Boolean): Boolean =
        await(timeout) { isAbsent(predicate) }

    private fun await(timeout: Long, ready: () -> Boolean): Boolean {
        val deadline = now() + timeout
        while (now() < deadline) {
            if (ready()) return true
            val remaining = deadline - now()
            if (remaining <= 0) break
            idle(minOf(100L, remaining))
        }
        return false
    }
}

internal fun <N> appWindowRoots(roots: List<N>, packageName: (N) -> String?): List<N> =
    roots.filter { packageName(it) == "org.bimeron.app" }
