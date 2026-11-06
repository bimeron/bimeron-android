package org.bimeron.app

import org.bimeron.core.androidcore.SSConfSocketProtector

/** A lease backed by the real [SSConfLeaseState], counting invalidation registrations. */
internal class FakeFetchLease(
    override val networkID: String = "synthetic-network",
    override val dns: String = "192.0.2.53",
) : SSConfFetchLease {
    val state = SSConfLeaseState()
    val protected = mutableListOf<Long>()
    val bound = mutableListOf<Long>()
    var registrations = 0
        private set
    var openRegistrations = 0
        private set

    override fun isValid(): Boolean = state.isValid()
    override fun onInvalidated(cancel: () -> Unit): AutoCloseable {
        registrations++
        openRegistrations++
        val registration = state.onInvalidated(cancel)
        var closed = false
        return AutoCloseable {
            if (!closed) { closed = true; openRegistrations--; registration.close() }
        }
    }
    override fun protect(fd: Long): Boolean { protected += fd; return true }
    override fun bind(fd: Long): Boolean { bound += fd; return true }

    /** A lease scope that runs the block directly against this lease. */
    val scope: SSConfLeaseScope = { block -> block(this) }
}

/** A request whose [resolve] runs [onResolve]; records cancellation. */
internal class FakeFetchRequest(private val onResolve: (FakeFetchRequest) -> ByteArray) : SSConfFetchRequest {
    var cancelled = 0
        private set
    var resolves = 0
        private set
    override fun resolve(): ByteArray { resolves++; return onResolve(this) }
    override fun cancel() { cancelled++ }
}

/** A factory that records its arguments and returns [request]. */
internal class FakeRequestFactory(private val create: () -> SSConfFetchRequest) : SSConfRequestFactory {
    val calls = mutableListOf<List<String>>()
    var protector: SSConfSocketProtector? = null
        private set
    override fun invoke(source: String, networkID: String, dns: String, protector: SSConfSocketProtector): SSConfFetchRequest {
        calls += listOf(source, networkID, dns)
        this.protector = protector
        return create()
    }
}
