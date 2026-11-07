package org.bimeron.app

import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Decision 8 at save time: `ProfileStore.validate`, which every save runs through `compareAndSave`, accepts an
 * SSH file reference only when it is one of this profile's imported files under the store's root.
 */
class SshFileSaveValidationTest {
    @get:Rule val temp = TemporaryFolder()
    private val root by lazy { File(temp.root.canonicalFile, "files").apply { mkdirs() } }
    private val directory by lazy { File(root, "profiles").apply { mkdirs() } }
    private val resources by lazy { SshProfileResources(File(root, "ssh-resources")) }
    private val store by lazy { ProfileStore(null, directory, resources) { /* Core accepts anything */ } }
    private val id = UUID.randomUUID()

    private fun typed(identity: String = "", knownHosts: String = "") = ConfigDocument.parse(
        "[Profile]\nVersion = 1\nName = S\n\n[AccessKey]\nType = ssh\nServer = ssh.example.com\nPort = 22\nUsername = u\n")
        .let { if (identity.isNotEmpty()) it.set("AccessKey", "IdentityFile", identity) else it }
        .let { if (knownHosts.isNotEmpty()) it.set("AccessKey", "KnownHostsFile", knownHosts) else it }
        .render()
    private fun key(uri: String) = Conf.fromKey("S", uri)
    private fun generation(owner: UUID, kind: SshProfileResources.Kind) =
        resources.prepare(owner, resources.stage(kind, "${kind.filename} bytes".byteInputStream())).path
    private fun save(text: String, profile: UUID = id, draft: SshDraftResources? = null) =
        runBlocking { store.compareAndSave(UUID.randomUUID(), profile, null, text, draft) }

    private fun refused(text: String, label: String, profile: UUID = id) {
        val validation = runCatching { runBlocking { store.validate(text, profile) } }.exceptionOrNull()
        assertEquals(label, R.string.ssh_files_not_imported, (validation as? ProfileError)?.resId)
        try { save(text, profile); fail("saved: $label") }
        catch (e: ProfileError) { assertEquals(label, R.string.ssh_files_not_imported, e.resId) }
        assertFalse(label, File(directory, "$profile.conf").exists())
    }

    @Test fun outsideAndForeignPathsAreRefused() {
        val identity = generation(id, SshProfileResources.Kind.IDENTITY)
        val knownHosts = generation(id, SshProfileResources.Kind.KNOWN_HOSTS)
        val generationName = File(identity).parentFile!!.name
        val other = generation(UUID.randomUUID(), SshProfileResources.Kind.IDENTITY)
        val outside = listOf("/sdcard/.ssh/id_rsa", "/storage/emulated/0/Download/id_rsa", "~/.ssh/id_rsa", "~", "keys/id_rsa", "id_rsa",
            "/data/local/tmp/identity", other,
            "$root/ssh-resources/profiles/$id/${generationName.uppercase()}/identity",
            "$root/ssh-resources/profiles/$id/$generationName/../$generationName/identity",
            "$root/ssh-resources/staging/$generationName/identity",
            "/other/files/ssh-resources/profiles/$id/$generationName/identity")
        for (path in outside) {
            refused(typed(identity = path), "IdentityFile $path")
            refused(typed(knownHosts = path.replace("identity", "known_hosts")), "KnownHostsFile $path")
        }
        // This profile's directory, but the wrong kind of file.
        refused(typed(identity = knownHosts), "known_hosts in IdentityFile")
        refused(typed(knownHosts = identity), "identity in KnownHostsFile")
        // One good reference does not cover a bad one.
        refused(typed(identity = identity, knownHosts = "/sdcard/known_hosts"), "mixed")
        refused(typed(identity = identity).replace("IdentityFile", "identityfile").replace(identity, "/sdcard/id"), "lowercase key")
    }

    @Test fun sshUriFileParametersAreRefused() {
        for (query in listOf("identity=/sdcard/id", "known_hosts=/sdcard/known_hosts", "identity=%2Fsdcard%2Fid",
            "x=1;identity=/sdcard/id", "Identity=~/.ssh/id_rsa", "identity=%zz", "known_hosts=%", "%zz=1")) {
            refused(key("ssh://u@h:22?$query"), query)
        }
        refused("[Profile]\nVersion = 1\nName = S\n\n[AccessKey]\nSSH = ssh://u@h:22?identity=/sdcard/id\n", "SSH field")
    }

    @Test fun emptyValuesAndOwnFilesPass() {
        assertTrue(save(typed()) is CommitResult.Committed)
        val emptyValues = ConfigDocument.parse(typed()).set("AccessKey", "IdentityFile", "").set("AccessKey", "KnownHostsFile", "").render()
        assertTrue(save(emptyValues, UUID.randomUUID()) is CommitResult.Committed)
        assertTrue(save(key("ssh://u@h:22"), UUID.randomUUID()) is CommitResult.Committed)
        // This profile's existing generations, typed or as ssh:// parameters.
        val own = UUID.randomUUID()
        val identity = generation(own, SshProfileResources.Kind.IDENTITY)
        val knownHosts = generation(own, SshProfileResources.Kind.KNOWN_HOSTS)
        assertTrue(save(typed(identity, knownHosts), own) is CommitResult.Committed)
        val uri = UUID.randomUUID()
        val uriIdentity = generation(uri, SshProfileResources.Kind.IDENTITY)
        assertTrue(save(key("ssh://u@h:22?identity=${uriIdentity.replace("/", "%2F")}"), uri) is CommitResult.Committed)
    }

    @Test fun importPlaceholdersResolveToThisProfilesGenerationAndPass() {
        val draft = SshDraftResources(id)
        val identity = draft.accept(resources.stage(SshProfileResources.Kind.IDENTITY, "key".byteInputStream()))!!
        val knownHosts = draft.accept(resources.stage(SshProfileResources.Kind.KNOWN_HOSTS, "hosts".byteInputStream()))!!
        val text = typed(identity, knownHosts)
        // The editor's Validate and Done check the resolved text the same way.
        runBlocking { store.validate(text, id, draft) }
        assertTrue(save(text, id, draft) is CommitResult.Committed)
        val stored = runBlocking { store.load() }.first.single()
        assertTrue(SshFileGuard.insideApp(stored.text, id, root))
        assertTrue(SshDraftResources.paths(stored.text).all { it.startsWith("$root/ssh-resources/profiles/$id/") })
        // Saving the stored text again (its own generations) still passes.
        assertTrue(runBlocking { store.compareAndSave(UUID.randomUUID(), id, stored.revision, stored.text) } is CommitResult.Committed)
        draft.dispose(resources)
    }

    @Test fun placeholderOfAnotherProfilesDraftIsNotAccepted() {
        val other = SshDraftResources(UUID.randomUUID())
        val placeholder = other.accept(resources.stage(SshProfileResources.Kind.IDENTITY, "key".byteInputStream()))!!
        // Unresolved by this profile's draft, the placeholder is not an imported file of this profile.
        refused(typed(identity = placeholder), "foreign placeholder")
        other.dispose(resources)
    }

    @Test fun saveAsNewCopiesTheFilesAndPasses() = runBlocking {
        val draft = SshDraftResources(id)
        val placeholder = draft.accept(resources.stage(SshProfileResources.Kind.IDENTITY, "key".byteInputStream()))!!
        assertTrue(save(typed(identity = placeholder, knownHosts = generation(id, SshProfileResources.Kind.KNOWN_HOSTS)), id, draft) is CommitResult.Committed)
        draft.dispose(resources)
        val stored = store.load().first.single()
        val editor = EditorDraft(stored.profile, stored.revision)
        val commits = EditorCommits { store }
        commits.loaded(listOf(stored))
        var published: Profile? = null
        commits.saveAsNew(stored.text, editor) { published = it }
        val copy = checkNotNull(published)
        assertTrue(copy.id != id.toString())
        val copied = store.load().first.single { it.id == copy.id }
        val copiedPaths = SshDraftResources.paths(copied.text)
        assertEquals(2, copiedPaths.size)
        assertTrue(copiedPaths.all { it.startsWith("$root/ssh-resources/profiles/${copy.id}/") })
        assertTrue(SshFileGuard.insideApp(copied.text, UUID.fromString(copy.id), root))
        assertEquals("key", File(ConfigDocument.parse(copied.text).value("AccessKey", "IdentityFile")).readText())
        editor.resources.dispose(resources)
    }

    @Test fun saveAsNewKeepsAnOutsidePathAndIsRefused() = runBlocking {
        val legacy = typed(identity = "/sdcard/.ssh/id_rsa")
        File(directory, "$id.conf").writeText(legacy)
        val stored = store.load().first.single()
        val editor = EditorDraft(stored.profile, stored.revision)
        try { EditorCommits { store }.saveAsNew(legacy, editor) { fail("published") }; fail() }
        catch (e: ProfileError) { assertEquals(R.string.ssh_files_not_imported, e.resId) }
        assertEquals(1, store.load().first.size)
        editor.resources.dispose(resources)
    }

    /** Profiles stored before this check still load, list, open and read; only saving them is refused. */
    @Test fun storedOutsidePathsLoadAndOpenButSavingIsRefusedUntilImported() = runBlocking {
        val legacy = typed(identity = "/sdcard/.ssh/id_rsa", knownHosts = "/storage/emulated/0/known_hosts")
        File(directory, "$id.conf").writeText(legacy)
        val (items, warning) = store.load()
        assertFalse(warning)
        val stored = items.single()
        assertEquals(legacy, stored.text)
        assertTrue(store.readLatest(id) is LatestProfile.Present)
        val editor = EditorDraft(stored.profile, stored.revision)
        assertFalse(ProtocolForms.rawOnly(editor.document))
        assertEquals(R.string.ssh_form_file_not_imported, SshProtocolForm.fieldError(editor.document, "IdentityFile", editor.resources))
        editor.set("AccessKey", "Username", "renamed")
        try { store.compareAndSave(UUID.randomUUID(), id, stored.revision, editor.source.text, editor.resources); fail() }
        catch (e: ProfileError) { assertEquals(R.string.ssh_files_not_imported, e.resId) }
        assertEquals(legacy, File(directory, "$id.conf").readText())
        // Importing both files through the draft makes the profile savable.
        editor.importKey(SshProfileResources.Kind.IDENTITY, resources.stage(SshProfileResources.Kind.IDENTITY, "key".byteInputStream()))
        editor.importKey(SshProfileResources.Kind.KNOWN_HOSTS, resources.stage(SshProfileResources.Kind.KNOWN_HOSTS, "hosts".byteInputStream()))
        assertEquals(null, SshProtocolForm.fieldError(editor.document, "IdentityFile", editor.resources))
        val result = store.compareAndSave(UUID.randomUUID(), id, stored.revision, editor.source.text, editor.resources)
        assertTrue("$result", result is CommitResult.Committed)
        assertTrue(SshFileGuard.insideApp(store.load().first.single().text, id, root))
        editor.resources.dispose(resources)
    }

    @Test fun untrustedOrMissingRootRefusesEveryReference() {
        // A store whose profiles are not beside its SSH imports cannot accept any reference.
        val elsewhere = ProfileStore(null, temp.newFolder("elsewhere"), resources) {}
        val identity = generation(id, SshProfileResources.Kind.IDENTITY)
        try { runBlocking { elsewhere.validate(typed(identity = identity), id) }; fail() }
        catch (e: ProfileError) { assertEquals(R.string.ssh_files_not_imported, e.resId) }
        runBlocking { elsewhere.validate(typed(), id) }
    }
}
