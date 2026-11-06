package org.bimeron.app

import kotlinx.coroutines.runBlocking
import org.bimeron.app.AuthTestSupport.PROFILE
import org.bimeron.app.AuthTestSupport.REQUEST_A
import org.bimeron.app.AuthTestSupport.SESSION
import org.bimeron.app.AuthTestSupport.attempt
import org.bimeron.app.AuthTestSupport.wire
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthErrorCodeTest {
    @After fun reset() = VpnLive.resetAfterProcessStart()

    @Test fun sshFailureCodeFollowsAuthHistory() {
        val ssh = "ssh_connection_failed"
        assertEquals(VpnErrorCode.SSH_CONNECTION_FAILED, coreFailureCode("failed", ssh, AuthHistory.NONE))
        assertEquals(VpnErrorCode.SSH_CONNECTION_FAILED, coreFailureCode("failed", ssh))
        assertEquals(VpnErrorCode.AUTH_FAILED, coreFailureCode("failed", ssh, AuthHistory.REPLIED))
        assertEquals(VpnErrorCode.AUTH_UNANSWERED, coreFailureCode("failed", ssh, AuthHistory.WITHDRAWN))
        assertEquals(VpnErrorCode.AUTH_UNANSWERED, coreFailureCode("failed", ssh, AuthHistory.OFFERED))
        assertEquals(VpnErrorCode.AUTH_CANCELLED, coreFailureCode("failed", ssh, AuthHistory.CANCELLED_BY_USER))
        assertEquals(VpnErrorCode.AUTH_FAILED, coreFailureCode("reconnecting", ssh, AuthHistory.REPLIED))
    }

    @Test fun nonSshFailuresIgnoreAuthHistory() {
        AuthHistory.entries.forEach { history ->
            assertEquals(VpnErrorCode.NETWORK_ERROR, coreFailureCode("failed", "network unreachable", history))
            assertEquals(VpnErrorCode.CORE_CONNECT_FAILED, coreFailureCode("failed", null, history))
            assertNull(coreFailureCode("connected", null, history))
        }
    }

    private fun withOffer(state: String = "connecting"): Long {
        val token = attempt(state)
        VpnAuthEndpoint.register(token) { _, _, _ -> true }
        VpnLive.publishAuth(token, AuthOffer(token, SESSION, REQUEST_A, wire(REQUEST_A)))
        assertEquals(token, VpnLive.auth.value?.token)
        return token
    }

    private fun assertCleared(token: Long) = runBlocking {
        assertNull(VpnLive.auth.value)
        assertEquals(AuthResult.STALE, VpnAuthEndpoint.cancel(token, SESSION, REQUEST_A))
    }

    @Test fun terminalPublishesAndGateAdvancesClearAuth() {
        listOf("failed", "disconnected", "stopping").forEach { terminal ->
            val token = withOffer()
            VpnLive.publish(VpnState(terminal, attemptProfileId = PROFILE), token)
            assertNull(terminal, VpnLive.auth.value)
        }
        withOffer().let { token -> VpnLive.publishFailure(VpnErrorCode.START_FAILED, "x", PROFILE, token); assertCleared(token) }
        withOffer().let { token ->
            assertEquals("failed", VpnLive.publishCoreStatus(token, PROFILE, "failed", 0, 0, 0, null, VpnErrorCode.AUTH_FAILED))
            assertNull(VpnLive.auth.value)
        }
        // The two calls runSession's finally makes (unregister, then withdraw under the token); the
        // service code itself is not exercised here.
        withOffer().let { token ->
            VpnAuthEndpoint.unregister(token)
            VpnLive.publishAuth(token, null)
            assertCleared(token)
        }
        // Command.Start / Command.Stop / Command.Invalid advance the gate and clear directly.
        withOffer().let { token -> VpnLive.nextAttempt(); assertCleared(token) }
        withOffer().let { token -> VpnLive.serviceDestroyed("destroyed"); assertCleared(token) }
        withOffer().let { token -> VpnLive.clearAuth(); assertCleared(token) }
        withOffer().let { token -> VpnLive.resetAfterProcessStart(); assertCleared(token) }
        // A gate advance outside the service (permission flow) drops the offer on its first publish.
        withOffer().let { token ->
            val next = VpnLive.gate.next()
            VpnLive.publish(VpnState("permission", attemptProfileId = PROFILE), next)
            assertCleared(token)
        }
    }

    @Test fun resetDropsRegistration() = runBlocking {
        val token = withOffer()
        VpnLive.resetAfterProcessStart()
        val next = VpnLive.nextAttempt()
        VpnLive.publishAuth(next, AuthOffer(next, SESSION, REQUEST_A, wire(REQUEST_A)))
        assertEquals(AuthResult.STALE, VpnAuthEndpoint.reply(next, SESSION, REQUEST_A, "[]"))
        assertTrue(token != next)
    }
}
