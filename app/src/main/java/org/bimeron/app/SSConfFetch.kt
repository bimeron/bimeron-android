package org.bimeron.app

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.bimeron.core.androidcore.Androidcore
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

/**
 * Sole consumer of [SSConfNetworkLease] / [withSSConfNetwork]. Bind-only; never starts a VPN.
 * Must be called off Main. Performs exactly one request with no retry and returns the raw body;
 * the caller validates it through Core before use.
 *
 * Failures map only to [VpnErrorCode.SSCONF_NETWORK_UNAVAILABLE] or [VpnErrorCode.SSCONF_FETCH_FAILED].
 * Coroutine cancellation is rethrown as is.
 */
internal suspend fun fetchSSConf(context: Context, source: String): ByteArray = try {
    withSSConfNetwork(context) { lease ->
        val request = try {
            Androidcore.newSSConfRequest(source, lease.networkID, lease.dns, lease.protector())
        } catch (_: Exception) {
            throw SSConfFailure(VpnErrorCode.SSCONF_FETCH_FAILED)
        }
        lease.onInvalidated { request.cancel() }.use {
            try {
                request.resolve().content()
            } catch (_: Exception) {
                // A resolve cancelled by lease invalidation is a network loss, not a fetch error.
                throw SSConfFailure(
                    if (lease.isValid()) VpnErrorCode.SSCONF_FETCH_FAILED else VpnErrorCode.SSCONF_NETWORK_UNAVAILABLE,
                )
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

private fun SSConfNetworkLease.protector() = object : SSConfSocketProtector {
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
 * [CancellationException] for a stale attempt. [validate] throws when Core rejects a body.
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
    val body = fetch()
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

private suspend fun <T> validateOrNull(validate: suspend (ByteArray) -> T, body: ByteArray): T? = try {
    validate(body)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
