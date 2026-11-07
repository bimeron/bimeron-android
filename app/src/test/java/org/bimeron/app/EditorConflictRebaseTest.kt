package org.bimeron.app

import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Edit-conflict rebase, reload and save-as-new through AppModelOperations and a real ProfileStore. */
class EditorConflictRebaseTest {
    @get:Rule val temp = TemporaryFolder()
    private val reviewMessage = "#${R.string.profile_conflict_review}"
    private val deletedMessage = "#${R.string.profile_deleted_elsewhere}"
    private val unreadableMessage = "#${R.string.profile_conflict_unreadable}"
    private fun ss(name: String) = Conf.fromKey(name, "ss://YWVzLTEyOC1nY206cGFzcw@example.com:443")
    private val ssh = Conf.fromKey("Ssh", "ssh://user@example.com:22")

    private class Gate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        suspend fun await() { entered.complete(Unit); release.await() }
    }

    private inner class Fixture(parent: CoroutineScope) {
        val job = SupervisorJob(parent.coroutineContext[Job])
        val scope = CoroutineScope(parent.coroutineContext + job)
        val resourcesRoot = File(temp.root.canonicalFile, UUID.randomUUID().toString())
        val resources = SshProfileResources(resourcesRoot)
        val profilesDir: File = temp.newFolder()
        val store = ProfileStore(null, profilesDir, resources) {}
        val runtime = MutableStateFlow(VpnState())
        var loadHook: suspend () -> Unit = {}
        var done = 0
        val commits = EditorCommits { store }
        val model = AppModelOperations(scope,
            { loadHook(); val (items, warning) = store.load(); commits.loaded(items); items.map { it.profile } to warning },
            { _, _ -> error("legacy save") }, { error("legacy delete") }, { null }, {}, runtime, {}, {},
            saveCommitted = { text, id, draft, publish -> commits.save(text, id, draft as? EditorDraft, publish) },
            deleteCommitted = { id, draft, publish -> commits.delete(id, draft as? EditorDraft, publish) },
            checkDelete = { id, draft -> commits.checkDelete(id, draft as? EditorDraft) },
            rebase = { id, text, draft -> commits.rebase(id, text, draft) },
            readLatest = { id -> store.readLatest(UUID.fromString(id)) },
            saveAsNewCommitted = { text, draft, publish -> commits.saveAsNew(text, draft, publish) })

        suspend fun create(text: String): String {
            val id = UUID.randomUUID()
            assertTrue(store.compareAndSave(UUID.randomUUID(), id, null, text, null) is CommitResult.Committed)
            model.refresh().join()
            return id.toString()
        }
        fun open(id: String): EditorDraft {
            val profile = model.profiles.value.single { it.id == id }
            return EditorDraft(profile, commits.baseRevision(profile)).also { model.editorDraft = it }
        }
        suspend fun stored(id: String) = store.load().first.single { it.id == id }
        /** Another writer (the store singleton seen from elsewhere) edits the stored profile. */
        suspend fun changeElsewhere(id: String, change: ConfigDocument.() -> ConfigDocument) {
            val current = stored(id)
            val text = ConfigDocument.parse(current.text).change().render()
            assertTrue(store.compareAndSave(UUID.randomUUID(), UUID.fromString(id), current.revision, text, null) is CommitResult.Committed)
        }
        fun save(draft: EditorDraft) = model.save(draft.source.text, draft.profile.id, "saved") { done++ }
        fun changed(draft: EditorDraft) = draft.conflict as EditorConflict.Changed
    }

    private fun scenario(block: suspend Fixture.() -> Unit) = runBlocking {
        val f = Fixture(this)
        try { withTimeout(10_000) { f.model.refresh().join(); f.block() } } finally { f.job.cancelAndJoin() }
    }

    @Test fun conflictRebasesAndTheNextSaveCommitsAgainstTheLatestRevision() = scenario {
        val id = create(ss("A"))
        val draft = open(id)
        val opened = draft.baseRevision
        changeElsewhere(id) { set("Interface", "DNS", "9.9.9.9").set("Interface", "MTU", "1500") }
        draft.set("Interface", "MTU", "1400")
        draft.set("Profile", "Name", "Mine")
        save(draft).join()
        assertEquals(0, done)
        assertEquals(reviewMessage, model.error.value)
        val conflict = changed(draft)
        assertEquals(stored(id).revision, conflict.latest.revision)
        assertEquals(listOf(MergeField.Key("Interface", "MTU")), conflict.merge.conflicts.map { it.field })
        assertEquals(2, conflict.merge.autoMerged)
        // Nothing was saved and nothing moves until the user applies.
        assertEquals(opened, draft.baseRevision)
        assertEquals("1500", ConfigDocument.parse(stored(id).text).value("Interface", "MTU"))
        assertFalse(draft.applyMerge(emptyMap()))
        val generation = draft.editGeneration
        assertTrue(draft.applyMerge(mapOf(MergeField.Key("Interface", "MTU") to MergeChoice.MINE)))
        assertNull(draft.conflict)
        assertEquals(conflict.latest.revision, draft.baseRevision)
        assertTrue(draft.editGeneration > generation)
        assertFalse("undo does not cross the rebase", draft.canUndo)
        assertTrue(draft.dirty)
        assertEquals("1500", ConfigDocument.parse(stored(id).text).value("Interface", "MTU"))
        save(draft).join()
        assertEquals(1, done)
        assertNull(model.error.value)
        val saved = ConfigDocument.parse(stored(id).text)
        assertEquals("1400", saved.value("Interface", "MTU"))
        assertEquals("9.9.9.9", saved.value("Interface", "DNS"))
        assertEquals("Mine", saved.value("Profile", "Name"))
    }

    @Test fun anotherChangeAfterTheRebaseRebasesAgainFromTheNewBase() = scenario {
        val id = create(ss("A"))
        val draft = open(id)
        changeElsewhere(id) { set("Interface", "DNS", "9.9.9.9") }
        draft.set("Interface", "MTU", "1400")
        save(draft).join()
        assertTrue(changed(draft).merge.conflicts.isEmpty())
        assertTrue(draft.applyMerge(emptyMap()))
        changeElsewhere(id) { set("Interface", "MTU", "1300").set("Profile", "Name", "Theirs again") }
        save(draft).join()
        assertEquals(0, done)
        val second = changed(draft)
        // The base is now the first rebased text, so only MTU conflicts; DNS is not a change any more.
        assertEquals(listOf(MergeField.Key("Interface", "MTU")), second.merge.conflicts.map { it.field })
        assertEquals(1, second.merge.theirsChanges)
        assertTrue(draft.applyMerge(mapOf(MergeField.Key("Interface", "MTU") to MergeChoice.THEIRS)))
        save(draft).join()
        assertEquals(1, done)
        val saved = ConfigDocument.parse(stored(id).text)
        assertEquals("1300", saved.value("Interface", "MTU"))
        assertEquals("9.9.9.9", saved.value("Interface", "DNS"))
        assertEquals("Theirs again", saved.value("Profile", "Name"))
    }

    @Test fun applyIsRefusedWhenTheDraftChangedSinceTheMerge() = scenario {
        val id = create(ss("A"))
        val draft = open(id)
        changeElsewhere(id) { set("Interface", "DNS", "9.9.9.9") }
        draft.set("Interface", "MTU", "1400")
        save(draft).join()
        val base = draft.baseRevision
        draft.set("Interface", "MTU", "1350")
        assertFalse(draft.applyMerge(emptyMap()))
        assertEquals(base, draft.baseRevision)
        assertEquals("1350", draft.document.value("Interface", "MTU"))
    }

    @Test fun reloadLatestReplacesTheDraftAtTheStoredRevision() = scenario {
        val id = create(ss("A"))
        val draft = open(id)
        changeElsewhere(id) { set("Profile", "Name", "Theirs") }
        draft.set("Profile", "Name", "Mine")
        save(draft).join()
        assertNotNull(draft.conflict)
        changeElsewhere(id) { set("Interface", "MTU", "1500") }
        val session = draft.session
        model.reloadLatest().join()
        val latest = stored(id)
        assertSame(draft, model.editorDraft)
        assertNull(draft.conflict)
        assertNull(model.error.value)
        assertEquals(latest.text, draft.source.text)
        assertEquals(latest.revision, draft.baseRevision)
        assertEquals(latest.profile, draft.profile)
        assertFalse(draft.dirty)
        assertFalse(draft.canUndo)
        assertTrue(draft.session > session)
        draft.set("Interface", "DNS", "8.8.8.8")
        save(draft).join()
        assertEquals(1, done)
        assertEquals("8.8.8.8", ConfigDocument.parse(stored(id).text).value("Interface", "DNS"))
        assertEquals("Theirs", stored(id).name)
    }

    @Test fun deletedElsewhereOffersOnlySaveAsNewWhichCreatesANewProfile() = scenario {
        val id = create(ss("A"))
        val draft = open(id)
        assertTrue(store.compareAndDelete(UUID.randomUUID(), UUID.fromString(id), stored(id).revision) is CommitResult.Committed)
        draft.set("Profile", "Name", "Rescued")
        save(draft).join()
        assertEquals(deletedMessage, model.error.value)
        assertEquals(EditorConflict.Gone(deleted = true), draft.conflict)
        assertFalse(draft.applyMerge(emptyMap()))
        // Reloading cannot bring it back either; the panel stays on save-as-new.
        model.reloadLatest().join()
        assertEquals(EditorConflict.Gone(deleted = true), draft.conflict)
        assertEquals("Rescued", draft.document.value("Profile", "Name"))
        model.saveAsNew("saved as new") { done++ }.join()
        assertEquals(1, done)
        assertNull(model.error.value)
        val created = store.load().first.single()
        assertNotEquals(id, created.id)
        assertEquals("Rescued", created.name)
        assertEquals(draft.source.text, created.text)
        assertEquals(created.id, model.selected.value)
        assertTrue(model.profiles.value.any { it.id == created.id })
    }

    @Test fun unreadableStoredProfileOffersSaveAsNew() = scenario {
        val id = create(ss("A"))
        val draft = open(id)
        File(profilesDir, "$id.conf").writeText("[broken")
        draft.set("Profile", "Name", "Mine")
        save(draft).join()
        assertEquals(unreadableMessage, model.error.value)
        assertEquals(EditorConflict.Gone(deleted = false), draft.conflict)
        model.saveAsNew("saved as new") { done++ }.join()
        assertEquals(1, done)
        assertEquals("Mine", store.load().first.single { it.id != id }.name)
    }

    @Test fun saveAsNewCopiesImportedAndSavedSshFilesForTheNewProfile() = scenario {
        // The stored profile already references a saved identity generation.
        val id = create(ssh)
        val first = open(id)
        val savedKey = resources.stage(SshProfileResources.Kind.IDENTITY, "saved-identity".byteInputStream())
        assertTrue(first.importKey(SshProfileResources.Kind.IDENTITY, savedKey))
        save(first).join()
        assertEquals(1, done)
        model.editorDraft = null
        first.resources.dispose(resources)
        val savedPath = ConfigDocument.parse(stored(id).text).value("AccessKey", "IdentityFile")
        assertTrue(savedPath.contains("/profiles/$id/"))

        val draft = open(id)
        val hosts = resources.stage(SshProfileResources.Kind.KNOWN_HOSTS, "imported-hosts".byteInputStream())
        assertTrue(draft.importKey(SshProfileResources.Kind.KNOWN_HOSTS, hosts))
        changeElsewhere(id) { set("Profile", "Name", "Theirs") }
        save(draft).join()
        val placeholder = draft.document.value("AccessKey", "KnownHostsFile")
        assertTrue(placeholder.startsWith(ProfileMerge.IMPORT_PREFIX))
        assertNotNull(draft.conflict)
        model.saveAsNew("saved as new") { done++ }.join()
        assertEquals(2, done)
        val created = store.load().first.single { it.id != id }
        val document = ConfigDocument.parse(created.text)
        val identity = document.value("AccessKey", "IdentityFile")
        val knownHosts = document.value("AccessKey", "KnownHostsFile")
        assertTrue(identity.contains("/profiles/${created.id}/"))
        assertTrue(knownHosts.contains("/profiles/${created.id}/"))
        assertEquals("saved-identity", File(identity).readText())
        assertEquals("imported-hosts", File(knownHosts).readText())
        // The editor's own draft still owns its import; the original profile keeps its saved file.
        assertEquals(placeholder, draft.document.value("AccessKey", "KnownHostsFile"))
        assertTrue(File(savedPath).exists())
        model.editorDraft = null
        draft.resources.dispose(resources)
        assertTrue(File(identity).exists())
        assertTrue(File(knownHosts).exists())
        assertEquals(setOf(File(identity).parentFile.name, File(knownHosts).parentFile.name),
            File(resourcesRoot, "profiles/${created.id}").list()!!.toSet())
    }

    @Test fun failedSaveAsNewKeepsTheDraftAndReleasesItsCopies() = scenario {
        val id = create(ssh)
        val draft = open(id)
        val key = resources.stage(SshProfileResources.Kind.IDENTITY, "identity".byteInputStream())
        assertTrue(draft.importKey(SshProfileResources.Kind.IDENTITY, key))
        draft.set("Profile", "Name", "")   // invalid name: the create fails validation
        model.saveAsNew("saved as new") { done++ }.join()
        assertEquals(0, done)
        assertNotNull(model.error.value)
        assertSame(draft, model.editorDraft)
        assertEquals(1, store.load().first.size)
        assertTrue(key.file.exists())
        assertTrue("copies were released", File(resourcesRoot, "staging").list().orEmpty().size == 1)
        draft.resources.dispose(resources)
    }

    @Test fun mineSshImportSurvivesRepeatedRebasesAndIsSaved() = scenario {
        val id = create(ssh)
        val draft = open(id)
        val key = resources.stage(SshProfileResources.Kind.IDENTITY, "identity".byteInputStream())
        assertTrue(draft.importKey(SshProfileResources.Kind.IDENTITY, key))
        changeElsewhere(id) { set("Interface", "MTU", "1500") }
        save(draft).join()
        val placeholder = ConfigDocument.parse(changed(draft).mine).value("AccessKey", "IdentityFile")
        assertTrue(placeholder.startsWith(ProfileMerge.IMPORT_PREFIX))
        // The failed save published the key; another save of this profile must not prune that generation.
        val published = checkNotNull(key.prepared).path
        changeElsewhere(id) { set("Interface", "DNS", "9.9.9.9") }
        assertTrue(File(published).exists())
        assertTrue(draft.applyMerge(emptyMap()))
        assertEquals(placeholder, draft.document.value("AccessKey", "IdentityFile"))
        // Theirs changed again after the rebase: the next save rebases again and keeps the import.
        save(draft).join()
        assertEquals(0, done)
        assertTrue(draft.applyMerge(emptyMap()))
        assertEquals(placeholder, draft.document.value("AccessKey", "IdentityFile"))
        save(draft).join()
        assertEquals(1, done)
        val saved = ConfigDocument.parse(stored(id).text)
        assertEquals(published, saved.value("AccessKey", "IdentityFile"))
        assertEquals("identity", File(published).readText())
        assertEquals("9.9.9.9", saved.value("Interface", "DNS"))
        assertEquals("1500", saved.value("Interface", "MTU"))
    }

    @Test fun draftReplacedWhileTheRebaseIsComputedGetsNoConflict() = scenario {
        val id = create(ss("A"))
        val draft = open(id)
        changeElsewhere(id) { set("Profile", "Name", "Theirs") }
        draft.set("Profile", "Name", "Mine")
        val gate = Gate(); loadHook = { gate.await() }
        val saving = save(draft)
        gate.entered.await()
        val replacement = open(id)
        loadHook = {}
        gate.release.complete(Unit); saving.join()
        assertNull(draft.conflict)
        assertNull(replacement.conflict)
        assertSame(replacement, model.editorDraft)
        model.editorDraft = null
        assertNull(model.editorDraft)
        draft.resources.dispose(resources)
        replacement.resources.dispose(resources)
    }

    @Test fun closedEditorIgnoresALateReloadAndSaveAsNew() = scenario {
        val id = create(ss("A"))
        val draft = open(id)
        changeElsewhere(id) { set("Profile", "Name", "Theirs") }
        draft.set("Profile", "Name", "Mine")
        save(draft).join()
        val gate = Gate(); loadHook = { gate.await() }
        val refresh = model.refresh(); gate.entered.await()
        val reload = model.reloadLatest()
        val saveNew = model.saveAsNew("saved as new") { done++ }
        model.editorDraft = null
        loadHook = {}
        gate.release.complete(Unit); refresh.join(); reload.join(); saveNew.join()
        assertEquals(0, done)
        assertEquals("Mine", draft.document.value("Profile", "Name"))
        assertEquals(1, store.load().first.size)
    }
}
