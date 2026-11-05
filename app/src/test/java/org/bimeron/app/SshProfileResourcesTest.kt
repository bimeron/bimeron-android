package org.bimeron.app

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SshProfileResourcesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store() = SshProfileResources(File(temporary.root.canonicalFile, UUID.randomUUID().toString()))
    private fun failure(block: () -> Unit) {
        try { block(); fail("Expected failure") } catch (e: IOException) {
            assertEquals("SSH resource operation failed", e.message)
            assertNull(e.cause)
        }
    }
    private fun imported(store: SshProfileResources, id: UUID, kind: SshProfileResources.Kind, text: String) =
        store.prepare(id, store.stage(kind, text.byteInputStream()))

    @Test fun boundedReadClosesAndHandlesZeroReads() {
        val store = store()
        for (size in listOf(0, 65536, 65537, 100000)) {
            var closed = false
            var consumed = 0
            val input = object : InputStream() {
                override fun read(b: ByteArray, off: Int, len: Int): Int = 0
                override fun read(): Int = if (consumed == size) -1 else { consumed++; 42 }
                override fun close() { closed = true }
            }
            if (size == 65536) store.discard(store.stage(SshProfileResources.Kind.IDENTITY, input))
            else failure { store.stage(SshProfileResources.Kind.IDENTITY, input) }
            assertTrue(closed)
            assertTrue(consumed <= 65537)
        }
    }

    @Test fun readAndCloseErrorsAreSanitizedAndCleanPartials() {
        val root = File(temporary.root.canonicalFile, "resources")
        val store = SshProfileResources(root)
        for (closeFailure in listOf(false, true)) {
            var closed = false
            val input = object : ByteArrayInputStream(byteArrayOf(1)) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (!closeFailure) throw IOException("secret provider /outside/key")
                    return super.read(b, off, len)
                }
                override fun close() { closed = true; if (closeFailure) throw IOException("secret") }
            }
            failure { store.stage(SshProfileResources.Kind.IDENTITY, input) }
            assertTrue(closed)
            assertEquals(0, File(root, "staging").list()!!.size)
        }
    }

    @Test fun prepareIsImmutableIdempotentAndSaveFailureCanRetryOrAbort() {
        val store = store()
        val id = UUID.randomUUID()
        val token = store.stage(SshProfileResources.Kind.IDENTITY, "synthetic".byteInputStream())
        val first = store.prepare(id, token)
        assertSame(first, store.prepare(id, token))
        store.discard(token)
        assertEquals("synthetic", File(first.path).readText())
        failure { store.prepare(UUID.randomUUID(), token) }
        val other = imported(store, id, SshProfileResources.Kind.IDENTITY, "replacement")
        assertNotEquals(first.path, other.path)
        store.abort(other)
        assertTrue(File(first.path).exists())
        assertFalse(File(other.path).exists())
    }

    @Test fun failedPublicationLeavesOldGenerationAndStagingRetryable() {
        val store = store()
        val id = UUID.randomUUID()
        val old = imported(store, id, SshProfileResources.Kind.IDENTITY, "old synthetic")
        val token = store.stage(SshProfileResources.Kind.IDENTITY, "new synthetic".byteInputStream())
        val publish = store.publish
        store.publish = { _, _ -> throw IOException("private path") }
        failure { store.prepare(id, token) }
        assertEquals("old synthetic", File(old.path).readText())
        store.publish = publish
        assertEquals("new synthetic", File(store.prepare(id, token).path).readText())
    }

    @Test fun publicationSyncsEntireAncestryBeforeReturningAndOnRetry() {
        val root = File(temporary.root.canonicalFile, "resources")
        val store = SshProfileResources(root)
        val id = UUID.randomUUID()
        val token = store.stage(SshProfileResources.Kind.IDENTITY, "synthetic".byteInputStream())
        val events = mutableListOf<File>()
        val sync = store.syncDirectory
        val publish = store.publish
        store.syncDirectory = { directory -> sync(directory); events.add(directory) }
        store.publish = { source, target ->
            assertEquals(listOf(source), events)
            publish(source, target)
            events.add(target) // Rename precedes all destination/ancestor syncs.
        }
        val handle = store.prepare(id, token)
        val generation = File(handle.path).parentFile
        val durability = listOf(generation, File(root, "staging"), generation.parentFile,
            File(root, "profiles"), root, root.parentFile)
        assertEquals(listOf(token.file.parentFile, generation) + durability, events)
        events.clear()
        assertSame(handle, store.prepare(id, token))
        assertEquals(durability, events)
    }

    @Test fun failureAfterActualRenameRetainsPublishedGenerationForRetry() {
        val store = store()
        val id = UUID.randomUUID()
        val old = imported(store, id, SshProfileResources.Kind.IDENTITY, "old")
        val token = store.stage(SshProfileResources.Kind.IDENTITY, "new".byteInputStream())
        val publish = store.publish
        store.publish = { source, target -> publish(source, target); throw IOException("private path") }
        failure { store.prepare(id, token) }
        val pending = token.prepared!!
        assertFalse(token.file.exists())
        store.discard(token)
        assertEquals("new", File(pending.path).readText())
        failure { store.prepare(UUID.randomUUID(), token) }
        store.publish = { _, _ -> fail("Retry must not republish") }
        assertSame(pending, store.prepare(id, token))
        store.abort(pending)
        assertFalse(File(pending.path).exists())
        assertEquals("old", File(old.path).readText())
    }

    @Test fun failureAfterEachActualDirectorySyncRetainsRetryableState() {
        // First sync precedes rename; the remaining six make publication durable.
        for (failAt in 1..7) {
            val store = store()
            val id = UUID.randomUUID()
            val old = imported(store, id, SshProfileResources.Kind.IDENTITY, "old")
            val token = store.stage(SshProfileResources.Kind.IDENTITY, "new".byteInputStream())
            val sync = store.syncDirectory
            var count = 0
            store.syncDirectory = { directory ->
                sync(directory)
                if (++count == failAt) throw IOException("private directory")
            }
            failure { store.prepare(id, token) }
            assertEquals("old", File(old.path).readText())
            if (failAt == 1) {
                assertNull(token.prepared)
                assertTrue(token.file.exists())
            } else {
                assertNotNull(token.prepared)
                assertFalse(token.file.exists())
                assertEquals("new", File(token.prepared!!.path).readText())
                store.discard(token)
            }
            val pending = token.prepared
            count = 0
            store.syncDirectory = { directory -> sync(directory); count++ }
            val recovered = store.prepare(id, token)
            if (pending != null) assertSame(pending, recovered)
            assertEquals(if (pending == null) 7 else 6, count)
            assertEquals("new", File(recovered.path).readText())
            store.afterSaved(id, setOf(recovered.path))
            assertFalse(File(old.path).exists())
        }
    }

    @Test fun persistentPostRenameSyncFailureCanBeCancelledThroughPublicApi() {
        val store = store()
        val id = UUID.randomUUID()
        val old = imported(store, id, SshProfileResources.Kind.IDENTITY, "old")
        val token = store.stage(SshProfileResources.Kind.IDENTITY, "new".byteInputStream())
        val sync = store.syncDirectory
        var count = 0
        store.syncDirectory = { directory ->
            if (++count == 1) sync(directory) // Allow only the pre-rename sync.
            else throw IOException("private directory")
        }
        failure { store.prepare(id, token) }
        val pending = requireNotNull(token.prepared)
        repeat(2) {
            failure { store.prepare(id, token) }
            assertSame(pending, token.prepared)
        }
        store.discard(token)
        assertEquals("new", File(pending.path).readText())
        store.abort(pending)
        assertFalse(File(pending.path).parentFile.exists())
        assertEquals("old", File(old.path).readText())
    }

    @Test fun ownedRootRequiresAnExistingParent() {
        val root = File(temporary.root.canonicalFile, "missing/resources")
        failure { SshProfileResources(root).stage(SshProfileResources.Kind.IDENTITY, "key".byteInputStream()) }
        assertFalse(root.parentFile.exists())
    }

    @Test fun successfulSavePrunesOnlyUnreferencedOwnedGenerations() {
        val store = store()
        val id = UUID.randomUUID()
        val old = imported(store, id, SshProfileResources.Kind.IDENTITY, "old")
        val hosts = imported(store, id, SshProfileResources.Kind.KNOWN_HOSTS, "hosts")
        val replacement = imported(store, id, SshProfileResources.Kind.IDENTITY, "new")
        store.afterSaved(id, setOf("/unknown/external"))
        assertTrue(File(old.path).exists())
        store.afterSaved(id, setOf(hosts.path, replacement.path))
        assertFalse(File(old.path).exists())
        assertTrue(File(hosts.path).exists())
        assertTrue(File(replacement.path).exists())
        val other = imported(store, UUID.randomUUID(), SshProfileResources.Kind.IDENTITY, "other")
        store.afterDeleted(id)
        assertFalse(File(hosts.path).exists())
        assertTrue(File(other.path).exists())
    }

    @Test fun foreignTokensAndHandlesAreRejected() {
        val first = store()
        val second = store()
        val token = first.stage(SshProfileResources.Kind.IDENTITY, "fake".byteInputStream())
        failure { second.discard(token) }
        failure { second.prepare(UUID.randomUUID(), token) }
        val handle = first.prepare(UUID.randomUUID(), token)
        failure { second.abort(handle) }
        assertTrue(File(handle.path).exists())
    }

    @Test fun symlinksAndTraversalCannotDeleteOutsideFiles() {
        val root = File(temporary.root.canonicalFile, "resources")
        val store = SshProfileResources(root)
        val id = UUID.randomUUID()
        val handle = imported(store, id, SshProfileResources.Kind.IDENTITY, "fake")
        val outside = temporary.newFolder("outside").canonicalFile
        val secret = File(outside, "synthetic").apply { writeText("untouched") }
        val link = File(File(handle.path).parentFile, "escape")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        failure { store.afterDeleted(id) }
        store.afterSaved(id, setOf(link.resolve("synthetic").path))
        store.afterSaved(id, setOf("${root.path}/../outside/synthetic"))
        assertEquals("untouched", secret.readText())
        link.delete()
        store.afterDeleted(id)
        assertEquals("untouched", secret.readText())
        val linkedRoot = File(temporary.root.canonicalFile, "linked")
        Files.createSymbolicLink(linkedRoot.toPath(), outside.toPath())
        failure { SshProfileResources(linkedRoot).stage(SshProfileResources.Kind.IDENTITY, "fake".byteInputStream()) }
        assertEquals(listOf("synthetic"), outside.list()!!.toList())
    }
}
