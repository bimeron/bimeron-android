package org.bimeron.app

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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnAuthEndpointTest {
    @After fun reset() = VpnLive.resetAfterProcessStart()

    private class RecordingSink : AuthSink {
        val calls = mutableListOf<String>()
        var thread: Thread? = null
        override suspend fun deliver(sessionId: String, requestId: String, answers: String?): Boolean {
            thread = Thread.currentThread()
            calls += if (answers == null) "cancel:$requestId" else "reply:$requestId"
            return true
        }
    }

    private fun offer(token: Long, request: String = REQUEST_A) = AuthOffer(token, SESSION, request, wire(request))

    @Test fun matchingReplyIsAcceptedOffMain() = runBlocking {
        val token = attempt()
        val sink = RecordingSink()
        VpnAuthEndpoint.register(token, sink)
        VpnLive.publishAuth(token, offer(token))
        assertEquals(AuthResult.ACCEPTED, VpnAuthEndpoint.reply(token, SESSION, REQUEST_A, "[\"pw\"]"))
        assertEquals(listOf("reply:$REQUEST_A"), sink.calls)
        assertNotEquals(Thread.currentThread(), sink.thread)
        assertTrue(sink.thread!!.name.startsWith("DefaultDispatcher-worker"))
        assertEquals(AuthResult.ACCEPTED, VpnAuthEndpoint.cancel(token, SESSION, REQUEST_A))
        assertTrue(sink.thread!!.name.startsWith("DefaultDispatcher-worker"))
    }

    @Test fun staleTokenIsRejectedWithoutCallingCore() = runBlocking {
        val old = attempt()
        val oldSink = RecordingSink()
        VpnAuthEndpoint.register(old, oldSink)
        VpnLive.publishAuth(old, offer(old))
        val token = VpnLive.nextAttempt()
        val sink = RecordingSink()
        VpnAuthEndpoint.register(token, sink)
        VpnLive.publishAuth(token, offer(token))
        assertEquals(AuthResult.STALE, VpnAuthEndpoint.reply(old, SESSION, REQUEST_A, "[\"pw\"]"))
        assertEquals(AuthResult.STALE, VpnAuthEndpoint.cancel(old, SESSION, REQUEST_A))
        assertTrue(oldSink.calls.isEmpty() && sink.calls.isEmpty())
        assertEquals(token, VpnLive.auth.value?.token)
    }

    @Test fun registrationMismatchIsStale() = runBlocking {
        val token = attempt()
        VpnLive.publishAuth(token, offer(token))
        // No registration at all.
        assertEquals(AuthResult.STALE, VpnAuthEndpoint.reply(token, SESSION, REQUEST_A, "[]"))
        // A stale attempt cannot register.
        val sink = RecordingSink()
        VpnAuthEndpoint.register(token - 1, sink)
        assertEquals(AuthResult.STALE, VpnAuthEndpoint.reply(token, SESSION, REQUEST_A, "[]"))
        assertTrue(sink.calls.isEmpty())
    }

    @Test fun offerMismatchIsStaleAndKeepsOffer() = runBlocking {
        val token = attempt()
        val sink = RecordingSink()
        VpnAuthEndpoint.register(token, sink)
        // Registered but nothing offered.
        assertEquals(AuthResult.STALE, VpnAuthEndpoint.reply(token, SESSION, REQUEST_A, "[]"))
        val published = offer(token)
        VpnLive.publishAuth(token, published)
        assertEquals(AuthResult.STALE, VpnAuthEndpoint.reply(token, SESSION, REQUEST_B, "[]"))
        assertEquals(AuthResult.STALE, VpnAuthEndpoint.cancel(token, REQUEST_B, REQUEST_A))
        assertTrue(sink.calls.isEmpty())
        assertSame(published, VpnLive.auth.value)
    }

    @Test fun lateUnregisterFromOldTokenKeepsNewerSink() = runBlocking {
        val old = attempt()
        VpnAuthEndpoint.register(old, RecordingSink())
        val token = VpnLive.nextAttempt()
        val sink = RecordingSink()
        VpnAuthEndpoint.register(token, sink)
        VpnLive.publishAuth(token, offer(token))
        VpnAuthEndpoint.unregister(old)
        VpnLive.publishAuth(old, null)
        assertEquals(token, VpnLive.auth.value?.token)
        assertEquals(AuthResult.ACCEPTED, VpnAuthEndpoint.reply(token, SESSION, REQUEST_A, "[]"))
        VpnAuthEndpoint.unregister(token)
        assertEquals(AuthResult.STALE, VpnAuthEndpoint.reply(token, SESSION, REQUEST_A, "[]"))
    }

    @Test fun coreExceptionIsRejectedWithoutText() = runBlocking {
        val token = attempt()
        val secret = "s3cret-answer"
        VpnAuthEndpoint.register(token) { _, _, _ -> throw IllegalStateException("core said $secret") }
        VpnLive.publishAuth(token, offer(token))
        val journal = AppEvents.entries.value.size
        val result = VpnAuthEndpoint.reply(token, SESSION, REQUEST_A, "[\"$secret\"]")
        assertEquals(AuthResult.REJECTED, result)
        assertEquals(journal, AppEvents.entries.value.size)
        assertFalse(VpnLive.state.value.toString().contains(secret))
        assertFalse(VpnLive.auth.value.toString().contains(secret))
    }

    @Test fun closedSinkIsUnavailable() = runBlocking {
        val token = attempt()
        VpnAuthEndpoint.register(token) { _, _, _ -> false }
        VpnLive.publishAuth(token, offer(token))
        assertEquals(AuthResult.UNAVAILABLE, VpnAuthEndpoint.cancel(token, SESSION, REQUEST_A))
    }

    @Test fun offerIsPublishedOnlyUnderCurrentToken() {
        val old = attempt()
        val token = VpnLive.nextAttempt()
        VpnLive.publishAuth(old, offer(old))
        assertNull(VpnLive.auth.value)
        // An offer tagged with another token is refused even under the current token.
        VpnLive.publishAuth(token, offer(old))
        assertNull(VpnLive.auth.value)
        VpnLive.publishAuth(token, offer(token))
        assertEquals(token, VpnLive.auth.value?.token)
        // A stale token can neither replace nor clear it.
        VpnLive.publishAuth(old, null)
        assertEquals(token, VpnLive.auth.value?.token)
    }

    @Test fun terminalAttemptCannotPublishOffer() {
        val token = attempt()
        VpnLive.publish(VpnState("failed", attemptProfileId = PROFILE, code = VpnErrorCode.CORE_CONNECT_FAILED), token)
        VpnLive.publishAuth(token, offer(token))
        assertNull(VpnLive.auth.value)
    }
}
