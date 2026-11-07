package org.bimeron.app

import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SshProfileStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    /** The production layout: profiles beside `ssh-resources` under one canonical files root. */
    private fun profiles() = File(temporary.root.canonicalFile, "profiles").apply { mkdirs() }
    @Test fun failedRenamePreservesOldResourcesAndRetryCommitsBothKeys() = runBlocking {
        val directory = profiles()
        val resources = SshProfileResources(File(temporary.root.canonicalFile, "ssh-resources"))
        val store = ProfileStore(null, directory, resources) {}
        val id = UUID.randomUUID()
        val old = resources.prepare(id, resources.stage(SshProfileResources.Kind.IDENTITY, "old".byteInputStream()))
        val target = File(directory, "$id.conf")
        // A nonempty directory forces rename failure on a real filesystem.
        target.mkdir(); File(target, "old.conf").writeText("old config")
        val draft = SshDraftResources(id)
        val a = resources.stage(SshProfileResources.Kind.IDENTITY, "new".byteInputStream())
        val b = resources.stage(SshProfileResources.Kind.KNOWN_HOSTS, "hosts".byteInputStream())
        val source = Conf.fromKey("Test", "ssh://user@example.com:22") +
            "\n" // resource fields are explicit additions, never implicit file reads
        val text = ConfigDocument.parse(source).set("AccessKey", "IdentityFile", draft.accept(a)!!)
            .set("AccessKey", "KnownHostsFile", draft.accept(b)!!).render()
        try { store.save(text, id.toString(), draft); fail() } catch (_: ProfileError) {}
        assertEquals("old config", File(target, "old.conf").readText())
        assertTrue(File(old.path).exists())
        assertTrue(File(a.prepared!!.path).exists())
        assertTrue(File(b.prepared!!.path).exists())
        File(target, "old.conf").delete(); target.delete()
        val result = store.save(text, id.toString(), draft)
        assertEquals(id.toString(), result.id)
        assertEquals(result.text, target.readText())
        assertFalse(File(old.path).exists())
        assertTrue(File(a.prepared!!.path).exists())
        assertTrue(File(b.prepared!!.path).exists())
        // Failed config deletion cannot grant resource deletion authority.
        target.delete(); target.mkdir(); File(target, "block").writeText("x")
        try { store.delete(id.toString()); fail() } catch (_: ProfileError) {}
        assertTrue(File(a.prepared!!.path).exists())
        File(target, "block").delete(); target.delete(); target.writeText(result.text)
        store.delete(id.toString())
        assertFalse(File(a.prepared!!.path).exists())
    }

    @Test fun cancellationAtCommittedRenameRetainsBothKeysThroughDisposalAndReload() = runBlocking {
        val directory = profiles()
        val resources = SshProfileResources(File(temporary.root.canonicalFile, "ssh-resources"))
        val store = ProfileStore(null, directory, resources) {}
        val id = UUID.randomUUID()
        val old = resources.prepare(id, resources.stage(SshProfileResources.Kind.IDENTITY, "old".byteInputStream()))
        val draft = SshDraftResources(id)
        val identity = resources.stage(SshProfileResources.Kind.IDENTITY, "key".byteInputStream())
        val hosts = resources.stage(SshProfileResources.Kind.KNOWN_HOSTS, "hosts".byteInputStream())
        val text = ConfigDocument.parse(Conf.fromKey("Test", "ssh://user@example.com:22"))
            .set("AccessKey", "IdentityFile", draft.accept(identity)!!)
            .set("AccessKey", "KnownHostsFile", draft.accept(hosts)!!).render()
        lateinit var saving: Job
        store.renameProfile = { source, target ->
            source.renameTo(target).also { renamed ->
                assertTrue(renamed)
                saving.cancel()
            }
        }
        saving = launch(start = CoroutineStart.LAZY) { store.save(text, id.toString(), draft) }
        saving.start()
        saving.join()
        assertTrue(saving.isCancelled)
        draft.dispose(resources)
        val loaded = store.load()
        assertFalse(loaded.second)
        val committed = loaded.first.single()
        assertEquals(id.toString(), committed.id)
        assertEquals(setOf(identity.prepared!!.path, hosts.prepared!!.path), SshDraftResources.paths(committed.text))
        assertEquals("key", File(identity.prepared!!.path).readText())
        assertEquals("hosts", File(hosts.prepared!!.path).readText())
        assertFalse(File(old.path).exists())
    }

    @Test fun cancellationDuringValidationRetainsPublishedRetryBytes() = runBlocking {
        val directory = profiles()
        val resources = SshProfileResources(File(temporary.root.canonicalFile, "ssh-resources"))
        val id = UUID.randomUUID()
        val draft = SshDraftResources(id)
        val token = resources.stage(SshProfileResources.Kind.IDENTITY, "key".byteInputStream())
        val text = ConfigDocument.parse(Conf.fromKey("Test", "ssh://user@example.com:22"))
            .set("AccessKey", "IdentityFile", draft.accept(token)!!).render()
        val store = ProfileStore(null, directory, resources) { throw CancellationException() }
        try { store.save(text, id.toString(), draft); fail() } catch (_: CancellationException) {}
        assertFalse(File(directory, "$id.conf").exists())
        assertTrue(File(token.prepared!!.path).exists())
        draft.dispose(resources)
        assertFalse(File(token.prepared!!.path).exists())
    }

    /**
     * Decision 8: a save with an unowned path is refused before anything is written, and the draft keeps its
     * published import for a retry. A legacy stored profile with such a path still deletes its own key.
     */
    @Test fun unownedKnownHostsIsRefusedAndLegacyDeleteRemovesSavedKey() = runBlocking {
        val resources = SshProfileResources(File(temporary.root.canonicalFile, "ssh-resources"))
        val directory = profiles()
        val store = ProfileStore(null, directory, resources) {}
        val id = UUID.randomUUID()
        val draft = SshDraftResources(id)
        val placeholder = draft.accept(resources.stage(SshProfileResources.Kind.IDENTITY, "k".byteInputStream()))!!
        val base = ConfigDocument.parse(Conf.fromKey("Ssh", "ssh://user@example.com:22")).set("AccessKey", "IdentityFile", placeholder)
        val refused = runCatching { store.compareAndSave(UUID.randomUUID(), id, null, base.set("AccessKey", "KnownHostsFile", "/tmp/known_hosts").render(), draft) }
        assertEquals(R.string.ssh_files_not_imported, (refused.exceptionOrNull() as ProfileError).resId)
        assertFalse(File(directory, "$id.conf").exists())
        val result = store.compareAndSave(UUID.randomUUID(), id, null, base.render(), draft)
        assertTrue("$result", result is CommitResult.Committed)
        draft.dispose(resources)
        val key = ConfigDocument.parse(store.load().first.single().text).value("AccessKey", "IdentityFile")
        // Written by an older version, before save-time validation.
        File(directory, "$id.conf").writeText(ConfigDocument.parse(File(directory, "$id.conf").readText())
            .set("AccessKey", "KnownHostsFile", "/tmp/known_hosts").render())
        val stored = store.load().first.single()
        assertEquals("/tmp/known_hosts", ConfigDocument.parse(stored.text).value("AccessKey", "KnownHostsFile"))
        assertTrue(File(key).exists())
        assertTrue(store.compareAndDelete(UUID.randomUUID(), id, stored.revision) is CommitResult.Committed)
        assertFalse("private key of a deleted profile left on disk", File(key).exists())
    }
}
