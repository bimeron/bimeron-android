package org.bimeron.app

// Temporary diagnostics: only fixed codes and counts may enter failure reports.
internal enum class SSConfDiagnostic {
    NONE, ACTIVE, INVALIDATED, CLOSED, REGISTERED, REGISTRATION_REJECTED, UNSPECIFIED, NETWORK_SELECTION, NETWORK_LOST, NETWORK_DNS,
    SERVICE_REVOKED, SERVICE_DESTROYED, SERVICE_DISCONNECTED, BINDING_DIED, CANCELLATION,
    AVAILABLE, CONSENT_REQUIRED, PREPARE_EXCEPTION,
    STATE_INVALID, FD_RANGE, RETURNED_TRUE, RETURNED_FALSE, EXCEPTION,
    PROTECT_BEFORE_SERVICE, PROTECT_PLATFORM, PROTECT_AFTER_SERVICE,
}

/** Scope-local lifetime. Normal release is deliberately not an environment event. */
internal class SSConfLeaseState(
    private val captureCandidates: Int = 0,
    private val captureReady: Int = 0,
    private val captureScore: Int = -1,
) {
    private val lock = Any()
    private var alive = true
    private var invalidated = false
    private var firstInvalidation = SSConfDiagnostic.NONE
    private var invalidationCandidates = -1
    private var invalidationScore = -1
    private var fdResult = SSConfDiagnostic.NONE
    internal fun diagnostics(): String = synchronized(lock) {
        val status = when {
            invalidated -> SSConfDiagnostic.INVALIDATED
            alive -> SSConfDiagnostic.ACTIVE
            else -> SSConfDiagnostic.CLOSED
        }
        "state=$status captureCandidates=$captureCandidates captureReady=$captureReady captureScore=$captureScore " +
            "first=$firstInvalidation invalidationCandidates=$invalidationCandidates " +
            "invalidationScore=$invalidationScore fdResult=$fdResult"
    }
    private val listeners = mutableSetOf<() -> Unit>()

    fun isValid(): Boolean = synchronized(lock) { alive }

    fun onInvalidated(cancel: () -> Unit): AutoCloseable {
        val registration = { cancel() }
        val immediate = synchronized(lock) {
            if (invalidated) true else {
                if (alive) listeners.add(registration)
                false
            }
        }
        if (immediate) runCatching(registration)
        return AutoCloseable { synchronized(lock) { listeners.remove(registration) } }
    }

    fun invalidate(reason: SSConfDiagnostic = SSConfDiagnostic.UNSPECIFIED) = invalidateDeferred(reason).invoke()

    internal fun invalidateDeferred(
        reason: SSConfDiagnostic = SSConfDiagnostic.UNSPECIFIED,
        candidates: Int = -1,
        score: Int = -1,
    ): () -> Unit {
        val callbacks = synchronized(lock) {
            if (!alive) return {}
            alive = false
            invalidated = true
            firstInvalidation = reason
            invalidationCandidates = candidates
            invalidationScore = score
            listeners.toList().also { listeners.clear() }
        }
        return { callbacks.forEach { runCatching(it) } }
    }

    fun close() = synchronized(lock) { alive = false; listeners.clear() }

    fun fd(fd: Long, operation: (Int) -> Boolean): Boolean = synchronized(lock) {
        fdResult = when {
            !alive -> SSConfDiagnostic.STATE_INVALID
            fd !in 0..Int.MAX_VALUE.toLong() -> SSConfDiagnostic.FD_RANGE
            else -> runCatching {
                if (operation(fd.toInt())) SSConfDiagnostic.RETURNED_TRUE else SSConfDiagnostic.RETURNED_FALSE
            }.getOrDefault(SSConfDiagnostic.EXCEPTION)
        }
        fdResult == SSConfDiagnostic.RETURNED_TRUE
    }
}
