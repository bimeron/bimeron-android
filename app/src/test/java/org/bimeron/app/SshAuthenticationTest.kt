package org.bimeron.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SshAuthenticationTest {
    private val session = "a".repeat(48)
    private fun wire(id: String = "b".repeat(48), prompts: String = "[\"Password\"]", echo: String = "[true]") =
        """{"sessionId":"$session","requestId":"$id","kind":"password","prompts":$prompts,"echo":$echo}"""
    private fun invalid(wire: String) {
        val controller = SshAuthenticationController()
        controller.feed(wire)
        assertNull(controller.request)
        assertTrue(controller.answers.isEmpty())
    }
    @Test fun strictBoundsAndTypes() {
        assertNotNull(SshAuthenticationController.parse(wire()))
        assertNotNull(SshAuthenticationController.parse(wire(prompts = "[\"${"é".repeat(512)}\"]")))
        invalid(wire(prompts = "[\"${"é".repeat(513)}\"]"))
        invalid(wire("x".repeat(48)))
        invalid(wire(echo = "[1]")); invalid(wire(echo = "[\"true\"]")); invalid(wire(echo = "[]"))
        invalid(wire(prompts = "[]")); invalid(wire(prompts = "[null]"))
        invalid(wire(prompts = "[\"\\u000a\"]")); invalid(wire(prompts = "[\"\\u202e\"]"))
        invalid(wire(prompts = "[\"\\ud800\"]")); invalid(wire() + "true")
        invalid(wire().replace("\"kind\":", "\"kind\":\"password\",\"kind\":"))
        invalid(wire().replace("password", "unknown"))
        invalid(wire() + " ".repeat(16384))
        invalid(wire(prompts = (1..9).joinToString(prefix = "[", postfix = "]") { "\"p\"" }))
    }
    @Test fun transientAnswersAndNoSecretToString() = runBlocking {
        val c = SshAuthenticationController(); c.feed(wire()); c.answer(0, "synthetic-secret\n\"")
        assertFalse(c.toString().contains("synthetic-secret"))
        assertFalse(c.request.toString().contains("Password"))
        c.feed(wire()); assertEquals("synthetic-secret\n\"", c.answers.single())
        var calls = 0
        c.submit { s, r, answers ->
            calls++; assertEquals(session, s); assertEquals("b".repeat(48), r)
            assertEquals("[\"synthetic-secret\\u000a\\\"\"]", answers)
            assertTrue(c.answers.isEmpty()); true
        }
        c.submit { _, _, _ -> calls++; true }; c.feed(wire())
        assertTrue(c.busy); assertFalse(c.canSubmit); assertTrue(c.answers.isEmpty())
        assertEquals(1, calls)
        c.feed(wire("c".repeat(48))); assertEquals(listOf(""), c.answers)
        c.answer(0, "secret"); c.feed(null); assertTrue(c.answers.isEmpty())
        c.feed(wire()); c.dispose(); c.feed(wire()); assertNull(c.request)
    }
    @Test fun staleCompletionAndDuplicateCancellation() = runBlocking {
        val c = SshAuthenticationController(); c.feed(wire()); c.answer(0, "test")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val work = launch { c.submit { _, _, _ -> entered.complete(Unit); release.await(); error("secret exception") } }
        entered.await(); c.submit { _, _, _ -> fail(); false }
        c.feed(wire("c".repeat(48))); c.answer(0, "new")
        release.complete(Unit); work.join()
        assertEquals(listOf("new"), c.answers); assertFalse(c.replyRejected)
        var cancels = 0
        c.cancel { _, _ -> cancels++; true }; c.cancel { _, _ -> cancels++; true }
        c.feed(wire("c".repeat(48))); assertNull(c.request); assertEquals(1, cancels)
    }
    @Test fun queuedOperationsCannotConsumeReplacementRequest() = runBlocking {
        val c = SshAuthenticationController(); c.feed(wire())
        val old = c.request!!
        c.feed(wire("c".repeat(48)))
        c.submit(old) { _, _, _ -> fail(); false }
        c.cancel(old) { _, _ -> fail(); false }
        assertEquals("c".repeat(48), c.request!!.requestId)
        c.submit { _, _, _ -> error("synthetic secret exception") }
        assertTrue(c.replyRejected); assertTrue(c.answers.isEmpty())
        assertEquals("c".repeat(48), c.request!!.requestId); assertFalse(c.canSubmit); assertFalse(c.busy)
    }
    @Test fun invalidatedRequestCannotReviveAnswersOrCallbacks() = runBlocking {
        val c = SshAuthenticationController(); c.feed(wire()); c.answer(0, "old secret")
        val old = c.request!!
        c.feed(null); c.feed(wire()); c.answer(0, "revived")
        assertNull(c.request); assertTrue(c.answers.isEmpty())
        c.submit(old) { _, _, _ -> fail(); false }; c.cancel(old) { _, _ -> fail(); false }
    }
    @Test fun retiredPayloadCannotReplaceNewerRequestOrAnswers() = runBlocking {
        val c = SshAuthenticationController(); c.feed(wire()); c.answer(0, "old secret")
        val old = c.request!!
        c.feed(wire("c".repeat(48))); val newer = c.request!!
        assertEquals(listOf(""), c.answers)
        c.answer(0, "new secret"); c.feed(wire())
        assertSame(newer, c.request); assertEquals(listOf("new secret"), c.answers)
        c.submit(old) { _, _, _ -> fail(); false }; c.cancel(old) { _, _ -> fail(); false }
        var calls = 0
        c.submit { _, _, answers -> calls++; assertEquals("[\"new secret\"]", answers); true }
        assertEquals(1, calls)
    }
    @Test fun changedImmutableRequestRetiresItsId() {
        val c = SshAuthenticationController(); c.feed(wire()); c.answer(0, "old secret")
        c.feed(wire(prompts = "[\"Changed\"]"))
        assertFalse(c.replyRejected); assertNull(c.request); assertTrue(c.answers.isEmpty())
        c.feed(wire()); assertNull(c.request); assertTrue(c.answers.isEmpty())
        // An invalid change is not a reply rejection and must not leak into the next request.
        c.feed(wire("c".repeat(48))); assertNotNull(c.request); assertFalse(c.replyRejected)
    }
    @Test fun controllerIsSessionScopedAndRetainsAllEightRetiredIds() {
        val c = SshAuthenticationController()
        for (id in 0..7) { c.feed(wire(id.toString().repeat(48))); assertNotNull(c.request); c.feed(null) }
        c.feed(wire("8".repeat(48))); assertNull(c.request)
        for (id in 0..7) { c.feed(wire(id.toString().repeat(48))); assertNull(c.request) }
        c.feed(wire().replace(session, "d".repeat(48))); assertNull(c.request)
        c.dispose(); c.feed(wire()); assertNull(c.request)
    }
    @Test fun answerBoundsRejectUnpairedSurrogates() {
        val c = SshAuthenticationController(); c.feed(wire())
        c.answer(0, "x".repeat(4096)); assertEquals(4096, c.answers.single().length)
        c.answer(0, "x".repeat(4097)); assertEquals(4096, c.answers.single().length)
        c.answer(0, "\ud800"); assertEquals(4096, c.answers.single().length)
        c.answer(0, ""); assertEquals("", c.answers.single())
    }
    @Test fun rejectedReplyMarksFailedAndKeepsHintForNextRequest() = runBlocking {
        val c = SshAuthenticationController(); c.feed(wire()); c.answer(0, "secret")
        c.submit { _, _, _ -> false }
        assertTrue(c.replyRejected); assertFalse(c.busy); assertFalse(c.canSubmit); assertTrue(c.answers.isEmpty())
        assertEquals("b".repeat(48), c.request!!.requestId)
        c.answer(0, "retry"); assertTrue(c.answers.isEmpty())
        c.submit { _, _, _ -> fail(); true }
        c.feed(wire()); assertTrue(c.replyRejected)
        c.feed(wire("c".repeat(48))); assertTrue(c.replyRejected); assertTrue(c.canSubmit)
        c.answer(0, "next"); c.submit { _, _, _ -> true }
        assertFalse(c.replyRejected); assertTrue(c.busy)
        c.feed(wire("d".repeat(48))); assertFalse(c.replyRejected)
        c.feed(null); assertNull(c.request); assertFalse(c.replyRejected)
    }
    @Test fun acceptedReplyStaysBusyUntilNextFeed() = runBlocking {
        val c = SshAuthenticationController(); c.feed(wire()); c.answer(0, "secret")
        c.submit { _, _, _ -> true }
        assertTrue(c.busy); assertFalse(c.replyRejected); assertNotNull(c.request); assertFalse(c.canSubmit)
        c.cancel { _, _ -> fail(); true }; assertTrue(c.busy)
        c.feed(wire()); assertTrue(c.busy)
        c.feed(wire("c".repeat(48))); assertFalse(c.busy); assertTrue(c.canSubmit)
        assertEquals("c".repeat(48), c.request!!.requestId)
        c.submit { _, _, _ -> true }; c.feed(null)
        assertFalse(c.busy); assertNull(c.request)
    }
    @Test fun staleReplyResultIsIgnored() = runBlocking {
        for (result in listOf(true, false)) {
            val c = SshAuthenticationController(); c.feed(wire())
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val work = launch { c.submit { _, _, _ -> entered.complete(Unit); release.await(); result } }
            entered.await(); c.feed(wire("c".repeat(48)))
            release.complete(Unit); work.join()
            assertEquals("c".repeat(48), c.request!!.requestId)
            assertFalse(c.replyRejected); assertFalse(c.busy); assertTrue(c.canSubmit)
        }
    }
    @Test fun lateReplyOnDisposedControllerDoesNotLeakIntoReplacement() = runBlocking {
        val old = SshAuthenticationController(); old.feed(wire())
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val work = launch { old.submit { _, _, _ -> entered.complete(Unit); release.await(); false } }
        entered.await(); old.dispose()
        val replacement = SshAuthenticationController(); replacement.feed(wire())
        release.complete(Unit); work.join()
        assertFalse(old.replyRejected); assertNull(old.request)
        assertFalse(replacement.replyRejected); assertTrue(replacement.canSubmit)
    }
    @Test fun rejectedCancelStaysVisibleAndIsNeverCarriedOver() = runBlocking {
        val c = SshAuthenticationController(); c.feed(wire()); c.answer(0, "secret")
        var cancels = 0
        c.cancel { _, _ -> cancels++; false }
        assertTrue(c.cancelFailed); assertFalse(c.replyRejected); assertFalse(c.busy); assertFalse(c.canSubmit)
        assertEquals("b".repeat(48), c.request!!.requestId); assertTrue(c.answers.isEmpty())
        c.feed(wire("c".repeat(48)))
        assertFalse(c.cancelFailed); assertFalse(c.replyRejected); assertTrue(c.canSubmit)
        c.cancel { _, _ -> cancels++; false }; assertTrue(c.cancelFailed)
        // A second cancel after a rejected one closes locally without calling the host.
        c.cancel { _, _ -> cancels++; true }
        assertNull(c.request); assertFalse(c.cancelFailed); assertEquals(2, cancels)
        c.feed(wire("d".repeat(48))); assertFalse(c.cancelFailed); assertFalse(c.replyRejected)
        c.cancel { _, _ -> error("synthetic secret exception") }; assertTrue(c.cancelFailed)
        c.feed(null); assertNull(c.request); assertFalse(c.cancelFailed)
    }
    @Test fun supersededCancelResultIsIgnored() = runBlocking {
        val c = SshAuthenticationController(); c.feed(wire())
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val work = launch { c.cancel { _, _ -> entered.complete(Unit); release.await(); false } }
        entered.await(); assertTrue(c.busy); c.feed(wire("c".repeat(48)))
        release.complete(Unit); work.join()
        assertFalse(c.cancelFailed); assertEquals("c".repeat(48), c.request!!.requestId); assertTrue(c.canSubmit)
    }
    @Test fun acceptedCancelHidesRequest() = runBlocking {
        val c = SshAuthenticationController(); c.feed(wire(prompts = "[\"Code\"]")); c.answer(0, "secret")
        c.cancel { _, _ -> true }
        assertNull(c.request); assertTrue(c.answers.isEmpty()); assertFalse(c.cancelFailed)
    }
}
