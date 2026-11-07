package org.bimeron.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit M-1: when an ended attempt gets the "VPN disconnected" notice ([shouldNotifyDrop], [noteSessionFinished]). */
class DropNoticeDecisionTest {
    @After fun reset() { VpnLive.resetAfterProcessStart() }

    private val userEnded = setOf(VpnErrorCode.AUTH_CANCELLED, VpnErrorCode.RECOVERY_NEEDS_USER)
    /** Every code an attempt can end with that the user did not choose, plus a plain disconnect (null). */
    private val nonUserCodes: List<VpnErrorCode?> = VpnErrorCode.entries.filter { it !in userEnded } + null

    private class FakeDropNotice : DropNotice {
        val effects = mutableListOf<String>()
        override fun post() { effects += "post" }
        override fun cancel() { effects += "cancel" }
    }

    private object NoRecoveryPort : RecoveryStartPort {
        override suspend fun read(id: String, token: Long): SessionInput? = null
        override suspend fun clearRecovery(): Boolean = true
        override val destroyed = false
        override fun postNotification() {}
        override fun cancelNotification() {}
        override fun recordSkip(message: Int) {}
        override fun publishSkipped(token: Long) {}
    }

    // Pure decision.

    @Test fun nonUserEndAfterConnectedNotifiesForEveryCode() {
        nonUserCodes.forEach { code ->
            assertTrue("$code", shouldNotifyDrop(code, userInitiated = false, wasConnected = true, recovery = false))
            assertTrue("$code recovery", shouldNotifyDrop(code, userInitiated = false, wasConnected = true, recovery = true))
        }
    }

    @Test fun userInitiatedEndNeverNotifies() {
        (nonUserCodes + userEnded).forEach { code ->
            for (connected in listOf(true, false)) for (recovery in listOf(true, false)) {
                assertFalse("$code $connected $recovery", shouldNotifyDrop(code, userInitiated = true, connected, recovery))
            }
        }
    }

    @Test fun userSshCancelNeverNotifies() {
        for (connected in listOf(true, false)) for (recovery in listOf(true, false)) {
            assertFalse(shouldNotifyDrop(VpnErrorCode.AUTH_CANCELLED, userInitiated = false, connected, recovery))
        }
    }

    /** The recovery skip already posts "Tap to reconnect"; a second notice would duplicate it. */
    @Test fun recoverySkipNeverNotifies() {
        for (connected in listOf(true, false)) for (recovery in listOf(true, false)) {
            assertFalse(shouldNotifyDrop(VpnErrorCode.RECOVERY_NEEDS_USER, userInitiated = false, connected, recovery))
        }
    }

    /** A user connect that fails before `connected` is shown in the app the user is looking at. */
    @Test fun userStartFailingBeforeConnectedDoesNotNotify() {
        nonUserCodes.forEach { code ->
            assertFalse("$code", shouldNotifyDrop(code, userInitiated = false, wasConnected = false, recovery = false))
        }
    }

    /** A sticky restart or always-on start runs unattended: its failure is announced even before `connected`. */
    @Test fun unattendedRecoveryFailingBeforeConnectedNotifies() {
        nonUserCodes.forEach { code ->
            assertTrue("$code", shouldNotifyDrop(code, userInitiated = false, wasConnected = false, recovery = true))
        }
    }

    // Wiring through the attempt gate and the published state, as the service's Command.Finished uses it.

    private val id = "00000000-0000-0000-0000-000000000001"

    /** Starts attempt [id] and drives it to `connected` the way the status loop does. */
    private fun connectedAttempt(): Long {
        val token = VpnLive.nextAttempt()
        VpnLive.publish(VpnState("connecting", attemptProfileId = id), token)
        assertEquals("connected", VpnLive.publishCoreStatus(token, id, "connected", 1, 1, 100))
        return token
    }

    private fun finish(token: Long, notice: DropNotice, connected: Boolean = true, recovery: Boolean = false, stopPending: Boolean = false) =
        noteSessionFinished(token, VpnLive.state.value.code, connected, recovery, stopPending, notice)

    @Test fun packetFailureAfterConnectedPostsOnce() {
        val notice = FakeDropNotice()
        val token = connectedAttempt()
        VpnLive.publishFailure(VpnErrorCode.PACKET_IO, "fixed", id, token)
        assertTrue(finish(token, notice))
        assertEquals(listOf("post"), notice.effects)
    }

    @Test fun coreGivingUpWhileReconnectingPosts() {
        val notice = FakeDropNotice()
        val token = connectedAttempt()
        assertEquals("reconnecting", VpnLive.publishCoreStatus(token, id, "reconnecting", 2, 2, 200))
        assertEquals("failed", VpnLive.publishCoreStatus(token, id, "failed", 2, 2, 300, "fixed", VpnErrorCode.CORE_CONNECT_FAILED))
        assertTrue(finish(token, notice))
        assertEquals(listOf("post"), notice.effects)
    }

    /** Command.Stop (and onRevoke, which sends it) advances the gate before the old attempt's Finished arrives. */
    @Test fun userStopDoesNotPost() {
        val notice = FakeDropNotice()
        val token = connectedAttempt()
        VpnLive.nextAttempt()
        assertFalse(finish(token, notice))
        assertTrue(notice.effects.isEmpty())
    }

    /**
     * S-1: onRevoke or an in-app STOP whose Command.Stop is still queued behind this attempt's Command.Finished.
     * The system teardown made the session fail first, so the token is still current; the stop is the user's.
     */
    @Test fun failureRacingAQueuedUserStopDoesNotPost() {
        val notice = FakeDropNotice()
        val token = connectedAttempt()
        VpnLive.publishFailure(VpnErrorCode.PACKET_IO, "fixed", id, token)
        assertTrue(VpnLive.gate.accepts(token))
        assertFalse(finish(token, notice, stopPending = true))
        assertFalse(finish(token, notice, connected = false, recovery = true, stopPending = true))
        assertTrue(notice.effects.isEmpty())
    }

    /** A recovery start that cannot even run its session (cleanup blocked, recovery id not saved) is announced. */
    @Test fun recoveryFailingBeforeItsSessionRunsPosts() {
        for (code in listOf(VpnErrorCode.CLEANUP_BLOCKED, VpnErrorCode.RECOVERY_STATE_FAILED)) {
            val notice = FakeDropNotice()
            val token = VpnLive.nextAttempt()
            VpnLive.publish(failureState(code, { "fixed" }, id), token)
            assertTrue("$code", noteSessionFinished(token, code, wasConnected = false, recovery = true, stopPending = false, notice))
            assertFalse("$code user", noteSessionFinished(token, code, wasConnected = false, recovery = false, stopPending = false, notice))
            assertEquals(listOf("post"), notice.effects)
        }
    }

    @Test fun supersededByNewStartDoesNotPost() {
        val notice = FakeDropNotice()
        val token = connectedAttempt()
        val next = VpnLive.nextAttempt()
        VpnLive.publish(VpnState("connecting", attemptProfileId = id), next)
        assertFalse(finish(token, notice))
        assertTrue(notice.effects.isEmpty())
    }

    @Test fun serviceDestroyedDoesNotPost() {
        val notice = FakeDropNotice()
        val token = connectedAttempt()
        VpnLive.serviceDestroyed("fixed")
        assertFalse(finish(token, notice))
        assertTrue(notice.effects.isEmpty())
    }

    /** §5.3: a cancel during `reconnecting` ends the attempt with AUTH_CANCELLED under its own token. */
    @Test fun userSshCancelAfterConnectedDoesNotPost() {
        val notice = FakeDropNotice()
        val token = connectedAttempt()
        assertEquals("reconnecting", VpnLive.publishCoreStatus(token, id, "reconnecting", 2, 2, 200))
        stopAfterUserCancel(id, token, "fixed", owner = null)
        assertEquals(VpnErrorCode.AUTH_CANCELLED, VpnLive.state.value.code)
        assertFalse(finish(token, notice))
        assertTrue(notice.effects.isEmpty())
    }

    @Test fun userConnectFailingBeforeConnectedDoesNotPost() {
        val notice = FakeDropNotice()
        val token = VpnLive.nextAttempt()
        VpnLive.publish(VpnState("connecting", attemptProfileId = id), token)
        VpnLive.publishFailure(VpnErrorCode.CORE_CONNECT_FAILED, "fixed", id, token)
        assertFalse(finish(token, notice, connected = false, recovery = false))
        assertTrue(notice.effects.isEmpty())
    }

    @Test fun nextUserStartCancelsTheNoticeAndARecoveryStartDoesNot() {
        val notice = FakeDropNotice()
        val token = connectedAttempt()
        VpnLive.publishFailure(VpnErrorCode.NETWORK_ERROR, "fixed", id, token)
        assertTrue(finish(token, notice))
        noteStartCommand(recovery = true, NoRecoveryPort, notice)
        assertEquals(listOf("post"), notice.effects)
        noteStartCommand(recovery = false, NoRecoveryPort, notice)
        assertEquals(listOf("post", "cancel"), notice.effects)
    }
}
