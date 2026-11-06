package org.bimeron.app

import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Editor saves and deletes go through a real ProfileStore with the revision the draft was opened at. */
class EditorDraftRevisionTest {
    @get:Rule val temp = TemporaryFolder()
    private val conflictMessage = "#${R.string.profile_edit_conflict}"
    private fun ss(name: String) = Conf.fromKey(name, "ss://YWVzLTEyOC1nY206cGFzcw@example.com:443")

    private class Gate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        suspend fun await() { entered.complete(Unit); release.await() }
    }

    private inner class Fixture(parent: CoroutineScope) {
        val job = SupervisorJob(parent.coroutineContext[Job])
        val scope = CoroutineScope(parent.coroutineContext + job)
        val resources = SshProfileResources(File(temp.root.canonicalFile, UUID.randomUUID().toString()))
        val store = ProfileStore(null, temp.newFolder(), resources) {}
        val commits = EditorCommits { store }
        var loadHook: suspend () -> Unit = {}
        var commitHook: suspend () -> Unit = {}
        var done = 0
        val model = AppModelOperations(scope,
            { loadHook(); val (items, warning) = store.load(); commits.loaded(items); items.map { it.profile } to warning },
            { _, _ -> error("legacy save") }, { error("legacy delete") }, { null }, {}, MutableStateFlow(VpnState()), {}, {},
            saveCommitted = { text, id, draft, publish -> commitHook(); commits.save(text, id, draft as? EditorDraft, publish) },
            deleteCommitted = { id, draft, publish -> commitHook(); commits.delete(id, draft as? EditorDraft, publish) })

        suspend fun create(text: String): String {
            val id = UUID.randomUUID()
            assertTrue(store.compareAndSave(UUID.randomUUID(), id, null, text, null) is CommitResult.Committed)
            model.refresh().join()
            return id.toString()
        }
        /** Mirrors AppModel.openEditor. */
        fun open(id: String): EditorDraft {
            val profile = model.profiles.value.single { it.id == id }
            return EditorDraft(profile, commits.baseRevision(profile)).also { model.editorDraft = it }
        }
        suspend fun stored(id: String) = store.load().first.single { it.id == id }
        /** Another writer changes the profile to [name] and back, ending with identical bytes. */
        suspend fun changeElsewhereAndBack(id: String, name: String) {
            val original = stored(id)
            val uuid = UUID.fromString(id)
            assertTrue(store.compareAndSave(UUID.randomUUID(), uuid, original.revision, ss(name), null) is CommitResult.Committed)
            assertTrue(store.compareAndSave(UUID.randomUUID(), uuid, stored(id).revision, original.text, null) is CommitResult.Committed)
            assertEquals(original.text, stored(id).text)
        }
        fun save(draft: EditorDraft) = model.save(draft.source.text, draft.profile.id, "saved") { done++ }
    }

    private fun scenario(block: suspend Fixture.() -> Unit) = runBlocking {
        val f = Fixture(this)
        try { withTimeout(10_000) { f.model.refresh().join(); f.block() } } finally { f.job.cancelAndJoin() }
    }

    @Test fun abaChangeElsewhereConflictsAndKeepsDraftOpen() = scenario {
        val id = create(ss("A"))
        val draft = open(id)
        assertNotNull(draft.baseRevision)
        changeElsewhereAndBack(id, "B")
        draft.set("Profile", "Name", "Mine")
        save(draft).join()
        assertEquals(0, done)
        assertEquals(conflictMessage, model.error.value)
        assertSame(draft, model.editorDraft)
        assertEquals("Mine", draft.document.value("Profile", "Name"))
        assertEquals("A", stored(id).name)
    }

    @Test fun deleteWithStaleBaseConflictsAndKeepsProfile() = scenario {
        val id = create(ss("A"))
        val draft = open(id)
        changeElsewhereAndBack(id, "B")
        model.delete(id) { done++ }.join()
        assertEquals(0, done)
        assertEquals(conflictMessage, model.error.value)
        assertSame(draft, model.editorDraft)
        assertEquals("A", stored(id).name)
    }

    @Test fun updateWithoutBaseOrMatchingDraftConflictsInsteadOfBlindSave() = scenario {
        val id = create(ss("A"))
        val profile = model.profiles.value.single()
        val unpinned = EditorDraft(profile).also { model.editorDraft = it }
        unpinned.set("Profile", "Name", "Blind")
        save(unpinned).join()
        assertEquals(conflictMessage, model.error.value)
        val draft = open(id)
        model.save(ss("Other text"), id, "saved") { done++ }.join()
        assertEquals(conflictMessage, model.error.value)
        model.editorDraft = null
        model.save(ss("No draft"), id, "saved") { done++ }.join()
        assertEquals(conflictMessage, model.error.value)
        model.delete(id) { done++ }.join()
        assertEquals(conflictMessage, model.error.value)
        assertEquals(0, done)
        assertEquals("A", stored(id).name)
        assertNotNull(draft.baseRevision)
    }

    @Test fun profileShownDifferentlyFromStoreHasNoBase() = scenario {
        val id = create(ss("A"))
        val shown = model.profiles.value.single().copy(name = "Outdated")
        assertNull(commits.baseRevision(shown))
        assertNotNull(commits.baseRevision(model.profiles.value.single { it.id == id }))
    }

    @Test fun successfulSaveClosesAndItsBaseCannotBeReused() = scenario {
        val id = create(ss("A"))
        val draft = open(id)
        draft.set("Profile", "Name", "First")
        save(draft).join()
        assertEquals(1, done)
        assertNull(model.error.value)
        assertEquals("First", stored(id).name)
        // The editor would close here; if this draft were saved again its base is already superseded.
        draft.set("Profile", "Name", "Second")
        save(draft).join()
        assertEquals(1, done)
        assertEquals(conflictMessage, model.error.value)
        assertEquals("First", stored(id).name)
        // Reopening pins the new revision and saves normally.
        open(id).also { it.set("Profile", "Name", "Third") }.let { save(it).join() }
        assertEquals(2, done)
        assertEquals("Third", stored(id).name)
    }

    @Test fun createUsesNullExpectedWithoutDraft() = scenario {
        model.editorDraft = null
        model.save(ss("New"), null, "added") { done++ }.join()
        assertEquals(1, done)
        assertEquals("New", store.load().first.single().name)
    }

    @Test fun committedResultAfterFurtherEditDoesNotClose() = scenario {
        val id = create(ss("A"))
        val draft = open(id)
        draft.set("Profile", "Name", "Saved")
        // Hold the post-commit reload so the edit lands between commit and the done callback.
        val gate = Gate(); loadHook = { gate.await() }
        val saving = save(draft)
        gate.entered.await()
        draft.set("Profile", "Name", "Edited later")
        loadHook = {}
        gate.release.complete(Unit); saving.join()
        assertEquals(0, done)
        assertNull(model.error.value)
        assertSame(draft, model.editorDraft)
        assertEquals("Saved", stored(id).name)
        assertEquals("Edited later", draft.document.value("Profile", "Name"))
    }

    @Test fun editBeforeCommitDropsSaveWithoutClosingOrError() = scenario {
        val id = create(ss("A"))
        val draft = open(id)
        draft.set("Profile", "Name", "Requested")
        val gate = Gate(); commitHook = { gate.await() }
        val saving = save(draft)
        gate.entered.await()
        draft.set("Profile", "Name", "Edited later")
        gate.release.complete(Unit); saving.join()
        assertEquals(0, done)
        assertNull(model.error.value)
        assertSame(draft, model.editorDraft)
        assertEquals("A", stored(id).name)
    }

    @Test fun conflictAfterFurtherEditKeepsNewerError() = scenario {
        val id = create(ss("A"))
        val draft = open(id)
        changeElsewhereAndBack(id, "B")
        draft.set("Profile", "Name", "Mine")
        val gate = Gate(); commitHook = { gate.await() }
        val saving = save(draft)
        gate.entered.await()
        draft.undo()
        model.error.value = "newer"
        gate.release.complete(Unit); saving.join()
        assertEquals(0, done)
        assertEquals("newer", model.error.value)
        assertSame(draft, model.editorDraft)
    }

    @Test fun queuedSaveForEditedOrReplacedDraftIsDropped() = scenario {
        val id = create(ss("A"))
        val draft = open(id)
        draft.set("Profile", "Name", "Queued")
        val gate = Gate(); loadHook = { gate.await() }
        val refresh = model.refresh(); gate.entered.await()
        val saving = save(draft)
        draft.pendingProtocolEdits = true
        loadHook = {}
        gate.release.complete(Unit); refresh.join(); saving.join()
        assertEquals(0, done)
        assertNull(model.error.value)
        assertEquals("A", stored(id).name)
    }

    @Test fun conflictKeepsImportedSshResources() = scenario {
        val id = create(Conf.fromKey("Ssh", "ssh://user@example.com:22"))
        val draft = open(id)
        val token = resources.stage(SshProfileResources.Kind.IDENTITY, "key".byteInputStream())
        assertTrue(draft.importKey(SshProfileResources.Kind.IDENTITY, token))
        changeElsewhereAndBack(id, "Other")
        save(draft).join()
        assertEquals(conflictMessage, model.error.value)
        assertSame(draft, model.editorDraft)
        assertTrue(token.prepared?.let { File(it.path).exists() } ?: token.file.exists())
        val late = resources.stage(SshProfileResources.Kind.KNOWN_HOSTS, "hosts".byteInputStream())
        assertNotNull("draft resources must not be disposed on conflict", draft.resources.accept(late))
        draft.resources.dispose(resources)
    }
}
