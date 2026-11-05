package org.bimeron.app

import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileStoreCasTest {
    @get:Rule val temp = TemporaryFolder()
    private fun store(validation: (String) -> Unit = {}): ProfileStore = ProfileStore(null,
        temp.newFolder(), SshProfileResources(File(temp.root.canonicalFile, UUID.randomUUID().toString())), coreValidation = validation)
    private fun text(name: String) = Conf.fromKey(name, "ss://YWVzLTEyOC1nY206cGFzcw@example.com:443")
    private suspend fun save(store: ProfileStore, id: UUID, revision: ProfileRevision?, name: String) =
        store.compareAndSave(UUID.randomUUID(), id, revision, text(name), null)
    @Test fun staleAndObservedAbaConflict() = runBlocking {
        val store = store(); val id = UUID.randomUUID()
        assertTrue(save(store, id, null, "A") is CommitResult.Committed)
        val first = store.load().first.single()
        assertEquals(first.profile.id, first.id); assertEquals(first.profile.text, first.text)
        assertTrue(save(store, id, first.revision, "B") is CommitResult.Committed)
        val second = store.load().first.single()
        assertTrue(save(store, id, second.revision, "A") is CommitResult.Committed)
        assertEquals(CommitResult.Conflict(ConflictReason.CHANGED), save(store, id, first.revision, "C"))
        assertEquals(CommitResult.Conflict(ConflictReason.EXISTS), save(store, id, null, "C"))
    }
    @Test fun concurrentSameExpectedHasOneWinnerAndDeleteRecreateNeverReusesRevision() = runBlocking {
        val store = store(); val id = UUID.randomUUID()
        save(store, id, null, "A")
        val revision = store.load().first.single().revision
        val results = (1..2).map { async(Dispatchers.Default) { save(store, id, revision, "B$it") } }.awaitAll()
        assertEquals(1, results.count { it is CommitResult.Committed })
        val current = store.load().first.single().revision
        assertTrue(store.compareAndDelete(UUID.randomUUID(), id, current) is CommitResult.Committed)
        assertEquals(CommitResult.Conflict(ConflictReason.DELETED), save(store, id, current, "C"))
        save(store, id, null, "A")
        assertNotEquals(current, store.load().first.single().revision)
    }
    @Test fun receiptReplayDoesNoValidationOrFileIoAndRejectsForeignPayload() = runBlocking {
        var validations = 0
        val directory = temp.newFolder()
        val store = ProfileStore(null, directory, SshProfileResources(temp.newFolder())) { validations++ }
        val id = UUID.randomUUID(); val request = UUID.randomUUID()
        val result = store.compareAndSave(request, id, null, text("A"), null)
        val count = validations
        File(directory, "$id.conf").delete()
        assertEquals(result, store.compareAndSave(request, id, null, text("A"), null))
        assertEquals(count, validations)
        assertFalse(File(directory, "$id.conf").exists())
        try { store.compareAndSave(request, id, null, text("B"), null); fail() } catch (_: IllegalArgumentException) {}
        assertEquals((result as CommitResult.Committed).receipt, store.committedReceipt(request))
    }
    @Test fun ownedBytesDigestAndMalformedExternalInputFailClosed() = runBlocking {
        val directory = temp.newFolder()
        val store = ProfileStore(null, directory, SshProfileResources(temp.newFolder())) {}
        val id = UUID.randomUUID(); save(store, id, null, "A")
        val input = store.readForSession(id.toString()) { true }
        val bytes = input.bytes; bytes.fill(0)
        assertEquals(Sha256.of(input.bytes), input.digest)
        File(directory, "$id.conf").writeBytes(byteArrayOf(0xc0.toByte(), 0xaf.toByte()))
        assertEquals(CommitResult.Conflict(ConflictReason.UNREADABLE), save(store, id, input.revision, "B"))
        try { store.readForSession(id.toString()) { true }; fail() } catch (e: ProfileError) { assertEquals("INVALID_PROFILE", e.message) }
    }
    @Test fun cancellationAfterRenameStillRecordsReceipt() = runBlocking {
        val store = store(); val id = UUID.randomUUID(); val request = UUID.randomUUID()
        lateinit var saving: Job
        store.renameProfile = { source, target -> source.renameTo(target).also { saving.cancel() } }
        saving = launch(start = CoroutineStart.LAZY) { store.compareAndSave(request, id, null, text("A"), null) }
        saving.start(); saving.join()
        assertTrue(saving.isCancelled)
        assertEquals(CommitReceipt.Kind.SAVED, store.committedReceipt(request)!!.kind)
        assertEquals("A", store.load().first.single().name)
    }
    @Test fun lostReturnStillPublishesThroughProductionFinallyPath() = runBlocking {
        val store = store(); val id = UUID.randomUUID(); val request = UUID.randomUUID()
        var published: CommitReceipt? = null
        lateinit var saving: Job
        store.renameProfile = { source, target -> source.renameTo(target).also { saving.cancel() } }
        saving = launch(start = CoroutineStart.LAZY) {
            publishStoreCommit(store, request, operation = {
                store.compareAndSave(request, id, null, text("A"), null)
            }) { receipt -> published = receipt }
        }
        saving.start(); saving.join()
        assertTrue(saving.isCancelled)
        assertEquals(store.committedReceipt(request), published)
        assertNotNull(published)
    }
}
