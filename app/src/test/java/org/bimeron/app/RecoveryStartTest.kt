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

    /** Records the drop notice (M-1) effects of [noteStartCommand]. */
    private class FakeDropNotice : DropNotice {
        val effects = mutableListOf<String>()
        override fun post() { effects += "post" }
        override fun cancel() { effects += "cancel" }
    }
    private val drop = FakeDropNotice()

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
        val journal = mutableListOf<Int>()
        override fun recordSkip(message: Int) { effects += "journal"; journal += message }
        override fun publishSkipped(token: Long) {
            effects += "publish"
            VpnLive.publish(recoverySkippedState { "fixed" }, token)
        }
    }

    @Test fun interactiveRecoveryClearsIdPostsNotificationAndPublishesUnderItsToken() = runBlocking {
        val port = FakePort { _, _ -> input(sshText) }
        noteStartCommand(true, port, drop)
        val token = VpnLive.nextAttempt()
        val plan = planStart(true, UUID.randomUUID().toString(), token, port)
        assertEquals(StartPlan.Skipped, plan)
        assertEquals(listOf("read", "clear", "post", "journal", "publish"), port.effects)
        assertNull(port.recoveryId)
        assertTrue(port.notificationShown)
        assertEquals(VpnErrorCode.RECOVERY_NEEDS_USER, VpnLive.state.value.code)
        assertNull(VpnLive.state.value.attemptProfileId)
    }

    private val ssconfSource = "ssconf://config.example/recovery-secret"
    private val ssconfText = Conf.fromKey("Home ssconf", ssconfSource)
    private val body = "ss://YWVzLTEyOC1nY206c2VjcmV0@192.0.2.10:8388".toByteArray()

    /** An ssconf read as the store returns it, with or without a persisted snapshot. */
    private fun ssconfInput(snapshot: Boolean): SessionInput {
        val id = UUID.randomUUID()
        val bytes = ssconfText.toByteArray(Charsets.UTF_8)
        return SessionInput(id, bytes, ProfileRevision(1, 1), Sha256.of(bytes), SSConfBinding(ssconfSource),
            SnapshotTicket(id, ssconfSource, 1) { true }, if (snapshot) StoredSnapshot(body, Sha256.of(body), 1L) else null)
    }

    @Test fun interactiveSkipJournalsTheSignInReason() = runBlocking {
        val port = FakePort { _, _ -> input(sshText) }
        assertEquals(StartPlan.Skipped, planStart(true, "id", VpnLive.nextAttempt(), port))
        assertEquals(listOf(R.string.journal_recovery_needs_user), port.journal)
    }

    /** A recovery never fetches, so an ssconf profile without a persisted snapshot is skipped like an interactive one. */
    @Test fun ssconfRecoveryWithoutSnapshotIsSkippedWithNotificationAndJournal() = runBlocking {
        val port = FakePort { _, _ -> ssconfInput(snapshot = false) }
        val token = VpnLive.nextAttempt()
        assertEquals(StartPlan.Skipped, planStart(true, "id", token, port))
        assertEquals(listOf("read", "clear", "post", "journal", "publish"), port.effects)
        assertEquals(listOf(R.string.journal_recovery_ssconf_unavailable), port.journal)
        assertNull(port.recoveryId)
        assertTrue(port.notificationShown)
        assertEquals(VpnErrorCode.RECOVERY_NEEDS_USER, VpnLive.state.value.code)
    }

    /**
     * The service path: planStart hands a recovery its classified read, [isRecoveryStart] derives the snapshot
     * rule from it, and that recovery reuses the persisted snapshot without fetching. A user start fetches once.
     */
    @Test fun recoveryStartThroughTheServicePathNeverFetches() = runBlocking {
        val stored = ssconfInput(snapshot = true)
        val recoveryPlan = planStart(true, stored.id.toString(), VpnLive.nextAttempt(), FakePort { _, _ -> stored })
        val classified = (recoveryPlan as StartPlan.Proceed).input
        assertSame(stored, classified)
        val userPlan = planStart(false, stored.id.toString(), VpnLive.nextAttempt(), FakePort { _, _ -> fail("read"); null })
        assertNull((userPlan as StartPlan.Proceed).input)
        for ((plan, expectedFetches) in listOf(classified to 0, userPlan.input to 1)) {
            var fetches = 0
            val token = VpnLive.nextAttempt()
            val input = sessionInputFor(plan, stored.id.toString(), token) { stored }
            val snapshot = resolveSessionSnapshot(input.ticket!!, input.snapshot, isRecoveryStart(plan),
                ensureAttempt = { ensureSessionAttempt(token) }, validate = { String(it) },
                fetch = { fetches++; throw SSConfFailure(VpnErrorCode.SSCONF_FETCH_FAILED) },
                discard = { _, _ -> fail("discarded") }, commit = { _, _ -> fail("committed"); SnapshotCommitResult.NotPersisted },
                warn = {}, onFallback = {})
            assertEquals(String(body), snapshot)
            assertEquals(expectedFetches, fetches)
        }
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
        noteStartCommand(true, port, drop)
        assertEquals(StartPlan.Skipped, planStart(true, "id", VpnLive.nextAttempt(), port))
        assertTrue(port.notificationShown)
        port.effects.clear()
        noteStartCommand(false, port, drop)
        val plan = planStart(false, "id", VpnLive.nextAttempt(), port,
            classify = { fail("user start classified"); true })
        assertEquals(StartPlan.Proceed(null), plan)
        assertEquals(listOf("cancel"), port.effects)
        assertEquals(listOf("cancel"), drop.effects)
        assertEquals(1, port.reads)
        assertTrue(!port.notificationShown)
    }

    @Test fun recoveryStartNeverCancelsNotification() {
        val port = FakePort { _, _ -> null }
        noteStartCommand(true, port, drop)
        assertTrue(port.effects.isEmpty())
        assertTrue(drop.effects.isEmpty())
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
        val port = FakePort { _, _ -> input(ssText) }
        val defaultThreads = mutableListOf<Thread>()
        val defaultPlan = planStart(true, "id", VpnLive.nextAttempt(), port,
            classify = { defaultThreads += Thread.currentThread(); false })
        assertTrue(defaultPlan.toString(), defaultPlan is StartPlan.Proceed)
        val defaultThread = defaultThreads.single()
        assertNotEquals(caller, defaultThread)
        assertTrue(defaultThread.name, defaultThread.name.startsWith("DefaultDispatcher"))
        // Compare the injected classifier thread by identity, not by name: in coroutines debug mode (on
        // whenever -ea is, as in Gradle tests) a running coroutine renames its thread "<name> @coroutine#N"
        // and restores the name only after resuming the caller, so reading Thread.name here would race.
        val executorThreads = mutableListOf<Thread>()
        val customThreads = mutableListOf<Thread>()
        val customPlan = Executors.newSingleThreadExecutor { task ->
            Thread(task, "classifier-test").also { synchronized(executorThreads) { executorThreads += it } }
        }.asCoroutineDispatcher().use { dispatcher ->
            planStart(true, "id", VpnLive.nextAttempt(), port,
                classify = { customThreads += Thread.currentThread(); false }, classifier = dispatcher)
        }
        assertTrue(customPlan.toString(), customPlan is StartPlan.Proceed)
        assertSame(synchronized(executorThreads) { executorThreads.single() }, customThreads.single())
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
        var sessionReads = 0
        val started = sessionInputFor(classified, id.toString(), token) { sessionReads++; readSessionInput(store, id.toString(), token) }
        assertSame(classified, started)
        assertArrayEquals(classifiedBytes, started.bytes)
        assertEquals(0, sessionReads)
        assertEquals(1, port.reads)
    }

    @Test fun sessionInputNeverReadsWhenAClassifiedInputExists() = runBlocking {
        val id = UUID.randomUUID()
        val classified = input(ssText, id)
        var reads = 0
        val started = sessionInputFor(classified, id.toString(), VpnLive.nextAttempt()) { reads++; input(sshText, id) }
        assertSame(classified, started)
        assertEquals(0, reads)
    }

    @Test fun sessionInputReadsExactlyOnceForAUserStart() = runBlocking {
        val id = UUID.randomUUID()
        val read = input(ssText, id)
        val token = VpnLive.nextAttempt()
        assertTrue(planStart(false, id.toString(), token, FakePort { _, _ -> throw AssertionError("user start read in planStart") })
            == StartPlan.Proceed(null))
        var reads = 0
        val started = sessionInputFor(null, id.toString(), token) { reads++; read }
        assertSame(read, started)
        assertEquals(1, reads)
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
