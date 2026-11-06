package org.bimeron.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contract §13.2 SSConfFetchTest. Real [fetchSSConf] (bind, lease capture, native request) against
 * sources with no server, so only failure paths are reachable without a test server. Run only on an
 * isolated test device; uses [SSConfConsentFixture] and never starts a VPN, Session or TUN.
 */
@RunWith(AndroidJUnit4::class)
class SSConfFetchTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    /** Loopback discard port: no listener, so the bounded request fails without leaving the device. */
    private val refused = "ssconf://127.0.0.1:9/bimeron-fetch-test-secret"
    /** TEST-NET-1 (RFC 5737): never contacted, because the fetch is cancelled during lease acquisition. */
    private val blackhole = "ssconf://192.0.2.1:443/bimeron-fetch-test-secret"

    /** Runs [block] and asserts it leaves VPN state, protection, the attempt token and the journal untouched. */
    private fun preservingGlobals(block: suspend CoroutineScope.() -> Unit) {
        val token = VpnLive.gate.next()
        val state = VpnLive.state.value
        val protection = VpnLive.protection.value
        val events = AppEvents.entries.value
        assertEquals("No other lease may be open", 0, SSConfNetworkLease.observerCount)
        runBlocking(Dispatchers.Default) { withTimeout(40_000) { block() } }
        assertEquals(0, SSConfNetworkLease.observerCount)
        assertTrue("Fetch must not advance the attempt token", VpnLive.gate.accepts(token))
        assertEquals(state, VpnLive.state.value)
        assertEquals(protection, VpnLive.protection.value)
        assertEquals(events, AppEvents.entries.value)
    }

    private suspend fun expectFailure(source: String): SSConfFailure = try {
        fetchSSConf(context, source)
        throw AssertionError("Unexpected SSConf body")
    } catch (failure: SSConfFailure) {
        assertNull(failure.cause)
        assertEquals(failure.code.name, failure.message)
        assertFalse(failure.toString().contains("bimeron-fetch-test-secret"))
        failure
    }

    @Test fun preparedFetchIsBindOnlyAndReleasesObserver() = SSConfConsentFixture.withConsent(context, true) {
        preservingGlobals {
            val failure = expectFailure(refused)
            // FETCH_FAILED when a physical network was leased; NETWORK_UNAVAILABLE when none is available.
            assertTrue(failure.code.name, failure.code in setOf(
                VpnErrorCode.SSCONF_FETCH_FAILED, VpnErrorCode.SSCONF_NETWORK_UNAVAILABLE))
        }
    }

    @Test fun unpreparedFetchIsNetworkUnavailableWithoutObserver() = SSConfConsentFixture.withConsent(context, false) {
        preservingGlobals {
            assertEquals(VpnErrorCode.SSCONF_NETWORK_UNAVAILABLE, expectFailure(refused).code)
        }
    }

    /**
     * Cancels while the lease is still being acquired (observer registered, network snapshot settling),
     * so no request is created. Requires a physical network; only cancellation is an accepted outcome.
     */
    @Test fun cancelDuringAcquisitionReleasesObserver() = SSConfConsentFixture.withConsent(context, true) {
        preservingGlobals {
            val outcome = AtomicReference<Throwable?>()
            val worker = launch {
                try { fetchSSConf(context, blackhole); fail("Unexpected SSConf body") }
                catch (t: Throwable) { outcome.set(t); throw t }
            }
            // Bounded wait for the condition; a worker that finishes first fails the test below.
            val registered = withTimeoutOrNull(5_000) {
                while (SSConfNetworkLease.observerCount == 0) {
                    if (!worker.isActive) return@withTimeoutOrNull false
                    delay(1)
                }
                true
            }
            worker.cancelAndJoin()
            val result = outcome.get()
            assertEquals("Observer must be registered before cancellation (outcome: $result)", true, registered)
            assertTrue("Cancellation must win: $result", result is CancellationException)
            assertFalse("Must not be a timeout: $result", result is TimeoutCancellationException)
        }
    }
}
