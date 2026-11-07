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
        /** Runs inside the rebase/readLatest calls, after the store read (and merge) completed. */
        var rebaseHook: suspend () -> Unit = {}
        var readHook: suspend () -> Unit = {}
        var done = 0
        var stops = 0
        val commits = EditorCommits { store }
        val model = AppModelOperations(scope,
            { loadHook(); val (items, warning) = store.load(); commits.loaded(items); items.map { it.profile } to warning },
            { _, _ -> error("legacy save") }, { error("legacy delete") }, { null }, {}, runtime, { stops++; runtime.value = VpnState() }, {},
            saveCommitted = { text, id, draft, publish -> commits.save(text, id, draft as? EditorDraft, publish) },
            deleteCommitted = { id, draft, publish -> commits.delete(id, draft as? EditorDraft, publish) },
            checkDelete = { id, draft -> commits.checkDelete(id, draft as? EditorDraft) },
            rebase = { id, text, draft -> commits.rebase(id, text, draft).also { rebaseHook() } },
            readLatest = { id -> store.readLatest(UUID.fromString(id)).also { readHook() } },
            saveAsNewCommitted = { text, draft, publish -> commits.saveAsNew(text, draft, publish) },
            deleteLatest = { id, expected, publish -> commits.deleteAt(id, expected, publish) },
            strings = { id, args -> when (id) {
                R.string.profile_copy_name -> "${args[0]} (copy)"
                R.string.profile_copy_name_numbered -> "${args[0]} (copy ${args[1]})"
                else -> "#$id"
            } })

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
        assertEquals(EditorDraft.MergeOutcome.NOT_APPLICABLE, draft.applyMerge(emptyMap()))
        val generation = draft.editGeneration
        assertEquals(EditorDraft.MergeOutcome.APPLIED, draft.applyMerge(mapOf(MergeField.Key("Interface", "MTU") to MergeChoice.MINE)))
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
        assertEquals(EditorDraft.MergeOutcome.APPLIED, draft.applyMerge(emptyMap()))
        changeElsewhere(id) { set("Interface", "MTU", "1300").set("Profile", "Name", "Theirs again") }
        save(draft).join()
        assertEquals(0, done)
        val second = changed(draft)
        // The base is now the first rebased text, so only MTU conflicts; DNS is not a change any more.
        assertEquals(listOf(MergeField.Key("Interface", "MTU")), second.merge.conflicts.map { it.field })
        assertEquals(1, second.merge.theirsChanges)
        assertEquals(EditorDraft.MergeOutcome.APPLIED, draft.applyMerge(mapOf(MergeField.Key("Interface", "MTU") to MergeChoice.THEIRS)))
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
        assertEquals(EditorDraft.MergeOutcome.NOT_APPLICABLE, draft.applyMerge(emptyMap()))
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
        assertEquals(EditorDraft.MergeOutcome.NOT_APPLICABLE, draft.applyMerge(emptyMap()))
        // Reloading cannot bring it back either; the panel stays on save-as-new.
        model.reloadLatest().join()
        assertEquals(EditorConflict.Gone(deleted = true), draft.conflict)
        assertEquals("Rescued", draft.document.value("Profile", "Name"))
        model.saveAsNew("saved as new") { done++ }.join()
        assertEquals(1, done)
        assertNull(model.error.value)
        val created = store.load().first.single()
        assertNotEquals(id, created.id)
        assertEquals("Rescued (copy)", created.name)
        assertEquals(ConfigDocument.parse(draft.source.text).set("Profile", "Name", "Rescued (copy)").render(), created.text)
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
        assertEquals("Mine (copy)", store.load().first.single { it.id != id }.name)
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
        draft.set("AccessKey", "SOCKS5", "socks5://192.0.2.1:1080")   // a second key source: the create fails validation
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
        assertEquals(EditorDraft.MergeOutcome.APPLIED, draft.applyMerge(emptyMap()))
        assertEquals(placeholder, draft.document.value("AccessKey", "IdentityFile"))
        // Theirs changed again after the rebase: the next save rebases again and keeps the import.
        save(draft).join()
        assertEquals(0, done)
        assertEquals(EditorDraft.MergeOutcome.APPLIED, draft.applyMerge(emptyMap()))
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

    @Test fun saveAsNewAfterDeleteKeepsTheSavedSshKeyOfTheBase() = scenario {
        val id = create(ssh)
        val first = open(id)
        assertTrue(first.importKey(SshProfileResources.Kind.IDENTITY, resources.stage(SshProfileResources.Kind.IDENTITY, "saved-key".byteInputStream())))
        save(first).join()
        assertEquals(1, done)
        model.editorDraft = null; first.resources.dispose(resources)
        val draft = open(id)
        val savedKey = draft.document.value("AccessKey", "IdentityFile")
        assertTrue(store.compareAndDelete(UUID.randomUUID(), UUID.fromString(id), stored(id).revision) is CommitResult.Committed)
        assertTrue("the open draft pins its base's saved key", File(savedKey).exists())
        draft.set("Profile", "Name", "Rescued")
        save(draft).join()
        assertEquals(EditorConflict.Gone(deleted = true), draft.conflict)
        model.saveAsNew("saved as new") { done++ }.join()
        assertEquals("save-as-new error: ${model.error.value}", 2, done)
        val created = store.load().first.single()
        assertEquals("saved-key", File(ConfigDocument.parse(created.text).value("AccessKey", "IdentityFile")).readText())
        // Releasing the pin removes the deleted profile's key and its now empty directory.
        model.editorDraft = null; draft.resources.dispose(resources)
        assertFalse(File(savedKey).exists())
        assertFalse(File(resourcesRoot, "profiles/$id").exists())
    }

    @Test fun saveAsNewKeepsMineKeyWhenTheirsReplacedIt() = scenario {
        val id = create(ssh)
        val first = open(id)
        assertTrue(first.importKey(SshProfileResources.Kind.IDENTITY, resources.stage(SshProfileResources.Kind.IDENTITY, "old-key".byteInputStream())))
        save(first).join()
        model.editorDraft = null; first.resources.dispose(resources)
        val draft = open(id)
        val oldKey = draft.document.value("AccessKey", "IdentityFile")
        // Another editor replaces the key; its save prunes generations it no longer references.
        val other = EditorDraft(stored(id).profile, stored(id).revision)
        assertTrue(other.importKey(SshProfileResources.Kind.IDENTITY, resources.stage(SshProfileResources.Kind.IDENTITY, "new-key".byteInputStream())))
        val theirs = other.resources.resolve(other.source.text, resources)
        assertTrue(store.compareAndSave(UUID.randomUUID(), UUID.fromString(id), stored(id).revision, other.source.text, other.resources) is CommitResult.Committed)
        other.resources.dispose(resources)
        assertTrue("pinned by the open draft", File(oldKey).exists())
        draft.set("Profile", "Name", "Mine")
        save(draft).join()
        assertNotNull(draft.conflict as? EditorConflict.Changed)
        model.saveAsNew("saved as new") { done++ }.join()
        assertEquals("save-as-new error: ${model.error.value}", 2, done)
        val created = store.load().first.single { it.id != id }
        assertEquals("old-key", File(ConfigDocument.parse(created.text).value("AccessKey", "IdentityFile")).readText())
        assertEquals(ConfigDocument.parse(theirs).value("AccessKey", "IdentityFile"), ConfigDocument.parse(stored(id).text).value("AccessKey", "IdentityFile"))
        model.editorDraft = null; draft.resources.dispose(resources)
        assertFalse("the replaced key goes once no draft pins it", File(oldKey).exists())
        assertEquals("new-key", File(ConfigDocument.parse(stored(id).text).value("AccessKey", "IdentityFile")).readText())
    }

    /** Another editor saves a new IdentityFile [content] for [id]; returns the saved generation's path. */
    private suspend fun Fixture.replaceKeyElsewhere(id: String, content: String): String {
        val other = EditorDraft(stored(id).profile, stored(id).revision)
        assertTrue(other.importKey(SshProfileResources.Kind.IDENTITY, resources.stage(SshProfileResources.Kind.IDENTITY, content.byteInputStream())))
        assertTrue(store.compareAndSave(UUID.randomUUID(), UUID.fromString(id), stored(id).revision, other.source.text, other.resources) is CommitResult.Committed)
        other.resources.dispose(resources)
        return ConfigDocument.parse(stored(id).text).value("AccessKey", "IdentityFile").also { assertEquals(content, File(it).readText()) }
    }

    /**
     * Opens a draft of an SSH profile without a saved key, lets theirs save key generation G2 and rebases
     * the draft onto it ([reload] or a conflict-free merge). Only the rebase pins G2: the draft's opening
     * base never referenced it. Returns the draft and G2's path.
     */
    private suspend fun Fixture.draftRebasedOntoTheirsKey(id: String, reload: Boolean): Pair<EditorDraft, String> {
        val draft = open(id)
        assertEquals("", draft.document.value("AccessKey", "IdentityFile"))
        val g2 = replaceKeyElsewhere(id, "theirs-key")
        draft.set("Profile", "Name", "Mine")
        save(draft).join()
        assertTrue(changed(draft).merge.conflicts.isEmpty())
        if (reload) { model.reloadLatest().join(); draft.set("Profile", "Name", "Mine") }
        else assertEquals(EditorDraft.MergeOutcome.APPLIED, draft.applyMerge(emptyMap()))
        assertEquals(stored(id).revision, draft.baseRevision)
        assertEquals(g2, draft.document.value("AccessKey", "IdentityFile"))
        return draft to g2
    }

    private suspend fun Fixture.saveAsNewKeepsTheirsKey(draft: EditorDraft, g2: String) {
        model.saveAsNew("saved as new") { done++ }.join()
        assertEquals("save-as-new error: ${model.error.value}", 1, done)
        val created = store.load().first.single { it.id != draft.profile.id }
        assertEquals("theirs-key", File(ConfigDocument.parse(created.text).value("AccessKey", "IdentityFile")).readText())
        model.editorDraft = null; draft.resources.dispose(resources)
        assertFalse("G2 goes once the rebased draft releases it", File(g2).exists())
    }

    @Test fun saveAsNewAfterARebaseKeepsTheRebasedKeyWhenTheirsThenDeletes() = scenario {
        val id = create(ssh)
        val (draft, g2) = draftRebasedOntoTheirsKey(id, reload = false)
        assertTrue(store.compareAndDelete(UUID.randomUUID(), UUID.fromString(id), stored(id).revision) is CommitResult.Committed)
        assertTrue("the rebased draft pins G2", File(g2).exists())
        save(draft).join()
        assertEquals(EditorConflict.Gone(deleted = true), draft.conflict)
        saveAsNewKeepsTheirsKey(draft, g2)
    }

    @Test fun saveAsNewAfterAReloadKeepsTheReloadedKeyWhenTheirsThenDeletes() = scenario {
        val id = create(ssh)
        val (draft, g2) = draftRebasedOntoTheirsKey(id, reload = true)
        assertTrue(store.compareAndDelete(UUID.randomUUID(), UUID.fromString(id), stored(id).revision) is CommitResult.Committed)
        assertTrue("the reloaded draft pins G2", File(g2).exists())
        save(draft).join()
        assertEquals(EditorConflict.Gone(deleted = true), draft.conflict)
        saveAsNewKeepsTheirsKey(draft, g2)
    }

    @Test fun saveAsNewAfterARebaseKeepsTheRebasedKeyWhenTheirsReplacesIt() = scenario {
        val id = create(ssh)
        val (draft, g2) = draftRebasedOntoTheirsKey(id, reload = false)
        val g3 = replaceKeyElsewhere(id, "newer-key")
        assertNotEquals(g2, g3)
        assertTrue("the rebased draft pins G2", File(g2).exists())
        save(draft).join()
        assertNotNull(draft.conflict as? EditorConflict.Changed)
        saveAsNewKeepsTheirsKey(draft, g2)
        assertEquals(g3, ConfigDocument.parse(stored(id).text).value("AccessKey", "IdentityFile"))
        assertEquals("newer-key", File(g3).readText())
    }

    @Test fun invalidMergeIsNotApplied() = scenario {
        val id = create(ss("A"))
        val draft = open(id)
        // Theirs changes the SS key; mine switches to SOCKS5. Keeping theirs' SS would leave two key sources.
        changeElsewhere(id) { set("AccessKey", "SS", "ss://YWVzLTEyOC1nY206b3RoZXI@example.com:443") }
        draft.edit(draft.document.remove("AccessKey", "SS").set("AccessKey", "SOCKS5", "socks5://192.0.2.1:1080"))
        save(draft).join()
        val field = MergeField.Key("AccessKey", "SS")
        assertEquals(listOf(field), changed(draft).merge.conflicts.map { it.field })
        val base = draft.baseRevision
        val text = draft.source.text
        assertEquals(EditorDraft.MergeOutcome.INVALID, draft.applyMerge(mapOf(field to MergeChoice.THEIRS)))
        assertEquals(base, draft.baseRevision)
        assertEquals(text, draft.source.text)
        assertNotNull(draft.conflict)
        assertEquals(EditorDraft.MergeOutcome.APPLIED, draft.applyMerge(mapOf(field to MergeChoice.MINE)))
        save(draft).join()
        assertEquals(1, done)
        assertEquals("socks5://192.0.2.1:1080", ConfigDocument.parse(stored(id).text).value("AccessKey", "SOCKS5"))
    }

    @Test fun rebaseResultForAnEditedOrReplacedDraftIsDropped() = scenario {
        val id = create(ss("A"))
        val draft = open(id)
        changeElsewhere(id) { set("Profile", "Name", "Theirs") }
        draft.set("Profile", "Name", "Mine")
        // Edited while the merge is being returned.
        var gate = Gate(); rebaseHook = { gate.await() }
        var saving = save(draft)
        gate.entered.await()
        draft.set("Profile", "Name", "Mine, edited")
        model.error.value = "newer"
        gate.release.complete(Unit); saving.join()
        assertNull(draft.conflict)
        assertEquals("newer", model.error.value)
        // Replaced while the merge is being returned.
        gate = Gate(); rebaseHook = { gate.await() }
        saving = save(draft)
        gate.entered.await()
        val replacement = open(id)
        gate.release.complete(Unit); saving.join()
        assertNull(draft.conflict)
        assertNull(replacement.conflict)
        draft.resources.dispose(resources)
        replacement.resources.dispose(resources)
    }

    @Test fun reloadResultForAnEditedOrReplacedDraftIsDropped() = scenario {
        val id = create(ss("A"))
        val draft = open(id)
        changeElsewhere(id) { set("Profile", "Name", "Theirs") }
        draft.set("Profile", "Name", "Mine")
        save(draft).join()
        val base = draft.baseRevision
        var gate = Gate(); readHook = { gate.await() }
        var reload = model.reloadLatest()
        gate.entered.await()
        draft.set("Profile", "Name", "Mine, edited")
        gate.release.complete(Unit); reload.join()
        assertEquals("Mine, edited", draft.document.value("Profile", "Name"))
        assertEquals(base, draft.baseRevision)
        gate = Gate(); readHook = { gate.await() }
        reload = model.reloadLatest()
        gate.entered.await()
        val replacement = open(id)
        gate.release.complete(Unit); reload.join()
        assertEquals("Mine, edited", draft.document.value("Profile", "Name"))
        assertEquals(base, draft.baseRevision)
        assertSame(replacement, model.editorDraft)
        draft.resources.dispose(resources)
        replacement.resources.dispose(resources)
    }

    @Test fun saveAsNewNamesTheCopyAfterTheTakenNames() = scenario {
        val id = create(ss("Office"))
        create(ss("Office (copy)"))
        val draft = open(id)
        changeElsewhere(id) { set("Interface", "MTU", "1500") }
        draft.set("Interface", "DNS", "8.8.8.8")
        save(draft).join()
        model.saveAsNew("saved as new") { done++ }.join()
        assertEquals(1, done)
        val names = store.load().first.map { it.name }.toSet()
        assertEquals(setOf("Office", "Office (copy)", "Office (copy 2)"), names)
        assertEquals("8.8.8.8", ConfigDocument.parse(store.load().first.single { it.name == "Office (copy 2)" }.text).value("Interface", "DNS"))
    }

    /** Decision: Cancel keeps the draft and its old base; the next Done merges again. */
    @Test fun cancelKeepsTheOldBaseAndTheNextSaveMergesAgain() = scenario {
        val id = create(ss("A"))
        val draft = open(id)
        val opened = draft.baseRevision
        changeElsewhere(id) { set("Interface", "DNS", "9.9.9.9") }
        draft.set("Interface", "MTU", "1400")
        save(draft).join()
        val first = changed(draft)
        draft.conflict = null
        assertEquals(opened, draft.baseRevision)
        assertEquals("1400", draft.document.value("Interface", "MTU"))
        save(draft).join()
        assertEquals(0, done)
        val second = changed(draft)
        assertNotSame(first, second)
        assertEquals(first.latest.revision, second.latest.revision)
        assertEquals(opened, draft.baseRevision)
        assertEquals(EditorDraft.MergeOutcome.APPLIED, draft.applyMerge(emptyMap()))
        save(draft).join()
        assertEquals(1, done)
    }

    @Test fun deleteAfterAChangeElsewhereAsksAndDeletesAnyway() = scenario {
        val id = create(ss("A"))
        val draft = open(id)
        changeElsewhere(id) { set("Profile", "Name", "Theirs") }
        model.delete(id) { done++ }.join()
        assertEquals(0, done)
        assertNull(model.error.value)
        assertEquals(EditorConflict.ChangedBeforeDelete, draft.conflict)
        assertEquals("Theirs", stored(id).name)
        // Cancel leaves everything as it is.
        draft.conflict = null
        model.delete(id) { done++ }.join()
        assertEquals(EditorConflict.ChangedBeforeDelete, draft.conflict)
        model.deleteAnyway { done++ }.join()
        assertEquals(1, done)
        assertNull(model.error.value)
        assertTrue(store.load().first.none { it.id == id })
        assertTrue(model.profiles.value.none { it.id == id })
    }

    @Test fun deleteAnywayAsksAgainWhenTheProfileChangesInBetween() = scenario {
        val id = create(ss("A"))
        val draft = open(id)
        changeElsewhere(id) { set("Profile", "Name", "Theirs") }
        model.delete(id) { done++ }.join()
        assertEquals(EditorConflict.ChangedBeforeDelete, draft.conflict)
        // Another change lands between the re-read and the compare-and-delete.
        readHook = { readHook = {}; changeElsewhere(id) { set("Profile", "Name", "Again") } }
        model.deleteAnyway { done++ }.join()
        assertEquals(0, done)
        assertEquals(EditorConflict.ChangedBeforeDelete, draft.conflict)
        assertEquals("Again", stored(id).name)
        model.deleteAnyway { done++ }.join()
        assertEquals(1, done)
        assertTrue(store.load().first.none { it.id == id })
    }

    @Test fun deleteOfAProfileAlreadyDeletedElsewhereIsDone() = scenario {
        val id = create(ss("A"))
        val draft = open(id)
        assertTrue(store.compareAndDelete(UUID.randomUUID(), UUID.fromString(id), stored(id).revision) is CommitResult.Committed)
        model.delete(id) { done++ }.join()
        assertEquals(1, done)
        assertNull(model.error.value)
        assertNull(draft.conflict)
        // Also when it disappears after "Delete anyway" was confirmed.
        val other = create(ss("B"))
        val second = open(other)
        changeElsewhere(other) { set("Profile", "Name", "Theirs") }
        model.delete(other) { done++ }.join()
        assertEquals(EditorConflict.ChangedBeforeDelete, second.conflict)
        assertTrue(store.compareAndDelete(UUID.randomUUID(), UUID.fromString(other), stored(other).revision) is CommitResult.Committed)
        model.deleteAnyway { done++ }.join()
        assertEquals(2, done)
        assertNull(model.error.value)
    }

    @Test fun deleteOfAnUnreadableProfileKeepsTheError() = scenario {
        val id = create(ss("A"))
        val draft = open(id)
        File(profilesDir, "$id.conf").writeText("[broken")
        model.delete(id) { done++ }.join()
        assertEquals(0, done)
        assertEquals("#${R.string.profile_edit_conflict}", model.error.value)
        assertNull(draft.conflict)
        assertTrue(File(profilesDir, "$id.conf").exists())
    }

    @Test fun deleteAnywayStopsTheActiveVpnFirst() = scenario {
        val id = create(ss("A"))
        val draft = open(id)
        changeElsewhere(id) { set("Profile", "Name", "Theirs") }
        runtime.value = VpnState(state = "connected", activeProfileId = id, connectedAtElapsed = 1)
        model.delete(id) { done++ }.join()
        assertEquals(EditorConflict.ChangedBeforeDelete, draft.conflict)
        val stopsBefore = stops
        runtime.value = VpnState(state = "connected", activeProfileId = id, connectedAtElapsed = 2)
        model.deleteAnyway { done++ }.join()
        assertEquals(stopsBefore + 1, stops)
        assertEquals(1, done)
        assertTrue(store.load().first.none { it.id == id })
    }

    /** The delete path stops the VPN exactly when the confirmations warn about it ([deleteStopsVpn]). */
    @Test fun deleteStopsTheVpnWhileTheProfileIsBeingConnected() = scenario {
        val id = create(ss("A"))
        val other = create(ss("B"))
        runtime.value = VpnState(state = "connecting", attemptProfileId = other)
        open(id)
        model.delete(id) { done++ }.join()
        assertEquals(0, stops)
        val draft = open(other)
        runtime.value = VpnState(state = "connecting", attemptProfileId = other)
        assertTrue(deleteStopsVpn(runtime.value, draft.profile.id))
        model.delete(other) { done++ }.join()
        assertEquals(1, stops)
        assertEquals(2, done)
        assertTrue(store.load().first.isEmpty())
    }
}
