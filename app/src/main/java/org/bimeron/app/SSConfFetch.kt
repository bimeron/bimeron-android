package org.bimeron.app

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.bimeron.core.androidcore.Androidcore
import org.bimeron.core.androidcore.SSConfSnapshot
import org.bimeron.core.androidcore.SSConfSocketProtector

/**
 * An SSConf failure carrying only a fixed [VpnErrorCode]. It never carries a message,
 * a cause, the source, the body or lease diagnostics.
 */
internal class SSConfFailure(val code: VpnErrorCode) : Exception() {
    override val message: String get() = code.name
    override fun fillInStackTrace(): Throwable = this
}

/** Fixed journal text for a validated snapshot that could not be persisted; not a [VpnErrorCode]. */
internal const val SNAPSHOT_NOT_PERSISTED = "SNAPSHOT_NOT_PERSISTED"

/** The part of an [SSConfNetworkLease] that [fetchSSConf] uses; a seam for JVM tests. */
internal interface SSConfFetchLease {
    val networkID: String
    val dns: String
    fun isValid(): Boolean
    fun onInvalidated(cancel: () -> Unit): AutoCloseable
    fun protect(fd: Long): Boolean
    fun bind(fd: Long): Boolean
}

/** One native SSConf request: [resolve] blocks until the body arrives or fails; [cancel] aborts it. */
internal interface SSConfFetchRequest {
    fun resolve(): ByteArray
    fun cancel()
}

/** Creates a request from (source, networkID, dns, protector). */
internal typealias SSConfRequestFactory = (String, String, String, SSConfSocketProtector) -> SSConfFetchRequest

/** Runs a non-suspending block inside one lease scope and returns its result. */
internal typealias SSConfLeaseScope = suspend (block: (SSConfFetchLease) -> ByteArray) -> ByteArray

/**
 * Sole consumer of [SSConfNetworkLease] / [withSSConfNetwork]. Bind-only; never starts a VPN.
 * Must be called off Main. Performs exactly one request with no retry and returns the raw body;
 * the caller validates it through Core before use.
 *
 * Failures map only to [VpnErrorCode.SSCONF_NETWORK_UNAVAILABLE] or [VpnErrorCode.SSCONF_FETCH_FAILED].
 * Coroutine cancellation is rethrown as is. Native load Errors ([LinkageError]) are not mapped and
 * propagate out of [fetchSSConf]; [resolveSessionSnapshot] maps them to [VpnErrorCode.CORE_INCOMPATIBLE].
 */
internal suspend fun fetchSSConf(context: Context, source: String): ByteArray = fetchSSConf(
    source,
    leaseScope = { block -> withSSConfNetwork(context) { lease -> block(lease.asFetchLease()) } },
    newRequest = ::coreSSConfRequest,
)

/**
 * [fetchSSConf] with an injectable [leaseScope] and [newRequest]. Request creation or resolve
 * failing while the lease is invalidated is a network loss ([VpnErrorCode.SSCONF_NETWORK_UNAVAILABLE]);
 * otherwise it is [VpnErrorCode.SSCONF_FETCH_FAILED]. The invalidation registration is closed on
 * every path, and cancellation of the caller wins over any failure.
 */
internal suspend fun fetchSSConf(
    source: String,
    leaseScope: SSConfLeaseScope,
    newRequest: SSConfRequestFactory,
): ByteArray = try {
    leaseScope { lease ->
        // Only a fixed code leaves this scope; the cause may carry Core diagnostics.
        fun failure() = SSConfFailure(
            if (lease.isValid()) VpnErrorCode.SSCONF_FETCH_FAILED else VpnErrorCode.SSCONF_NETWORK_UNAVAILABLE,
        )
        val request = try {
            newRequest(source, lease.networkID, lease.dns, lease.protector())
        } catch (_: Exception) {
            throw failure()
        }
        lease.onInvalidated { request.cancel() }.use {
            try {
                request.resolve()
            } catch (_: Exception) {
                // A resolve cancelled by lease invalidation is a network loss, not a fetch error.
                throw failure()
            }
        }
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    // Cancellation wins over any failure raised while the scope was being torn down.
    currentCoroutineContext().ensureActive()
    throw when (failure) {
        is SSConfFailure -> failure
        is SSConfNetworkUnavailableException -> SSConfFailure(VpnErrorCode.SSCONF_NETWORK_UNAVAILABLE)
        else -> SSConfFailure(VpnErrorCode.SSCONF_FETCH_FAILED)
    }
}

private fun coreSSConfRequest(
    source: String,
    networkID: String,
    dns: String,
    protector: SSConfSocketProtector,
): SSConfFetchRequest {
    val request = Androidcore.newSSConfRequest(source, networkID, dns, protector)
    return object : SSConfFetchRequest {
        override fun resolve(): ByteArray = request.resolve().content()
        override fun cancel() = request.cancel()
    }
}

private fun SSConfNetworkLease.asFetchLease(): SSConfFetchLease {
    val lease = this
    return object : SSConfFetchLease {
        override val networkID: String get() = lease.networkID
        override val dns: String get() = lease.dns
        override fun isValid(): Boolean = lease.isValid()
        override fun onInvalidated(cancel: () -> Unit): AutoCloseable = lease.onInvalidated(cancel)
        override fun protect(fd: Long): Boolean = lease.protect(fd)
        override fun bind(fd: Long): Boolean = lease.bind(fd)
    }
}

private fun SSConfFetchLease.protector() = object : SSConfSocketProtector {
    override fun protect(fd: Long): Boolean = this@protector.protect(fd)
    override fun bind(fd: Long): Boolean = this@protector.bind(fd)
}

/**
 * Obtains the Core snapshot handle for an SSConf session attempt (contract §7.2 step 2).
 *
 * - A [stored] snapshot is reused without network access. If [validate] rejects it, it is discarded
 *   through [discard] and treated as absent.
 * - When absent, exactly one [fetch] runs. A body outside 1..[SSConfSnapshots.MAX_BODY] or rejected by
 *   [validate] fails with [VpnErrorCode.SSCONF_SNAPSHOT_INVALID] and is never committed.
 * - A validated fetched body is committed: [SnapshotCommitResult.Committed] continues,
 *   [SnapshotCommitResult.NotPersisted] continues with the in-memory body after [warn] records
 *   [SNAPSHOT_NOT_PERSISTED], and any [SnapshotCommitResult.Conflict] fails with
 *   [VpnErrorCode.PROFILE_CHANGED].
 *
 * [ensureAttempt] runs before the first step and after every suspension point; it throws
 * [CancellationException] for a stale attempt, so a commit conflict caused by a stale attempt is
 * cancellation rather than [VpnErrorCode.PROFILE_CHANGED]. [validate] throws when Core rejects a body.
 *
 * This step runs before the capability check, so a native load failure from [validate] or [fetch]
 * (see [isNativeLoadFailure]) fails with [VpnErrorCode.CORE_INCOMPATIBLE] instead of escaping as an
 * [Error]. The mapping covers only those two Core calls: any [Error] from [ensureAttempt], [discard]
 * or [commit], and every other error, propagates.
 */
internal suspend fun <T> resolveSessionSnapshot(
    ticket: SnapshotTicket,
    stored: StoredSnapshot?,
    ensureAttempt: suspend () -> Unit,
    validate: suspend (ByteArray) -> T,
    fetch: suspend () -> ByteArray,
    discard: suspend (SnapshotTicket, Sha256) -> Unit,
    commit: suspend (SnapshotTicket, ByteArray) -> SnapshotCommitResult,
    warn: (String) -> Unit,
): T {
    ensureAttempt()
    if (stored != null) {
        val reused = validateOrNull(validate, stored.body)
        ensureAttempt()
        if (reused != null) return reused
        discard(ticket, stored.digest)
        ensureAttempt()
    }
    val body = coreCall(fetch)
    ensureAttempt()
    if (body.size !in 1..SSConfSnapshots.MAX_BODY) throw SSConfFailure(VpnErrorCode.SSCONF_SNAPSHOT_INVALID)
    val fetched = validateOrNull(validate, body)
    ensureAttempt()
    if (fetched == null) throw SSConfFailure(VpnErrorCode.SSCONF_SNAPSHOT_INVALID)
    val result = commit(ticket, body)
    ensureAttempt()
    return when (result) {
        is SnapshotCommitResult.Committed -> fetched
        SnapshotCommitResult.NotPersisted -> { warn(SNAPSHOT_NOT_PERSISTED); fetched }
        is SnapshotCommitResult.Conflict -> throw SSConfFailure(VpnErrorCode.PROFILE_CHANGED)
    }
}

/** Runs one Core call, mapping a native load failure to [VpnErrorCode.CORE_INCOMPATIBLE]. */
private suspend fun <R> coreCall(call: suspend () -> R): R = try {
    call()
} catch (error: LinkageError) {
    if (isNativeLoadFailure(error)) throw SSConfFailure(VpnErrorCode.CORE_INCOMPATIBLE)
    throw error
}

/** A body Core rejects with an [Exception] is null; a native load failure is still mapped by [coreCall]. */
private suspend fun <T> validateOrNull(validate: suspend (ByteArray) -> T, body: ByteArray): T? = coreCall {
    try {
        validate(body)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

/**
 * The service's snapshot step for one SSConf attempt: Core validates on IO, [store] discards and
 * commits, and [fetch] runs only when no persisted snapshot is usable. Holds no store guard or
 * attempt monitor across JNI or the network.
 */
internal suspend fun loadSessionSnapshot(
    store: ProfileStore,
    input: SessionInput,
    token: Long,
    fetch: suspend (source: String) -> ByteArray,
): SSConfSnapshot {
    val source = requireNotNull(input.ssconf).source
    return resolveSessionSnapshot(
        ticket = requireNotNull(input.ticket),
        stored = input.snapshot,
        ensureAttempt = { ensureSessionAttempt(token) },
        validate = { body -> withContext(Dispatchers.IO) { Androidcore.newSSConfSnapshot(source, body) } },
        fetch = { withContext(Dispatchers.IO) { fetch(source) } },
        discard = store::discardSnapshot,
        commit = store::commitSnapshot,
        warn = { code -> AppEvents.add(code, LogLevel.WARNING) },
    )
}
