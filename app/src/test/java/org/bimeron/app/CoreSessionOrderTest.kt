package org.bimeron.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

/** The runSession creation order seam, [createCoreSession], and the published failure code. */
class CoreSessionOrderTest {
    private class Recorder {
        val log = mutableListOf<String>()
        var ownsNative = false
    }

    private suspend fun create(
        r: Recorder,
        ssconf: Boolean,
        snapshot: suspend () -> String = { "snapshot" },
        ensureAttempt: suspend () -> Unit = {},
        acquire: Boolean = true,
        canStart: Boolean = true,
    ): String = createCoreSession(
        snapshot = if (ssconf) suspend { r.log += "snapshot"; snapshot() } else null,
        ensureAttempt = { r.log += "ensure"; ensureAttempt() },
        acquireNative = { r.log += "acquire"; acquire.also { r.ownsNative = it } },
        canStartVpn = { r.log += "capabilities"; canStart },
        newSession = { r.log += "newSession"; "plain" },
        newSessionWithSnapshot = { r.log += "newSessionWithSnapshot($it)"; "ssconf" },
    )

    private suspend fun expectCode(code: VpnErrorCode, block: suspend () -> Unit) {
        try { block(); fail("Expected $code") } catch (e: Exception) {
            if (e is CancellationException) throw e
            assertEquals(code, sessionFailureCode(e))
        }
    }

    @Test fun ssconfResolvesSnapshotBeforeGateCapabilitiesAndSessionWithSnapshot() = runBlocking {
        val r = Recorder()
        assertEquals("ssconf", create(r, ssconf = true))
        assertEquals(listOf("snapshot", "ensure", "acquire", "capabilities", "ensure", "newSessionWithSnapshot(snapshot)"), r.log)
    }

    @Test fun nonSsconfNeverResolvesSnapshotAndUsesNewSession() = runBlocking {
        val r = Recorder()
        assertEquals("plain", create(r, ssconf = false))
        assertEquals(listOf("ensure", "acquire", "capabilities", "ensure", "newSession"), r.log)
    }

    @Test fun snapshotFailureTouchesNoNativeState() = runBlocking {
        for (code in listOf(VpnErrorCode.SSCONF_FETCH_FAILED, VpnErrorCode.SSCONF_NETWORK_UNAVAILABLE,
            VpnErrorCode.SSCONF_SNAPSHOT_INVALID, VpnErrorCode.PROFILE_CHANGED, VpnErrorCode.CORE_INCOMPATIBLE)) {
            val r = Recorder()
            expectCode(code) { create(r, ssconf = true, snapshot = { throw SSConfFailure(code) }) }
            assertEquals(listOf("snapshot"), r.log)
            assertFalse(r.ownsNative)
        }
    }

    @Test fun staleAttemptAfterSnapshotStopsBeforeNativeGate() = runBlocking {
        val r = Recorder()
        try {
            create(r, ssconf = true, ensureAttempt = { throw CancellationException("Stale VPN attempt") })
            fail("stale attempt continued")
        } catch (_: CancellationException) {}
        assertEquals(listOf("snapshot", "ensure"), r.log)
        assertFalse(r.ownsNative)
    }

    @Test fun blockedCleanupGateFailsBeforeCapabilities() = runBlocking {
        val r = Recorder()
        expectCode(VpnErrorCode.CLEANUP_BLOCKED) { create(r, ssconf = true, acquire = false) }
        assertEquals(listOf("snapshot", "ensure", "acquire"), r.log)
        assertFalse(r.ownsNative)
    }

    @Test fun incompatibleCoreFailsAfterGateWithoutSession() = runBlocking {
        val r = Recorder()
        expectCode(VpnErrorCode.CORE_INCOMPATIBLE) { create(r, ssconf = true, canStart = false) }
        assertEquals(listOf("snapshot", "ensure", "acquire", "capabilities"), r.log)
        // The gate was acquired, so the service's finally block must complete it.
        assertEquals(true, r.ownsNative)
    }

    @Test fun failureCodesAreFixed() {
        assertEquals(VpnErrorCode.TUN_FAILED, sessionFailureCode(BimeronVpnService.SessionFailure(VpnErrorCode.TUN_FAILED)))
        assertEquals(VpnErrorCode.PROFILE_CHANGED, sessionFailureCode(SSConfFailure(VpnErrorCode.PROFILE_CHANGED)))
        assertEquals(VpnErrorCode.START_FAILED, sessionFailureCode(IllegalStateException("ssconf://secret")))
    }
}
