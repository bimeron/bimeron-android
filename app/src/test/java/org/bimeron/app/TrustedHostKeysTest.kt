package org.bimeron.app

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.bimeron.app.HostKeyFixtures.line
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** App-owned per-profile known_hosts storage for accept-new host keys. */
class TrustedHostKeysTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root by lazy { File(temporary.root.canonicalFile, "trusted-hosts") }
    private val store by lazy { TrustedHostKeys(root) }
    private val id = UUID.randomUUID()
    private fun file(id: UUID = this.id) = File(root, "$id.known_hosts")
    private fun mode(file: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath(), NOFOLLOW_LINKS))
    private fun bytes(vararg lines: String) = lines.joinToString("") { "$it\n" }.toByteArray(Charsets.US_ASCII)

    @Test fun appendCreatesPrivateFilePerProfile() {
        assertArrayEquals(ByteArray(0), store.read(id))
        assertEquals(0, store.count(id))
        assertFalse(root.exists())
        assertEquals(TrustedHostKeys.Append.ADDED, store.append(id, line()))
        assertEquals("rwx------", mode(root))
        assertEquals("rw-------", mode(file()))
        assertArrayEquals(bytes(line()), file().readBytes())
        assertArrayEquals(bytes(line()), store.read(id))
        assertEquals(TrustedHostKeys.Append.ADDED, store.append(id, line("other.example:22", seed = 2)))
        assertArrayEquals(bytes(line(), line("other.example:22", seed = 2)), store.read(id))
        assertEquals(2, store.count(id))
        val other = UUID.randomUUID()
        assertEquals(TrustedHostKeys.Append.ADDED, store.append(other, line()))
        assertEquals(setOf("$id.known_hosts", "$other.known_hosts"), root.list()!!.toSet())
    }

    @Test fun duplicateIsNotWrittenAgain() {
        store.append(id, line())
        val before = file().lastModified()
        store.publish = { _, _ -> fail("rewrote a duplicate") }
        assertEquals(TrustedHostKeys.Append.DUPLICATE, store.append(id, line()))
        assertEquals(before, file().lastModified())
        assertEquals(1, store.count(id))
    }

    @Test fun capMatchesCoreLimitAndLeavesFileUnchanged() {
        root.mkdirs()
        val lines = mutableListOf<String>()
        var seed = 0
        while (bytes(*lines.toTypedArray()).size + 120 < TrustedHostKeys.MAX_BYTES) lines += line("h$seed.example:2222", seed = seed++)
        val content = bytes(*lines.toTypedArray())
        file().writeBytes(content)
        val next = line("last.example:2222", seed = seed)
        val fits = content.size + next.length + 1 <= TrustedHostKeys.MAX_BYTES
        if (fits) {
            assertEquals(TrustedHostKeys.Append.ADDED, store.append(id, next))
            assertTrue(store.read(id).size <= TrustedHostKeys.MAX_BYTES)
        }
        // Fill to the brim, then the next line is refused.
        while (true) {
            val candidate = line("x$seed.example:2222", seed = seed++)
            val result = store.append(id, candidate)
            if (result == TrustedHostKeys.Append.FULL) break
            assertEquals(TrustedHostKeys.Append.ADDED, result)
        }
        val full = file().readBytes()
        assertTrue(full.size <= TrustedHostKeys.MAX_BYTES)
        assertEquals(TrustedHostKeys.Append.FULL, store.append(id, line("y.example:2222", seed = seed + 1)))
        assertArrayEquals(full, file().readBytes())
        // An oversized file is not read at all.
        file().writeBytes(ByteArray(TrustedHostKeys.MAX_BYTES + 1) { 'A'.code.toByte() })
        try { store.read(id); fail() } catch (_: IOException) {}
    }

    @Test fun publicationFailureKeepsPreviousFileAndNoTemporary() {
        store.append(id, line())
        val before = file().readBytes()
        store.publish = { source, target ->
            // Atomic replace: the new bytes are complete in a private temporary before the move.
            assertEquals("rw-------", mode(source))
            assertArrayEquals(before, target.readBytes())
            throw IOException("synthetic")
        }
        try { store.append(id, line("b.example:22", seed = 2)); fail() } catch (_: IOException) {}
        assertArrayEquals(before, file().readBytes())
        assertEquals(listOf("$id.known_hosts"), root.list()!!.toList())
    }

    @Test fun damagedLinesAreDroppedNotPassedToCore() {
        root.mkdirs()
        file().writeText("# note\n\n${line()}\n@revoked * ssh-ed25519 AAAA\ngarbage\n${line()}\r\n|1|x|y ssh-rsa AAAA\n")
        assertArrayEquals(bytes(line()), store.read(id))
        assertEquals(1, store.count(id))
        assertEquals(TrustedHostKeys.Append.DUPLICATE, store.append(id, line()))
        store.append(id, line("b.example:22", seed = 2))
        assertEquals(bytes(line(), line("b.example:22", seed = 2)).decodeToString(), file().readText())
    }

    @Test fun appendRejectsLinesThatAreNotOfferLines() {
        for (bad in listOf("", "${line()}\nevil ssh-ed25519 AAAA", "${line()} comment", "@revoked ${line()}", "*.example ${line().substringAfter(' ')}")) {
            try { store.append(id, bad); fail(bad) } catch (_: IOException) {}
        }
        assertFalse(file().exists())
    }

    @Test fun forgetRemovesFileAndLeftoverTemporaries() {
        store.forget(id)
        store.append(id, line())
        val leftover = File(root, "$id.known_hosts.tmp-1234").apply { writeText("partial") }
        val unrelated = File(root, "${UUID.randomUUID()}.known_hosts.tmp-1").apply { writeText("other") }
        store.forget(id)
        assertFalse(file().exists()); assertFalse(leftover.exists()); assertTrue(unrelated.exists())
        assertEquals(0, store.count(id))
        store.forget(id)
    }

    @Test fun symlinksAreRefused() {
        val outside = temporary.newFolder("outside").canonicalFile
        File(outside, "$id.known_hosts").writeText(line() + "\n")
        Files.createSymbolicLink(root.toPath(), outside.toPath())
        for (action in listOf<() -> Unit>({ store.read(id) }, { store.append(id, line(seed = 3)) }, { store.forget(id) })) {
            try { action(); fail() } catch (_: IOException) {}
        }
        assertEquals(line() + "\n", File(outside, "$id.known_hosts").readText())
        Files.delete(root.toPath()); root.mkdirs()
        val target = temporary.newFile("target").canonicalFile.apply { writeText(line() + "\n") }
        Files.createSymbolicLink(file().toPath(), target.toPath())
        try { store.read(id); fail() } catch (_: IOException) {}
    }

    @Test fun persistNeverThrowsAndWarnsOnlyWhenTrustIsLost() {
        val warnings = mutableListOf<Int>()
        persistAcceptedHostKey(line(), { TrustedHostKeys.Append.ADDED }) { warnings += it }
        persistAcceptedHostKey(line(), { TrustedHostKeys.Append.DUPLICATE }) { warnings += it }
        persistAcceptedHostKey(line(), { null }) { warnings += it }
        assertTrue(warnings.isEmpty())
        persistAcceptedHostKey(line(), { TrustedHostKeys.Append.FULL }) { warnings += it }
        persistAcceptedHostKey(line(), { throw IOException("disk full") }) { warnings += it }
        assertEquals(List(2) { R.string.journal_trusted_host_key_not_saved }, warnings)
    }

    /** ProfileStore wiring: guarded append, profile-existence check, and the compareAndDelete hook. */
    @Test fun storeDeleteHookAndGuardedAppend() = runBlocking {
        val base = temporary.root.canonicalFile
        val profiles = File(base, "profiles").apply { mkdirs() }
        val store = ProfileStore(null, profiles, SshProfileResources(File(base, "ssh-resources"))) {}
        val saved = store.save(Conf.fromKey("SSH", "ssh://user@ssh.example.com:2222"))
        val profileId = UUID.fromString(saved.id)
        val stored = File(base, "trusted-hosts/$profileId.known_hosts")
        assertEquals(TrustedHostKeys.Append.ADDED, store.rememberTrustedHostKey(profileId, line()))
        assertTrue(stored.isFile)
        assertArrayEquals(bytes(line()), store.trustedHostKeys(profileId))
        assertEquals(1, store.trustedHostKeyCount(profileId))
        // Saving the profile again (same UUID) keeps its trusted keys.
        store.save(Conf.fromKey("SSH renamed", "ssh://user@ssh.example.com:2222"), saved.id)
        assertEquals(1, store.trustedHostKeyCount(profileId))
        store.forgetTrustedHostKeys(profileId)
        assertEquals(0, store.trustedHostKeyCount(profileId))
        store.rememberTrustedHostKey(profileId, line())
        store.delete(saved.id)
        assertFalse(stored.exists())
        // A late acceptance for a deleted profile never recreates its file.
        assertNull(store.rememberTrustedHostKey(profileId, line()))
        assertFalse(stored.exists())
        assertNull(store.rememberTrustedHostKey(UUID.randomUUID(), line()))
        assertEquals(emptyList<String>(), File(base, "trusted-hosts").list()!!.toList())
    }

    @Test fun rememberUnderTheGateFailsClosed() = runBlocking {
        val base = temporary.root.canonicalFile
        val store = ProfileStore(null, File(base, "profiles").apply { mkdirs() }, SshProfileResources(File(base, "ssh-resources"))) {}
        val profileId = UUID.fromString(store.save(Conf.fromKey("SSH", "ssh://user@ssh.example.com:2222")).id)
        try {
            synchronized(VpnLive.gate) { store.rememberTrustedHostKey(profileId, line()) }
            fail()
        } catch (_: IllegalStateException) {}
        assertFalse(File(base, "trusted-hosts").exists())
    }

    /** A draft whose profile was deleted elsewhere: count 0, Forget is a no-op that recreates nothing. */
    @Test fun deletedProfileCountsZeroAndForgetIsNoOp() = runBlocking {
        val base = temporary.root.canonicalFile
        val store = ProfileStore(null, File(base, "profiles").apply { mkdirs() }, SshProfileResources(File(base, "ssh-resources"))) {}
        val saved = store.save(Conf.fromKey("SSH", "ssh://user@ssh.example.com:2222"))
        val profileId = UUID.fromString(saved.id)
        store.rememberTrustedHostKey(profileId, line())
        assertEquals(1, store.trustedHostKeyCount(profileId))
        store.delete(saved.id)
        assertEquals(0, store.trustedHostKeyCount(profileId))
        store.forgetTrustedHostKeys(profileId)
        assertEquals(emptyList<String>(), File(base, "trusted-hosts").list()!!.toList())
        // Never created in the first place: still nothing.
        val other = UUID.randomUUID()
        val fresh = ProfileStore(null, File(base, "p2").apply { mkdirs() }, SshProfileResources(File(base, "r2"))) {}
        assertEquals(0, fresh.trustedHostKeyCount(other))
        fresh.forgetTrustedHostKeys(other)
        assertFalse(File(base, "trusted-hosts").list()!!.isNotEmpty())
    }
}
