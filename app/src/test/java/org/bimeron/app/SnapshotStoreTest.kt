package org.bimeron.app

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Persistence, reload, cleanup and concurrency of ssconf/<uuid>.snap. */
class SnapshotStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val sourceA = "ssconf://a.example/config"
    private val sourceB = "ssconf://b.example/config"
    private val plain = "ss://YWVzLTEyOC1nY206cGFzcw@example.com:443"
    private val root by lazy { temp.root.canonicalFile }
    private val directory by lazy { File(root, "profiles").apply { mkdir() } }
    private val store by lazy { ProfileStore(null, directory, SshProfileResources(File(root, "ssh-resources")), coreValidation = {}) }
    private val snapshots get() = File(root, "ssconf")
    private fun snap(id: UUID) = File(snapshots, "$id.snap")
    private suspend fun save(id: UUID, key: String) {
        val expected = store.load().first.firstOrNull { it.id == id.toString() }?.revision
        assertTrue(store.compareAndSave(UUID.randomUUID(), id, expected, Conf.fromKey("Profile", key), null) is CommitResult.Committed)
    }
    private suspend fun commit(id: UUID, body: String) =
        store.commitSnapshot(store.readForSession(id.toString()) { true }.ticket!!, body.toByteArray())

    @Test fun filesArePrivateAndNoTmpRemains() = runBlocking<Unit> {
        val id = UUID.randomUUID(); save(id, sourceA)
        assertTrue(commit(id, "body") is SnapshotCommitResult.Committed)
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(snapshots.toPath()))
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(snap(id).toPath()))
        assertEquals(listOf("$id.snap"), snapshots.list()!!.toList())
        assertEquals(sourceA, SSConfSnapshots.decodeBound(id, snap(id).readBytes())!!.source)
    }

    @Test fun reloadAfterNewStoreInstanceReturnsSnapshot() = runBlocking<Unit> {
        val id = UUID.randomUUID(); save(id, sourceA)
        assertTrue(commit(id, "persisted") is SnapshotCommitResult.Committed)
        val reopened = ProfileStore(null, directory, SshProfileResources(File(root, "ssh-resources")), coreValidation = {})
        val input = reopened.readForSession(id.toString()) { true }
        assertArrayEquals("persisted".toByteArray(), input.snapshot!!.body)
        // A fresh instance has no commit history, so its new tickets are accepted.
        assertTrue(reopened.commitSnapshot(input.ticket!!, "next".toByteArray()) is SnapshotCommitResult.Committed)
    }

    @Test fun corruptFileIsAbsentAndDeleted() = runBlocking<Unit> {
        val id = UUID.randomUUID(); save(id, sourceA)
        assertTrue(commit(id, "body") is SnapshotCommitResult.Committed)
        val bytes = snap(id).readBytes()
        snap(id).writeBytes(bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() })
        assertNull(store.readForSession(id.toString()) { true }.snapshot)
        assertFalse(snap(id).exists())
        snap(id).writeBytes(bytes.copyOf(bytes.size - 1))
        assertNull(store.readForSession(id.toString()) { true }.snapshot)
        assertFalse(snap(id).exists())
    }

    @Test fun foreignUuidFileIsAbsentAndDeleted() = runBlocking<Unit> {
        val id = UUID.randomUUID(); save(id, sourceA)
        snapshots.mkdir()
        snap(id).writeBytes(SSConfSnapshots.encode(UUID.randomUUID(), sourceA, byteArrayOf(1), 0))
        assertNull(store.readForSession(id.toString()) { true }.snapshot)
        assertFalse(snap(id).exists())
    }

    @Test fun staleSourceFileIsAbsentAndDeleted() = runBlocking<Unit> {
        val id = UUID.randomUUID(); save(id, sourceA)
        snapshots.mkdir()
        // An external edit (or a crash after rename) leaves a file bound to another source.
        snap(id).writeBytes(SSConfSnapshots.encode(id, sourceB, byteArrayOf(1), 0))
        assertNull(store.readForSession(id.toString()) { true }.snapshot)
        assertFalse(snap(id).exists())
        snap(id).writeBytes(SSConfSnapshots.encode(id, sourceA, byteArrayOf(1), 0))
        save(id, plain)
        assertFalse(snap(id).exists())
        snap(id).writeBytes(SSConfSnapshots.encode(id, sourceA, byteArrayOf(1), 0))
        assertNull(store.readForSession(id.toString()) { true }.snapshot)
        assertFalse(snap(id).exists())
    }

    @Test fun saveKeepsSnapshotForSameSourceAndDropsItOnSourceChange() = runBlocking<Unit> {
        val id = UUID.randomUUID(); save(id, sourceA)
        assertTrue(commit(id, "body") is SnapshotCommitResult.Committed)
        val renamed = Conf.rename(store.load().first.single().text, "Renamed")
        assertTrue(store.compareAndSave(UUID.randomUUID(), id, store.load().first.single().revision, renamed, null) is CommitResult.Committed)
        assertTrue(snap(id).exists())
        save(id, sourceB)
        assertFalse(snap(id).exists())
        assertNull(store.readForSession(id.toString()) { true }.snapshot)
    }

    @Test fun symlinkedSnapshotIsIgnoredAndNotFollowed() = runBlocking<Unit> {
        val id = UUID.randomUUID(); save(id, sourceA)
        snapshots.mkdir()
        val outside = temp.newFile("outside").apply { writeBytes(SSConfSnapshots.encode(id, sourceA, byteArrayOf(1), 0)) }
        Files.createSymbolicLink(snap(id).toPath(), outside.toPath())
        val input = store.readForSession(id.toString()) { true }
        assertNull(input.snapshot)
        assertTrue(outside.exists())
        assertTrue(store.commitSnapshot(input.ticket!!, byteArrayOf(2)) is SnapshotCommitResult.Committed)
        assertArrayEquals(byteArrayOf(2), store.readForSession(id.toString()) { true }.snapshot!!.body)
        assertEquals(1, SSConfSnapshots.decode(id, outside.readBytes())!!.body.single().toInt())
    }

    @Test fun ioFailureIsNotPersistedCleansTmpAndKeepsTicketUsable() = runBlocking<Unit> {
        val id = UUID.randomUUID(); save(id, sourceA)
        assertTrue(commit(id, "old") is SnapshotCommitResult.Committed)
        val ticket = store.readForSession(id.toString()) { true }.ticket!!
        store.moveSnapshot = { _, _ -> throw IOException("disk full") }
        assertEquals(SnapshotCommitResult.NotPersisted, store.commitSnapshot(ticket, "new".toByteArray()))
        assertEquals(listOf("$id.snap"), snapshots.list()!!.toList())
        assertArrayEquals("old".toByteArray(), store.readForSession(id.toString()) { true }.snapshot!!.body)
        store.moveSnapshot = { source, target -> Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE) }
        assertTrue(store.commitSnapshot(ticket, "new".toByteArray()) is SnapshotCommitResult.Committed)
    }

    @Test fun unwritableDirectoryIsNotPersisted() = runBlocking<Unit> {
        val id = UUID.randomUUID(); save(id, sourceA)
        snapshots.writeText("not a directory")
        assertEquals(SnapshotCommitResult.NotPersisted, commit(id, "body"))
        assertEquals("not a directory", snapshots.readText())
    }

    @Test fun concurrentSaveDeleteAndCommitNeverLeaveWrongSource() {
        val id = UUID.randomUUID()
        runBlocking { save(id, sourceA) }
        val keys = listOf(sourceA, sourceB, plain)
        repeat(30) { round ->
            val barrier = CyclicBarrier(4)
            val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
            val writer = thread {
                runCatching { barrier.await(); runBlocking {
                    repeat(8) { step ->
                        val current = store.load().first.firstOrNull()
                        if (current == null) { assertTrue(store.compareAndSave(UUID.randomUUID(), id, null, Conf.fromKey("P", keys[(round + step) % 3]), null) is CommitResult.Committed) }
                        else if ((round + step) % 5 == 4) store.compareAndDelete(UUID.randomUUID(), id, current.revision)
                        else store.compareAndSave(UUID.randomUUID(), id, current.revision, Conf.fromKey("P", keys[(round + step) % 3]), null)
                    }
                } }.onFailure(failures::add)
            }
            val committers = (1..3).map { n -> thread {
                runCatching { barrier.await(); runBlocking {
                    repeat(8) {
                        val ticket = runCatching { store.readForSession(id.toString()) { true }.ticket }.getOrNull() ?: return@repeat
                        store.commitSnapshot(ticket, "body-$n-$it".toByteArray())
                    }
                } }.onFailure(failures::add)
            } }
            (committers + writer).forEach { it.join() }
            assertTrue(failures.toString(), failures.isEmpty())
            val profile = File(directory, "$id.conf")
            if (!snap(id).exists()) return@repeat
            assertTrue("snapshot without profile", profile.exists())
            val expected = SSConfSnapshots.sourceOf(ConfigDocument.parse(profile.readText()))
            assertEquals(expected, SSConfSnapshots.decodeBound(id, snap(id).readBytes())!!.source)
        }
        assertTrue(snapshots.list().orEmpty().none { it.contains(".tmp-") })
    }
}
