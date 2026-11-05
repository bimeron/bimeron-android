package org.bimeron.app

import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SshDraftResourcesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun resources() = SshProfileResources(File(temporary.root.canonicalFile, "resources"))
    private fun text(identity: String, hosts: String = "") = "\uFEFF[Profile]\r\nVersion = 1\r\nName = Test\r\n[AccessKey]\r\nType = SSH\r\nIdentityFile = $identity\r\nKnownHostsFile = $hosts\r\n# untouched\r\nFuture = value\r\n"

    @Test fun partialPublicationRetainsBothTokensForRetryAndTerminalDiscard() {
        val resources = resources()
        val draft = SshDraftResources(UUID.randomUUID())
        val a = resources.stage(SshProfileResources.Kind.IDENTITY, "identity".byteInputStream())
        val b = resources.stage(SshProfileResources.Kind.KNOWN_HOSTS, "hosts".byteInputStream())
        val source = text(draft.accept(a)!!, draft.accept(b)!!)
        val sync = resources.syncDirectory
        resources.syncDirectory = { if (a.prepared != null) throw IOException() else sync(it) }
        assertThrows(IOException::class.java) { draft.resolve(source, resources) }
        assertNotNull(a.prepared)
        assertTrue(File(a.prepared!!.path).exists())
        resources.syncDirectory = sync
        val resolved = draft.resolve(source, resources)
        assertEquals(source.replace(ConfigDocument.parse(source).value("AccessKey", "IdentityFile"), a.prepared!!.path)
            .replace(ConfigDocument.parse(source).value("AccessKey", "KnownHostsFile"), b.prepared!!.path), resolved)
        draft.dispose(resources)
        assertFalse(File(a.prepared!!.path).exists())
        assertFalse(File(b.prepared!!.path).exists())
    }

    @Test fun secondKeyFailureRetainsFirstPublishedGeneration() {
        val resources = resources()
        val draft = SshDraftResources(UUID.randomUUID())
        val a = resources.stage(SshProfileResources.Kind.IDENTITY, "A".byteInputStream())
        val b = resources.stage(SshProfileResources.Kind.KNOWN_HOSTS, "B".byteInputStream())
        val source = text(draft.accept(a)!!, draft.accept(b)!!)
        val publish = resources.publish
        resources.publish = { from, to ->
            if (a.prepared != null) throw IOException() else publish(from, to)
        }
        assertThrows(IOException::class.java) { draft.resolve(source, resources) }
        assertTrue(File(a.prepared!!.path).exists())
        assertTrue(b.file.exists())
        resources.publish = publish
        draft.resolve(source, resources)
        draft.dispose(resources)
    }

    @Test fun committedBothKeysSurviveDisposalAndUnknownPathSuppressesPruning() {
        val resources = resources()
        val id = UUID.randomUUID()
        val draft = SshDraftResources(id)
        val a = resources.stage(SshProfileResources.Kind.IDENTITY, "identity".byteInputStream())
        val b = resources.stage(SshProfileResources.Kind.KNOWN_HOSTS, "hosts".byteInputStream())
        val resolved = draft.resolve(text(draft.accept(a)!!, draft.accept(b)!!), resources)
        draft.saved(resolved)
        draft.dispose(resources)
        resources.afterSaved(id, SshDraftResources.paths(resolved))
        assertTrue(File(a.prepared!!.path).exists())
        assertTrue(File(b.prepared!!.path).exists())
        resources.afterSaved(id, setOf("/foreign/file"))
        assertTrue(File(a.prepared!!.path).exists())
        resources.afterSaved(id, setOf(b.prepared!!.path))
        assertFalse(File(a.prepared!!.path).exists())
        assertTrue(File(b.prepared!!.path).exists())
    }

    @Test fun undoAndInvalidRawRetainImportsUntilDiscardAndRejectLateResults() {
        val resources = resources()
        val draft = EditorDraft(Conf.parse(Conf.fromKey("Test", "ssh://user@example.com:22")))
        val a = resources.stage(SshProfileResources.Kind.IDENTITY, "A".byteInputStream())
        val b = resources.stage(SshProfileResources.Kind.IDENTITY, "B".byteInputStream())
        assertTrue(draft.importKey(SshProfileResources.Kind.IDENTITY, a))
        val first = draft.source
        assertTrue(draft.importKey(SshProfileResources.Kind.IDENTITY, b))
        draft.undo()
        assertEquals(first, draft.source)
        draft.update(androidx.compose.ui.text.input.TextFieldValue("[invalid"))
        assertTrue(a.file.exists())
        assertTrue(b.file.exists())
        draft.resources.dispose(resources)
        assertFalse(a.file.exists())
        assertFalse(b.file.exists())
        val late = resources.stage(SshProfileResources.Kind.IDENTITY, "late".byteInputStream())
        assertNull(draft.resources.accept(late))
        resources.discard(late)
    }
}
