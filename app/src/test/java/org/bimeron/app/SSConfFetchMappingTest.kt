package org.bimeron.app

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Error mapping, registration lifetime and cancellation of [fetchSSConf] through its injectable seams. */
class SSConfFetchMappingTest {
    private val source = "ssconf://secret-host.example/private-path"
    private val body = "ss://YWVzLTEyOC1nY206cGFzcw@example.com:443".toByteArray()

    private suspend fun expectFailure(code: VpnErrorCode, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected $code")
        } catch (e: SSConfFailure) {
            assertEquals(code, e.code)
            assertNull(e.cause)
            assertEquals(code.name, e.message)
            assertFalse(e.toString().contains("secret-host"))
        }
    }

    @Test fun successPassesLeaseValuesAndDelegatingProtector() = runBlocking {
        val lease = FakeFetchLease()
        val request = FakeFetchRequest { body }
        val factory = FakeRequestFactory { request }
        assertTrue(body.contentEquals(fetchSSConf(source, lease.scope, factory)))
        assertEquals(listOf(listOf(source, "synthetic-network", "192.0.2.53")), factory.calls)
        val protector = checkNotNull(factory.protector)
        assertTrue(protector.protect(7))
        assertTrue(protector.bind(8))
        assertEquals(listOf(7L), lease.protected)
        assertEquals(listOf(8L), lease.bound)
        assertEquals(1, request.resolves)
        assertEquals(0, request.cancelled)
        assertEquals(1, lease.registrations)
        assertEquals(0, lease.openRegistrations)
    }

    @Test fun resolveFailureWithValidLeaseIsFetchFailed() = runBlocking {
        val lease = FakeFetchLease()
        val request = FakeFetchRequest { throw IOException("resolve failed for $source") }
        expectFailure(VpnErrorCode.SSCONF_FETCH_FAILED) { fetchSSConf(source, lease.scope, FakeRequestFactory { request }) }
        assertEquals(0, request.cancelled)
        assertEquals(1, lease.registrations)
        assertEquals(0, lease.openRegistrations)
    }

    @Test fun resolveFailureWithInvalidatedLeaseIsNetworkUnavailable() = runBlocking {
        val lease = FakeFetchLease()
        val request = FakeFetchRequest {
            // Environmental loss during the blocking resolve: the registration cancels the request.
            lease.state.invalidate(SSConfDiagnostic.NETWORK_LOST)
            throw IOException("cancelled $source")
        }
        expectFailure(VpnErrorCode.SSCONF_NETWORK_UNAVAILABLE) {
            fetchSSConf(source, lease.scope, FakeRequestFactory { request })
        }
        assertEquals(1, request.cancelled)
        assertEquals(1, lease.registrations)
        assertEquals(0, lease.openRegistrations)
    }

    @Test fun requestCreationFailureWithValidLeaseIsFetchFailedWithoutRegistration() = runBlocking {
        val lease = FakeFetchLease()
        expectFailure(VpnErrorCode.SSCONF_FETCH_FAILED) {
            fetchSSConf(source, lease.scope, FakeRequestFactory { throw IllegalArgumentException("bad $source") })
        }
        assertEquals(0, lease.registrations)
    }

    @Test fun requestCreationFailureWithInvalidatedLeaseIsNetworkUnavailable() = runBlocking {
        val lease = FakeFetchLease()
        expectFailure(VpnErrorCode.SSCONF_NETWORK_UNAVAILABLE) {
            fetchSSConf(source, lease.scope, FakeRequestFactory {
                lease.state.invalidate(SSConfDiagnostic.SERVICE_REVOKED)
                throw IllegalStateException("network gone for $source")
            })
        }
        assertEquals(0, lease.registrations)
    }

    @Test fun leaseAcquisitionFailuresKeepFixedCodes() = runBlocking {
        val factory = FakeRequestFactory { throw AssertionError("request created without a lease") }
        expectFailure(VpnErrorCode.SSCONF_NETWORK_UNAVAILABLE) {
            fetchSSConf(source, { throw SSConfNetworkUnavailableException("state=INVALIDATED $source") }, factory)
        }
        expectFailure(VpnErrorCode.SSCONF_FETCH_FAILED) {
            fetchSSConf(source, { throw IllegalStateException("unexpected $source") }, factory)
        }
        assertTrue(factory.calls.isEmpty())
    }

    @Test fun cancellationWinsOverFailure() = runBlocking {
        for (invalidate in listOf(false, true)) {
            val lease = FakeFetchLease()
            var scopeJob: Job? = null
            var outcome: Throwable? = null
            val request = FakeFetchRequest {
                scopeJob!!.cancel()
                if (invalidate) lease.state.invalidate(SSConfDiagnostic.CANCELLATION)
                throw IOException("resolve failed for $source")
            }
            val worker = launch {
                try {
                    fetchSSConf(source, { block ->
                        scopeJob = currentCoroutineContext()[Job]
                        block(lease)
                    }, FakeRequestFactory { request })
                } catch (t: Throwable) {
                    outcome = t
                    throw t
                }
            }
            worker.join()
            assertTrue(worker.isCancelled)
            assertTrue("cancellation must win, got $outcome", outcome is CancellationException)
            assertFalse(outcome is SSConfFailure)
            assertEquals(0, lease.openRegistrations)
        }
    }

    @Test fun cancellationFromScopeIsRethrownAsIs() = runBlocking {
        val cancelled = CancellationException("scope cancelled")
        try {
            fetchSSConf(source, { throw cancelled }, FakeRequestFactory { throw AssertionError("created") })
            fail("Expected cancellation")
        } catch (e: CancellationException) {
            assertSame(cancelled, e)
        }
    }

    @Test fun invalidationRegistrationIsClosedOnEveryPath() = runBlocking {
        val paths = listOf<Pair<String, (FakeFetchLease) -> (FakeFetchRequest) -> ByteArray>>(
            "success" to { _ -> { body } },
            "resolve failure" to { _ -> { throw IOException("x") } },
            "invalidated" to { lease -> { lease.state.invalidate(); throw IOException("x") } },
            "runtime exception" to { _ -> { throw IllegalStateException("x") } },
            "native error" to { _ -> { throw UnsatisfiedLinkError("x") } },
        )
        for ((name, behavior) in paths) {
            val lease = FakeFetchLease()
            val request = FakeFetchRequest(behavior(lease))
            runCatching { fetchSSConf(source, lease.scope, FakeRequestFactory { request }) }
            assertEquals(name, 1, lease.registrations)
            assertEquals(name, 0, lease.openRegistrations)
        }
        // Cancellation path.
        val lease = FakeFetchLease()
        var scopeJob: Job? = null
        val worker = launch {
            fetchSSConf(source, { block -> scopeJob = currentCoroutineContext()[Job]; block(lease) },
                FakeRequestFactory { FakeFetchRequest { scopeJob!!.cancel(); throw IOException("x") } })
        }
        worker.join()
        assertNotNull(scopeJob)
        assertEquals(1, lease.registrations)
        assertEquals(0, lease.openRegistrations)
    }

    @Test fun nativeLoadFailureEscapesFetchAndBecomesCoreIncompatibleInSnapshotStep() = runBlocking {
        val errors = listOf(UnsatisfiedLinkError("gojni"), NoSuchMethodError("newSSConfRequest"),
            NoClassDefFoundError("Androidcore").apply { initCause(ExceptionInInitializerError(UnsatisfiedLinkError("gojni"))) })
        for (error in errors) {
            val lease = FakeFetchLease()
            expectFailure(VpnErrorCode.CORE_INCOMPATIBLE) {
                resolveSessionSnapshot(
                    ticket = SnapshotTicket(java.util.UUID.randomUUID(), source, 1) { true },
                    stored = null, recovery = false, ensureAttempt = {}, validate = { it.size },
                    fetch = { fetchSSConf(source, lease.scope, FakeRequestFactory { throw error }) },
                    discard = { _, _ -> fail("discarded") }, commit = { _, _ -> throw AssertionError("committed") },
                    warn = { fail("warned") }, onFallback = { fail("fell back") },
                )
            }
            assertEquals(0, lease.openRegistrations)
        }
    }
}
