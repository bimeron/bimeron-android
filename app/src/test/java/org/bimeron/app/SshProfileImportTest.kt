package org.bimeron.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Creating a profile from outside text (a `.conf` import, a link, an added key) with decision 8: SSH file
 * fields the new profile cannot own are cleared before the save and the user is told to import the files
 * in the editor; `ssh://` links with file parameters are refused. The store check itself stays strict.
 */
class SshProfileImportTest {
    @get:Rule val temp = TemporaryFolder()
    private val cleared = "#${R.string.profile_imported_ssh_files_cleared}"
    private val uriRefused = "#${R.string.ssh_import_uri_files}"

    private inner class Fixture(parent: CoroutineScope) {
        val job = SupervisorJob(parent.coroutineContext[Job])
        val scope = CoroutineScope(parent.coroutineContext + job)
        val root = File(temp.root.canonicalFile, "files").apply { mkdirs() }
        val resources = SshProfileResources(File(root, "ssh-resources"))
        val store = ProfileStore(null, File(root, "profiles").apply { mkdirs() }, resources) { /* Core accepts anything */ }
        val commits = EditorCommits { store }
        val model = AppModelOperations(scope,
            { val (items, warning) = store.load(); commits.loaded(items); items.map { it.profile } to warning },
            { _, _ -> error("legacy save") }, { error("legacy delete") }, { null }, {}, MutableStateFlow(VpnState()), {}, {},
            saveCommitted = { text, id, draft, publish -> commits.save(text, id, draft as? EditorDraft, publish) })

        /** Imports [text] like MainActivity and AppModel.addKey do: a creation with no id. */
        suspend fun import(text: String) = model.save(text, null, "imported") {}.join()
        suspend fun texts() = store.load().first.map { it.text }
    }

    private fun scenario(block: suspend Fixture.() -> Unit) = runBlocking {
        val f = Fixture(this)
        try { withTimeout(10_000) { f.model.refresh().join(); f.block() } } finally { f.job.cancelAndJoin() }
    }

    private fun ssh(name: String) = ConfigDocument.parse(
        "[Profile]\nVersion = 1\nName = $name\n\n[AccessKey]\nType = ssh\nServer = ssh.example.com\nPort = 22\nUsername = u\n").render()

    @Test fun exportedSshProfileImportsWithItsFilesClearedAndANotice() = scenario {
        // An SSH profile with both files imported, saved and exported (backup or a new device).
        val id = UUID.randomUUID()
        val draft = SshDraftResources(id)
        val text = ConfigDocument.parse(ssh("Backup"))
            .set("AccessKey", "IdentityFile", draft.accept(resources.stage(SshProfileResources.Kind.IDENTITY, "key".byteInputStream()))!!)
            .set("AccessKey", "KnownHostsFile", draft.accept(resources.stage(SshProfileResources.Kind.KNOWN_HOSTS, "hosts".byteInputStream()))!!)
            .render()
        assertTrue(store.compareAndSave(UUID.randomUUID(), id, null, text, draft) is CommitResult.Committed)
        draft.dispose(resources)
        val exported = ByteArrayOutputStream().also { out -> writeProfileExport(store.load().first.single().text) { out } }.toString(Charsets.UTF_8.name())
        assertTrue(exported.contains("ssh-resources/profiles/$id/"))

        import(Conf.ensureName(exported, "Backup"))
        assertNull(model.error.value)
        assertEquals(cleared, model.notice.value)
        val imported = store.load().first.single { it.id != id.toString() }
        val document = ConfigDocument.parse(imported.text)
        assertEquals("", document.value("AccessKey", "IdentityFile"))
        assertEquals("", document.value("AccessKey", "KnownHostsFile"))
        assertEquals("ssh.example.com", document.value("AccessKey", "Server"))
        assertTrue(SshFileGuard.insideApp(imported.text, UUID.fromString(imported.id), root))
        assertEquals(2, model.profiles.value.size)
        model.clearNotice()
        assertNull(model.notice.value)
    }

    @Test fun typedOutsidePathsAndOtherKeyCasesAreClearedOnImport() = scenario {
        val text = ssh("Typed").replace("Username = u\n", "Username = u\nidentityfile = /sdcard/.ssh/id_rsa\nKnownHostsFile = ~/.ssh/known_hosts\n")
        import(text)
        assertNull(model.error.value)
        assertEquals(cleared, model.notice.value)
        val stored = texts().single()
        assertTrue(SshFileGuard.references(stored)!!.isEmpty())
        assertFalse(stored.contains("/sdcard"))
    }

    @Test fun sshLinksWithFileParametersAreRefusedWithTheImportMessage() = scenario {
        for (link in listOf("ssh://u@h:22?identity=/sdcard/id", "ssh://u@h:22?known_hosts=%2Fsdcard%2Fknown_hosts",
            "ssh://u@h:22?x=1;Identity=~/.ssh/id_rsa", "ssh://u@h:22?identity=%zz")) {
            // An added key (AppModel.addKey) and the same link inside an imported .conf.
            for (text in listOf(Conf.fromKey("Link", link), ssh("Conf").replace("Type = ssh\n", "").replace("Server = ssh.example.com\nPort = 22\nUsername = u\n", "Key = $link\n"))) {
                model.clearError()
                import(text)
                assertEquals(link, uriRefused, model.error.value)
                assertNull(link, model.notice.value)
            }
        }
        assertTrue(texts().isEmpty())
    }

    @Test fun sshLinkWithoutFileParametersAndSshWithoutFilesAreUnchanged() = scenario {
        val link = Conf.fromKey("Link", "ssh://u@h:22?x=1")
        import(link)
        import(ssh("Plain"))
        assertNull(model.error.value)
        assertNull(model.notice.value)
        assertEquals(setOf(link, ssh("Plain")), texts().toSet())
    }

    @Test fun nonSshImportsAreUnaffected() = scenario {
        val ss = Conf.fromKey("Shadowsocks", "ss://YWVzLTEyOC1nY206cGFzcw@example.com:443")
        val socks = "[Profile]\nVersion = 1\nName = Socks\n\n[AccessKey]\nType = socks5\nServer = example.com\nPort = 1080\n"
        import(ss)
        import(socks)
        assertNull(model.error.value)
        assertNull(model.notice.value)
        assertEquals(setOf(ss, socks), texts().toSet())
    }

    @Test fun editorSavesOfAnExistingProfileAreNeverStripped() = scenario {
        val id = UUID.randomUUID()
        val legacy = ssh("Legacy").replace("Username = u\n", "Username = u\nIdentityFile = /sdcard/id\n")
        File(root, "profiles/$id.conf").writeText(legacy)
        model.refresh().join()
        val profile = model.profiles.value.single()
        val draft = EditorDraft(profile, commits.baseRevision(profile)).also { model.editorDraft = it }
        draft.set("AccessKey", "Username", "v")
        model.save(draft.source.text, profile.id, "saved") {}.join()
        assertEquals("#${R.string.ssh_files_not_imported}", model.error.value)
        assertNull(model.notice.value)
        assertEquals(legacy, texts().single())
        draft.resources.dispose(resources)
    }

    @Test fun storeCheckStaysStrictForCreations() = runBlocking {
        val root = File(temp.root.canonicalFile, "strict").apply { mkdirs() }
        val store = ProfileStore(null, File(root, "profiles").apply { mkdirs() }, SshProfileResources(File(root, "ssh-resources"))) {}
        val text = ssh("Direct").replace("Username = u\n", "Username = u\nIdentityFile = /sdcard/id\n")
        try { store.compareAndSave(UUID.randomUUID(), UUID.randomUUID(), null, text, null); fail() }
        catch (e: ProfileError) { assertEquals(R.string.ssh_files_not_imported, e.resId) }
        // prepareNewProfile is what clears, and only for the id it is given.
        val id = UUID.randomUUID()
        val prepared = store.prepareNewProfile(text, id)
        assertTrue(prepared.filesCleared)
        assertTrue(store.compareAndSave(UUID.randomUUID(), id, null, prepared.text, null) is CommitResult.Committed)
        val unchanged = store.prepareNewProfile(ssh("None"), id)
        assertFalse(unchanged.filesCleared)
        assertEquals(ssh("None"), unchanged.text)
    }
}
