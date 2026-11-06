package org.bimeron.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bimeron.app.AuthTestSupport.PROFILE
import org.bimeron.app.AuthTestSupport.REQUEST_A
import org.bimeron.app.AuthTestSupport.REQUEST_B
import org.bimeron.app.AuthTestSupport.SESSION
import org.bimeron.app.AuthTestSupport.attempt
import org.bimeron.app.AuthTestSupport.wire
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthPollingTest {
    @After fun reset() = VpnLive.resetAfterProcessStart()

    private fun authAttempt(token: Long, port: FakeCorePort, open: Boolean = true, onCancelled: () -> Unit = {}) =
        AuthAttempt(token, port, { open && VpnLive.gate.accepts(token) }, onCancelled)

    @Test fun sameRequestIsPublishedOnce() {
        val token = attempt()
        val port = FakeCorePort(wire(REQUEST_A))
        val auth = authAttempt(token, port)
        auth.poll()
        val first = VpnLive.auth.value
        assertEquals(AuthOffer(token, SESSION, REQUEST_A, wire(REQUEST_A)), first)
        auth.poll()
        assertSame(first, VpnLive.auth.value)
        assertEquals(AuthHistory.OFFERED, auth.history)
        port.pending = wire(REQUEST_B)
        auth.poll()
        assertNotSame(first, VpnLive.auth.value)
        assertEquals(REQUEST_B, VpnLive.auth.value?.requestId)
    }

    @Test fun unansweredOfferThatDisappearsIsWithdrawn() {
        // Covers Core timeout, round limit and SetNetwork withdrawal: the slot simply empties.
        val token = attempt()
        val port = FakeCorePort(wire(REQUEST_A))
        val auth = authAttempt(token, port)
        auth.poll()
        port.pending = "null"
        auth.poll()
        assertNull(VpnLive.auth.value)
        assertEquals(AuthHistory.WITHDRAWN, auth.history)
        assertEquals(VpnErrorCode.AUTH_UNANSWERED, coreFailureCode("failed", "ssh_connection_failed", auth.history))
    }

    @Test fun replacedUnansweredOfferIsWithdrawnThenOffered() {
        val token = attempt()
        val port = FakeCorePort(wire(REQUEST_A))
        val auth = authAttempt(token, port)
        auth.poll()
        port.pending = wire(REQUEST_B)
        auth.poll()
        assertEquals(AuthHistory.OFFERED, auth.history)
        port.pending = "null"
        auth.poll()
        assertEquals(AuthHistory.WITHDRAWN, auth.history)
    }

    @Test fun malformedPendingAuthIsNeverPublished() {
        val token = attempt()
        listOf("", "{", "[]", "{\"sessionId\":\"x\"}", wire("short"), wire(REQUEST_A).replace("password", "otp"), null)
            .forEach { bad ->
                val auth = authAttempt(token, FakeCorePort(bad))
                auth.poll()
                assertNull(VpnLive.auth.value)
                assertEquals(AuthHistory.NONE, auth.history)
            }
        // Malformed after a valid offer withdraws it.
        val port = FakeCorePort(wire(REQUEST_A))
        val auth = authAttempt(token, port)
        auth.poll()
        port.pending = "{garbage"
        auth.poll()
        assertNull(VpnLive.auth.value)
        assertEquals(AuthHistory.WITHDRAWN, auth.history)
    }

    @Test fun replyThenSshFailureIsAuthFailed() = runBlocking {
        val token = attempt()
        val port = FakeCorePort(wire(REQUEST_A))
        val auth = authAttempt(token, port)
        VpnAuthEndpoint.register(token, auth)
        auth.poll()
        assertEquals(AuthResult.ACCEPTED, VpnAuthEndpoint.reply(token, SESSION, REQUEST_A, "[\"pw\"]"))
        assertEquals(AuthHistory.REPLIED, auth.history)
        auth.poll()
        assertNull(VpnLive.auth.value)
        // An answered offer leaving the slot is not a withdrawal.
        assertEquals(AuthHistory.REPLIED, auth.history)
        assertEquals(VpnErrorCode.AUTH_FAILED, coreFailureCode("failed", "ssh_connection_failed", auth.history))
        // A second reply to the consumed request is stale.
        assertEquals(AuthResult.STALE, VpnAuthEndpoint.reply(token, SESSION, REQUEST_A, "[\"pw\"]"))
    }

    @Test fun connectedResetsHistoryForLaterReconnects() = runBlocking {
        val token = attempt()
        val port = FakeCorePort(wire(REQUEST_A))
        val auth = authAttempt(token, port)
        VpnAuthEndpoint.register(token, auth)
        auth.poll()
        assertEquals(AuthResult.ACCEPTED, VpnAuthEndpoint.reply(token, SESSION, REQUEST_A, "[\"pw\"]"))
        auth.poll()
        auth.connected()
        // A later reconnect that fails without any prompt is a plain SSH failure, not AUTH_FAILED.
        assertEquals(VpnErrorCode.SSH_CONNECTION_FAILED, coreFailureCode("failed", "ssh_connection_failed", auth.history))
    }

    @Test fun offerVisibleAtConnectIsNotWithdrawnLater() {
        val token = attempt()
        val port = FakeCorePort(wire(REQUEST_A))
        val auth = authAttempt(token, port)
        auth.poll()
        auth.connected()
        port.pending = "null"
        auth.poll()
        assertEquals(AuthHistory.NONE, auth.history)
        assertNull(VpnLive.auth.value)
    }

    @Test fun rejectedCoreReplyKeepsOffered() = runBlocking {
        val token = attempt()
        val port = FakeCorePort(wire(REQUEST_A)).apply { failWith = Exception("expired") }
        val auth = authAttempt(token, port)
        VpnAuthEndpoint.register(token, auth)
        auth.poll()
        assertEquals(AuthResult.REJECTED, VpnAuthEndpoint.reply(token, SESSION, REQUEST_A, "[\"pw\"]"))
        assertEquals(AuthHistory.OFFERED, auth.history)
    }

    @Test fun closedAttemptIsUnavailable() = runBlocking {
        val token = attempt()
        val port = FakeCorePort(wire(REQUEST_A))
        val auth = authAttempt(token, port, open = false)
        VpnAuthEndpoint.register(token, auth)
        auth.poll()
        assertEquals(AuthResult.UNAVAILABLE, VpnAuthEndpoint.reply(token, SESSION, REQUEST_A, "[]"))
        assertTrue(port.calls.isEmpty())
    }

    /**
     * Stands in for runSession: the run job whose cancellation takes the finally path (auth unregistered
     * and withdrawn) and then sends Command.Finished([token]); [finished] records that send.
     */
    // UNDISPATCHED: the body (and its finally) is entered before launch returns, so an early cancel cannot skip it.
    private fun CoroutineScope.runJob(token: Long, finished: MutableList<Long>) = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            VpnAuthEndpoint.unregister(token)
            VpnLive.publishAuth(token, null)
            finished += token
        }
    }

    /**
     * After a user cancel the attempt ends through Command.Finished, which clears recovery and does not
     * reconnect: Finished is accepted only while the gate still accepts the token, and a teardown that
     * cleans up and clears recovery keeps the AUTH_CANCELLED failure instead of publishing anything else.
     */
    private suspend fun assertStoppedByUser(token: Long, auth: AuthAttempt, owner: Job, finished: List<Long>) {
        owner.join()
        assertTrue(owner.isCancelled)
        assertEquals(listOf(token), finished)
        assertTrue("Command.Finished for this token is processed", VpnLive.gate.accepts(token))
        assertNull(teardownFailureCode(cleanupBlocked = false, recoveryCleared = true, prior = VpnLive.state.value.code))
        assertEquals("failed", VpnLive.state.value.state)
        assertEquals(VpnErrorCode.AUTH_CANCELLED, VpnLive.state.value.code)
        assertNull(VpnLive.auth.value)
        assertEquals(AuthResult.STALE, VpnAuthEndpoint.cancel(token, SESSION, REQUEST_A))
        // No reconnect: later Core ticks (reconnecting, connected, SSH failure) cannot leave failed or change the code.
        auth.poll()
        assertEquals(AuthHistory.CANCELLED_BY_USER, auth.history)
        listOf("reconnecting", "connected", "failed").forEach { core ->
            assertEquals(core, "failed", VpnLive.publishCoreStatus(token, PROFILE, core, 0, 0, 0, null,
                coreFailureCode(core, "ssh_connection_failed", auth.history)))
        }
        assertEquals(VpnErrorCode.AUTH_CANCELLED, VpnLive.state.value.code)
    }

    private fun cancelStops(state: String) = runBlocking {
        val token = attempt(state)
        val finished = mutableListOf<Long>()
        val owner = runJob(token, finished)
        val port = FakeCorePort(wire(REQUEST_A))
        val auth = authAttempt(token, port) { stopAfterUserCancel(PROFILE, token, "cancelled", owner) }
        VpnAuthEndpoint.register(token, auth)
        auth.poll()
        assertEquals(AuthResult.ACCEPTED, VpnAuthEndpoint.cancel(token, SESSION, REQUEST_A))
        assertEquals(listOf("cancel:$REQUEST_A"), port.calls)
        assertStoppedByUser(token, auth, owner, finished)
    }

    @Test fun cancelWhileConnectingStops() = cancelStops("connecting")
    @Test fun cancelWhileReconnectingStops() = cancelStops("reconnecting")
    @Test fun cancelWhileConnectedStops() = cancelStops("connected")

    private fun coreRejectedCancelStillStops(state: String) = runBlocking {
        val token = attempt(state)
        val finished = mutableListOf<Long>()
        val owner = runJob(token, finished)
        // Core already withdrew the request and rejects the cancel.
        val port = FakeCorePort(wire(REQUEST_A)).apply { failWith = IllegalStateException("no such request") }
        val auth = authAttempt(token, port) { stopAfterUserCancel(PROFILE, token, "cancelled", owner) }
        VpnAuthEndpoint.register(token, auth)
        auth.poll()
        assertEquals(AuthResult.REJECTED, VpnAuthEndpoint.cancel(token, SESSION, REQUEST_A))
        assertStoppedByUser(token, auth, owner, finished)
    }

    @Test fun coreRejectedCancelStopsWhileConnecting() = coreRejectedCancelStillStops("connecting")
    @Test fun coreRejectedCancelStopsWhileReconnecting() = coreRejectedCancelStillStops("reconnecting")
    @Test fun coreRejectedCancelStopsWhileConnected() = coreRejectedCancelStillStops("connected")

    @Test fun tickDuringCancelDerivesAuthCancelled() = runBlocking {
        val token = attempt()
        val owner = Job()
        val port = FakeCorePort(wire(REQUEST_A))
        val auth = authAttempt(token, port) { stopAfterUserCancel(PROFILE, token, "cancelled", owner) }
        VpnAuthEndpoint.register(token, auth)
        auth.poll()
        // The tick reads the history while Core is processing the cancel: it is already recorded.
        port.duringCancel = {
            VpnLive.publishCoreStatus(token, PROFILE, "failed", 0, 0, 0, "x",
                coreFailureCode("failed", "ssh_connection_failed", auth.history))
        }
        assertEquals(AuthResult.ACCEPTED, VpnAuthEndpoint.cancel(token, SESSION, REQUEST_A))
        assertEquals(VpnErrorCode.AUTH_CANCELLED, VpnLive.state.value.code)
        assertTrue(owner.isCancelled)
    }

    @Test fun tickPublishedBeforeCancelRecordedIsOverriddenByCancel() = runBlocking {
        val token = attempt()
        val owner = Job()
        val port = FakeCorePort(wire(REQUEST_A))
        val auth = authAttempt(token, port) { stopAfterUserCancel(PROFILE, token, "cancelled", owner) }
        VpnAuthEndpoint.register(token, auth)
        auth.poll()
        // The tick read OFFERED just before the cancel started and publishes the SSH failure while the
        // cancel is in Core.
        val staleCode = coreFailureCode("failed", "ssh_connection_failed", auth.history)
        assertEquals(VpnErrorCode.AUTH_UNANSWERED, staleCode)
        port.duringCancel = {
            assertEquals("failed", VpnLive.publishCoreStatus(token, PROFILE, "failed", 0, 0, 0, "x", staleCode))
            assertEquals(VpnErrorCode.AUTH_UNANSWERED, VpnLive.state.value.code)
        }
        assertEquals(AuthResult.ACCEPTED, VpnAuthEndpoint.cancel(token, SESSION, REQUEST_A))
        assertEquals("failed", VpnLive.state.value.state)
        assertEquals(VpnErrorCode.AUTH_CANCELLED, VpnLive.state.value.code)
        assertTrue(owner.isCancelled)
    }

    @Test fun userCancelKeepsNonAuthFailureCode() {
        val token = attempt()
        VpnLive.publishFailure(VpnErrorCode.NETWORK_ERROR, "net", PROFILE, token)
        stopAfterUserCancel(PROFILE, token, "cancelled", null)
        assertEquals(VpnErrorCode.NETWORK_ERROR, VpnLive.state.value.code)
    }

    @Test fun replyToEarlierRequestWhileNewerIsOfferedKeepsOffered() = runBlocking {
        val token = attempt()
        val port = FakeCorePort(wire(REQUEST_A))
        val auth = authAttempt(token, port)
        auth.poll()
        // B replaces A on a tick after the endpoint validated the reply to A but before it reached the sink.
        port.pending = wire(REQUEST_B)
        auth.poll()
        assertEquals(REQUEST_B, VpnLive.auth.value?.requestId)
        assertTrue(auth.deliver(SESSION, REQUEST_A, "[\"pw\"]"))
        assertEquals(AuthHistory.OFFERED, auth.history)
        assertEquals(VpnErrorCode.AUTH_UNANSWERED, coreFailureCode("failed", "ssh_connection_failed", auth.history))
    }

    @Test fun cancelInReconnectingOverridesReconnectCode() = runBlocking {
        val token = attempt("connected")
        VpnLive.publishCoreStatus(token, PROFILE, "reconnecting", 1, 1, 10, "x", VpnErrorCode.CORE_CONNECT_FAILED)
        val owner = Job()
        stopAfterUserCancel(PROFILE, token, "cancelled", owner)
        assertEquals("failed", VpnLive.state.value.state)
        assertEquals(VpnErrorCode.AUTH_CANCELLED, VpnLive.state.value.code)
        assertTrue(owner.isCancelled)
    }

    @Test fun cadenceIs250OnlyWhileThisAttemptHasAnOffer() {
        val token = attempt()
        assertEquals(200L, statusTickMillis(connected = false, token))
        assertEquals(500L, statusTickMillis(connected = true, token))
        val port = FakeCorePort(wire(REQUEST_A))
        val auth = authAttempt(token, port)
        auth.poll()
        assertEquals(200L, statusTickMillis(connected = false, token))
        assertEquals(250L, statusTickMillis(connected = true, token))
        assertEquals(500L, statusTickMillis(connected = true, token + 1))
        port.pending = "null"
        auth.poll()
        assertEquals(500L, statusTickMillis(connected = true, token))
    }

    @Test fun staleAttemptPollCannotPublish() {
        val old = attempt()
        VpnLive.nextAttempt()
        authAttempt(old, FakeCorePort(wire(REQUEST_A))).poll()
        assertNull(VpnLive.auth.value)
        assertFalse(VpnLive.gate.accepts(old))
    }
}
