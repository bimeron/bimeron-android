package org.bimeron.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VpnErrorCodeTest {
    private lateinit var app: Application

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        AppEvents.resources = app.resources
        AppEvents.clear()
    }

    @After fun reset() {
        VpnLive.resetAfterProcessStart()
        AppEvents.clear()
        AppEvents.resources = null
    }

    private fun journaled(code: VpnErrorCode) = app.getString(R.string.journal_vpn_failed_detail, code.name)

    @Test fun specificFailureSurvivesFallbackAndJournalsOnlyCode() {
        for (code in listOf(VpnErrorCode.NETWORK_ERROR, VpnErrorCode.SSH_CONNECTION_FAILED)) {
            val token = VpnLive.gate.next()
            VpnLive.publish(VpnState("connecting", attemptProfileId = "p"), token)
            VpnLive.publishFailure(code, "secret marker", "p", token)
            VpnLive.publishFailure(VpnErrorCode.CORE_CONNECT_FAILED, "generic", "p", token)
            assertEquals(code, VpnLive.state.value.code)
            val failures = AppEvents.entries.value.filter { it.level == LogLevel.ERROR }
            assertEquals(journaled(code), failures.first().message)
            assertFalse(AppEvents.entries.value.any { "secret marker" in it.message || "generic" in it.message })
            VpnLive.publishCoreStatus(token, "p", "connected", 0, 0, 10)
            assertEquals(code, VpnLive.state.value.code)
        }
    }

    @Test fun disconnectedCoreErrorSurvivesOuterFallback() {
        val token = VpnLive.gate.next()
        VpnLive.publishCoreStatus(token, "p", "disconnected", 0, 0, 0,
            "network", VpnErrorCode.NETWORK_ERROR)
        VpnLive.publishFailure(VpnErrorCode.CORE_CONNECT_FAILED, "generic", "p", token)
        assertEquals("failed", VpnLive.state.value.state)
        assertEquals(VpnErrorCode.NETWORK_ERROR, VpnLive.state.value.code)
        assertEquals("network", VpnLive.state.value.message)
        assertEquals(journaled(VpnErrorCode.NETWORK_ERROR), AppEvents.entries.value.first().message)
    }

    @Test fun resetAndNewAttemptClearCodeAndRejectStaleFailures() {
        val token = VpnLive.gate.next()
        VpnLive.publishFailure(VpnErrorCode.NETWORK_ERROR, "network", "p", token)
        VpnLive.resetAfterProcessStart()
        VpnLive.publishFailure(VpnErrorCode.START_FAILED, "late", "p", token)
        assertEquals(VpnState(), VpnLive.state.value)
        val next = VpnLive.gate.next()
        VpnLive.publish(VpnState("connecting"), next)
        assertNull(VpnLive.state.value.code)
        VpnLive.serviceDestroyed("stop")
        assertEquals(VpnErrorCode.STOP_FAILED, VpnLive.state.value.code)
        assertEquals(journaled(VpnErrorCode.STOP_FAILED), AppEvents.entries.value.first().message)
    }

    @Test fun failureWithoutCodeJournalsNoMessageText() {
        VpnLive.publish(VpnState("failed", message = "secret marker"), VpnLive.gate.next())
        assertEquals(app.getString(R.string.journal_vpn_failed), AppEvents.entries.value.first().message)
    }

    @Test fun coreErrorsAreClassifiedWithoutAuthGuesses() {
        assertEquals(VpnErrorCode.SSH_CONNECTION_FAILED, coreFailureCode("failed", "ssh_connection_failed"))
        assertEquals(VpnErrorCode.NETWORK_ERROR, coreFailureCode("failed", "Network failed secret"))
        assertEquals(VpnErrorCode.CORE_CONNECT_FAILED, coreFailureCode("failed", "secret"))
        assertEquals(VpnErrorCode.CORE_CONNECT_FAILED, coreFailureCode("disconnected", null))
        assertEquals(VpnErrorCode.CORE_CONNECT_FAILED, coreFailureCode("reconnecting", null))
        assertNull(coreFailureCode("connected", null))
    }

    @Test fun staleAndCancelledAttemptsCannotPassNativeGuard() = runBlocking {
        val stale = VpnLive.gate.next()
        val current = VpnLive.gate.next()
        try { ensureSessionAttempt(stale); fail("stale accepted") } catch (_: CancellationException) {}
        ensureSessionAttempt(current)
        var reachedNative = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            ensureSessionAttempt(current)
            reachedNative = true
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(reachedNative)
    }
}
