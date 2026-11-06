package org.bimeron.app

import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Service-side recovery wiring through [RecoveryStartPort] (§6, §13.1). */
class RecoveryStartTest {
    @get:Rule val temp = TemporaryFolder()
    @After fun reset() { VpnLive.resetAfterProcessStart() }

    private val sshText = Conf.fromKey("Home server", "ssh://user@example.com:22")
    private val ssText = Conf.fromKey("Home server", "ss://YWVzLTEyOC1nY206c2VjcmV0@example.com:8388")

    private fun input(text: String, id: UUID = UUID.randomUUID()): SessionInput {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return SessionInput(id, bytes, ProfileRevision(1, 1), Sha256.of(bytes), null, null, null)
    }

    /** Records every effect in order; [onRead] supplies the read result. */
    private class FakePort(var onRead: suspend (String, Long) -> SessionInput?) : RecoveryStartPort {
        val effects = mutableListOf<String>()
        var reads = 0
        var recoveryId: String? = "persisted"
        var notificationShown = false
        override var destroyed = false
        override suspend fun read(id: String, token: Long): SessionInput? { reads++; effects += "read"; return onRead(id, token) }
        override suspend fun clearRecovery(): Boolean { effects += "clear"; recoveryId = null; return true }
        override fun postNotification() { effects += "post"; notificationShown = true }
        override fun cancelNotification() { effects += "cancel"; notificationShown = false }
        override fun recordSkip() { effects += "journal" }
        override fun publishSkipped(token: Long) {
            effects += "publish"
            VpnLive.publish(recoverySkippedState { "fixed" }, token)
        }
    }

    @Test fun interactiveRecoveryClearsIdPostsNotificationAndPublishesUnderItsToken() = runBlocking {
        val port = FakePort { _, _ -> input(sshText) }
        noteStartCommand(true, port)
        val token = VpnLive.nextAttempt()
        val plan = planStart(true, UUID.randomUUID().toString(), token, port)
        assertEquals(StartPlan.Skipped, plan)
        assertEquals(listOf("read", "clear", "post", "journal", "publish"), port.effects)
        assertNull(port.recoveryId)
        assertTrue(port.notificationShown)
        assertEquals(VpnErrorCode.RECOVERY_NEEDS_USER, VpnLive.state.value.code)
        assertNull(VpnLive.state.value.attemptProfileId)
    }

    @Test fun absentRecoveryInputIsSkipped() = runBlocking {
        val port = FakePort { _, _ -> null }
        val plan = planStart(true, UUID.randomUUID().toString(), VpnLive.nextAttempt(), port)
        assertEquals(StartPlan.Skipped, plan)
        assertNull(port.recoveryId)
        assertTrue(port.notificationShown)
    }

    @Test fun absentInputIsSkippedEvenIfClassifierSaysNonInteractive() = runBlocking {
        val port = FakePort { _, _ -> null }
        assertEquals(StartPlan.Skipped, planStart(true, "id", VpnLive.nextAttempt(), port, classify = { false }))
    }

    @Test fun supersededReadLeavesSilently() = runBlocking {
        val before = VpnLive.state.value
        // The app starts another attempt while the recovery read is in flight.
        val port = FakePort { _, _ -> VpnLive.nextAttempt(); input(sshText) }
        val plan = planStart(true, UUID.randomUUID().toString(), VpnLive.nextAttempt(), port)
        assertEquals(StartPlan.Superseded, plan)
        assertEquals(listOf("read"), port.effects)
        assertEquals("persisted", port.recoveryId)
        assertEquals(before, VpnLive.state.value)
    }

    @Test fun supersededNonInteractiveReadDoesNotProceed() = runBlocking {
        val port = FakePort { _, _ -> VpnLive.nextAttempt(); input(ssText) }
        assertEquals(StartPlan.Superseded, planStart(true, "id", VpnLive.nextAttempt(), port))
    }

    @Test fun destroyedDuringReadStopsWithoutEffects() = runBlocking {
        lateinit var port: FakePort
        port = FakePort { _, _ -> port.destroyed = true; input(sshText) }
        assertEquals(StartPlan.Destroyed, planStart(true, "id", VpnLive.nextAttempt(), port))
        assertEquals(listOf("read"), port.effects)
    }

    @Test fun userStartQueuedBehindRecoverySkipCancelsNotification() = runBlocking {
        val port = FakePort { _, _ -> input(sshText) }
        noteStartCommand(true, port)
        assertEquals(StartPlan.Skipped, planStart(true, "id", VpnLive.nextAttempt(), port))
        assertTrue(port.notificationShown)
        port.effects.clear()
        noteStartCommand(false, port)
        val plan = planStart(false, "id", VpnLive.nextAttempt(), port,
            classify = { fail("user start classified"); true })
        assertEquals(StartPlan.Proceed(null), plan)
        assertEquals(listOf("cancel"), port.effects)
        assertEquals(1, port.reads)
        assertTrue(!port.notificationShown)
    }

    @Test fun recoveryStartNeverCancelsNotification() {
        val port = FakePort { _, _ -> null }
        noteStartCommand(true, port)
        assertTrue(port.effects.isEmpty())
    }

    @Test fun nonInteractiveRecoveryProceedsWithTheClassifiedInput() = runBlocking {
        val expected = input(ssText)
        var classified = 0
        val port = FakePort { _, _ -> expected }
        val plan = planStart(true, "id", VpnLive.nextAttempt(), port,
            classify = { classified++; assertSame(expected, it); false })
        assertSame(expected, (plan as StartPlan.Proceed).input)
        assertEquals(1, port.reads)
        assertEquals(1, classified)
        assertEquals(listOf("read"), port.effects)
    }

    @Test fun classificationRunsOffTheCallingThread() = runBlocking {
        val caller = Thread.currentThread()
        var classifierThread: Thread? = null
        val port = FakePort { _, _ -> input(ssText) }
        planStart(true, "id", VpnLive.nextAttempt(), port, classify = { classifierThread = Thread.currentThread(); false })
        assertNotEquals(caller, classifierThread)
        assertTrue(classifierThread!!.name, classifierThread!!.name.startsWith("DefaultDispatcher"))
        Executors.newSingleThreadExecutor { Thread(it, "classifier-test") }.asCoroutineDispatcher().use { dispatcher ->
            planStart(true, "id", VpnLive.nextAttempt(), port, classify = { classifierThread = Thread.currentThread(); false },
                classifier = dispatcher)
        }
        assertEquals("classifier-test", classifierThread!!.name)
    }

    @Test fun recoveryStartReadsOnceAndSessionStartsTheClassifiedBytes() = runBlocking {
        val directory = temp.newFolder()
        val store = ProfileStore(null, directory, SshProfileResources(temp.newFolder())) {}
        val id = UUID.randomUUID()
        store.compareAndSave(UUID.randomUUID(), id, null, ssText, null)
        val file = File(directory, "$id.conf")
        val classifiedBytes = file.readBytes()
        val port = FakePort { readId, token -> readSessionInput(store, readId, token) }
        val token = VpnLive.nextAttempt()
        val plan = planStart(true, id.toString(), token, port)
        val classified = (plan as StartPlan.Proceed).input!!
        // A concurrent edit to an SSH profile lands between classification and session start.
        file.writeText(sshText)
        val started = reuseClassifiedInput(classified, id.toString(), token)
        assertSame(classified, started)
        assertArrayEquals(classifiedBytes, started.bytes)
        assertEquals(1, port.reads)
    }

    @Test fun reusedInputFailsClosedWhenStaleOrForAnotherProfile() = runBlocking {
        val directory = temp.newFolder()
        val store = ProfileStore(null, directory, SshProfileResources(temp.newFolder())) {}
        val id = UUID.randomUUID()
        store.compareAndSave(UUID.randomUUID(), id, null, Conf.fromKey("A", "ssconf://example.com/config"), null)
        val token = VpnLive.nextAttempt()
        val classified = readSessionInput(store, id.toString(), token)
        try { reuseClassifiedInput(classified, UUID.randomUUID().toString(), token); fail("other profile accepted") }
        catch (_: IllegalArgumentException) {}
        VpnLive.nextAttempt()
        try { reuseClassifiedInput(classified, id.toString(), token); fail("stale input accepted") }
        catch (_: CancellationException) {}
    }
}
