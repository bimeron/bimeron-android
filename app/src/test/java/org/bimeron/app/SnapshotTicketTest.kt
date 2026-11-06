package org.bimeron.app

import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SnapshotTicketTest {
    @get:Rule val temp = TemporaryFolder()
    private val sourceA = "ssconf://a.example/config"
    private val sourceB = "ssconf://b.example/config"
    private val directory by lazy { File(temp.root.canonicalFile, "profiles").apply { mkdir() } }
    private val store by lazy { ProfileStore(null, directory, SshProfileResources(File(temp.root.canonicalFile, "ssh-resources")), coreValidation = {}) }
    private val snapshots get() = File(temp.root.canonicalFile, "ssconf")
    private fun text(key: String) = Conf.fromKey("Profile", key)
    private suspend fun save(id: UUID, key: String) {
        val expected = store.load().first.firstOrNull { it.id == id.toString() }?.revision
        assertTrue(store.compareAndSave(UUID.randomUUID(), id, expected, text(key), null) is CommitResult.Committed)
    }
    private suspend fun ticket(id: UUID, valid: () -> Boolean = { true }) = store.readForSession(id.toString(), valid).ticket!!
    private fun committed(result: SnapshotCommitResult) = (result as SnapshotCommitResult.Committed).snapshot

    @Test fun committedSnapshotIsReturnedOnNextRead() = runBlocking<Unit> {
        val id = UUID.randomUUID(); save(id, sourceA)
        val input = store.readForSession(id.toString()) { true }
        assertNull(input.snapshot)
        assertEquals(SSConfBinding(sourceA), input.ssconf)
        assertEquals(sourceA, input.ticket!!.source); assertEquals(id, input.ticket!!.profileId)
        val stored = committed(store.commitSnapshot(input.ticket!!, "body".toByteArray()))
        assertEquals(Sha256.of("body".toByteArray()), stored.digest)
        val reread = store.readForSession(id.toString()) { true }.snapshot!!
        assertArrayEquals("body".toByteArray(), reread.body); assertEquals(stored.digest, reread.digest); assertEquals(stored.savedAt, reread.savedAt)
    }

    @Test fun nonSsconfProfileHasNoTicket() = runBlocking<Unit> {
        val id = UUID.randomUUID(); save(id, "ss://YWVzLTEyOC1nY206cGFzcw@example.com:443")
        val input = store.readForSession(id.toString()) { true }
        assertNull(input.ssconf); assertNull(input.ticket); assertNull(input.snapshot)
    }

    @Test fun sourceChangeIsRejected() = runBlocking<Unit> {
        val id = UUID.randomUUID(); save(id, sourceA)
        val old = ticket(id)
        save(id, sourceB)
        assertEquals(SnapshotCommitResult.Conflict(ConflictReason.SOURCE_CHANGED), store.commitSnapshot(old, byteArrayOf(1)))
        save(id, "ss://YWVzLTEyOC1nY206cGFzcw@example.com:443")
        assertEquals(SnapshotCommitResult.Conflict(ConflictReason.SOURCE_CHANGED), store.commitSnapshot(old, byteArrayOf(1)))
        assertFalse(File(snapshots, "$id.snap").exists())
    }

    @Test fun olderSeqAfterNewerCommitIsSuperseded() = runBlocking<Unit> {
        val id = UUID.randomUUID(); save(id, sourceA)
        val older = ticket(id); val newer = ticket(id)
        assertTrue(newer.seq > older.seq)
        committed(store.commitSnapshot(newer, "new".toByteArray()))
        assertEquals(SnapshotCommitResult.Conflict(ConflictReason.SUPERSEDED), store.commitSnapshot(older, "old".toByteArray()))
        assertEquals(SnapshotCommitResult.Conflict(ConflictReason.SUPERSEDED), store.commitSnapshot(newer, "again".toByteArray()))
        assertArrayEquals("new".toByteArray(), store.readForSession(id.toString()) { true }.snapshot!!.body)
    }

    @Test fun invalidTicketIsStaleAndStillValidIsEvaluatedAtCommit() = runBlocking<Unit> {
        val id = UUID.randomUUID(); save(id, sourceA)
        var valid = true
        val ticket = ticket(id) { valid }
        valid = false
        assertEquals(SnapshotCommitResult.Conflict(ConflictReason.STALE_TICKET), store.commitSnapshot(ticket, byteArrayOf(1)))
        assertFalse(File(snapshots, "$id.snap").exists())
        valid = true
        committed(store.commitSnapshot(ticket, byteArrayOf(1)))
    }

    @Test fun deletedProfileIsRejectedAndSnapshotRemoved() = runBlocking<Unit> {
        val id = UUID.randomUUID(); save(id, sourceA)
        val first = ticket(id); val second = ticket(id)
        committed(store.commitSnapshot(first, byteArrayOf(1)))
        assertTrue(File(snapshots, "$id.snap").exists())
        val revision = store.load().first.single().revision
        assertTrue(store.compareAndDelete(UUID.randomUUID(), id, revision) is CommitResult.Committed)
        assertFalse(File(snapshots, "$id.snap").exists())
        assertEquals(SnapshotCommitResult.Conflict(ConflictReason.DELETED), store.commitSnapshot(second, byteArrayOf(2)))
    }

    @Test fun sourceAbaIsAccepted() = runBlocking<Unit> {
        val id = UUID.randomUUID(); save(id, sourceA)
        val ticket = ticket(id)
        save(id, sourceB); save(id, sourceA)
        committed(store.commitSnapshot(ticket, "a".toByteArray()))
        assertArrayEquals("a".toByteArray(), store.readForSession(id.toString()) { true }.snapshot!!.body)
    }

    @Test fun discardKeepsTicketUsableForReplacement() = runBlocking<Unit> {
        val id = UUID.randomUUID(); save(id, sourceA)
        committed(store.commitSnapshot(ticket(id), "rejected-by-core".toByteArray()))
        val input = store.readForSession(id.toString()) { true }
        val stored = input.snapshot!!
        store.discardSnapshot(input.ticket!!, stored.digest)
        assertFalse(File(snapshots, "$id.snap").exists())
        committed(store.commitSnapshot(input.ticket!!, "fresh".toByteArray()))
        assertArrayEquals("fresh".toByteArray(), store.readForSession(id.toString()) { true }.snapshot!!.body)
    }

    @Test fun discardWithWrongDigestOrSourceIsNoOp() = runBlocking<Unit> {
        val id = UUID.randomUUID(); save(id, sourceA)
        val stored = committed(store.commitSnapshot(ticket(id), "kept".toByteArray()))
        val file = File(snapshots, "$id.snap"); val bytes = file.readBytes()
        store.discardSnapshot(ticket(id), Sha256.of("other".toByteArray()))
        assertArrayEquals(bytes, file.readBytes())
        // A ticket for another source never deletes the file, even with the matching digest.
        val foreign = SnapshotTicket(id, sourceB, Long.MAX_VALUE) { true }
        store.discardSnapshot(foreign, stored.digest)
        assertArrayEquals(bytes, file.readBytes())
        // Discarding a missing file is silent.
        store.discardSnapshot(SnapshotTicket(UUID.randomUUID(), sourceA, 1) { true }, stored.digest)
    }

    @Test fun bodyLengthIsRequired() = runBlocking<Unit> {
        val id = UUID.randomUUID(); save(id, sourceA)
        val ticket = ticket(id)
        for (body in listOf(ByteArray(0), ByteArray(SSConfSnapshots.MAX_BODY + 1))) {
            try { store.commitSnapshot(ticket, body); fail() } catch (_: IllegalArgumentException) {}
        }
        committed(store.commitSnapshot(ticket, ByteArray(SSConfSnapshots.MAX_BODY)))
    }
}
