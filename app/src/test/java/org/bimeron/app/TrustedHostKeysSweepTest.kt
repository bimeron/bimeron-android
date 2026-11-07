package org.bimeron.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `trusted-hosts/` in the fail-closed singleton sweep: orphans and stale tmp files go, anything unexpected deletes nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrustedHostKeysSweepTest {
    @get:Rule val temp = TemporaryFolder()
    @Before @After fun isolateProcessSingleton() {
        ProfileStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }
    private val root by lazy { temp.root.canonicalFile }
    private val context by lazy { object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getFilesDir() = root
    } }
    private val line = HostKeyFixtures.line("ssh.example.com:22")
    private val trusted by lazy { File(root, "trusted-hosts").apply { mkdir() } }
    private fun profile(id: UUID) = File(root, "profiles").apply { mkdir() }.let { File(it, "$id.conf") }
        .apply { writeText(Conf.fromKey("P", "ssh://u@ssh.example.com:22")) }
    private fun keys(id: UUID) = File(trusted, "$id.known_hosts").apply { writeText("$line\n") }
    private fun skipped() = AppEvents.entries.value.any { it.message == "STORE_SWEEP_SKIPPED" }
    private suspend fun open() = withContext(Dispatchers.IO) { ProfileStore.get(context) }

    @Test fun orphansAndStaleTemporariesAreDeletedAndMatchedFilesKept() = runBlocking<Unit> {
        val kept = UUID.randomUUID(); profile(kept)
        val keptKeys = keys(kept)
        val orphan = keys(UUID.randomUUID())
        val keptTmp = File(trusted, "$kept.known_hosts.tmp-1a2b").apply { writeText("partial") }
        val orphanTmp = File(trusted, "${UUID.randomUUID()}.known_hosts.tmp-9").apply { writeText("partial") }
        AppEvents.clear()
        val store = open()
        assertFalse(skipped())
        assertTrue(keptKeys.exists()); assertFalse(orphan.exists()); assertFalse(keptTmp.exists()); assertFalse(orphanTmp.exists())
        assertArrayEquals("$line\n".toByteArray(), store.trustedHostKeys(kept))
        assertEquals(1, store.trustedHostKeyCount(kept))
    }

    @Test fun missingTreeIsFine() = runBlocking<Unit> {
        val kept = UUID.randomUUID(); profile(kept)
        AppEvents.clear()
        open()
        assertFalse(skipped())
        assertFalse(File(root, "trusted-hosts").exists())
    }

    @Test fun unexpectedEntryDeletesNothingAnywhere() = runBlocking<Unit> {
        for (name in listOf("notes.txt", "${UUID.randomUUID().toString().uppercase()}.known_hosts",
            "${UUID.randomUUID()}.known_hosts.tmp-", "x.known_hosts", "${UUID.randomUUID()}.snap")) {
            isolateProcessSingleton()
            trusted.listFiles()!!.forEach { it.deleteRecursively() }
            val orphan = keys(UUID.randomUUID())
            val pending = File(root, "profiles").apply { mkdir() }.let { File(it, "profile-retain.tmp").apply { writeText("pending") } }
            val unexpected = File(trusted, name).apply { writeText("retain") }
            AppEvents.clear()
            open()
            assertTrue(name, skipped())
            assertTrue(name, orphan.exists()); assertTrue(name, unexpected.exists()); assertTrue(name, pending.exists())
            pending.delete()
        }
    }

    @Test fun directoryOrSymlinkEntryDeletesNothing() = runBlocking<Unit> {
        val orphan = keys(UUID.randomUUID())
        val directory = File(trusted, "${UUID.randomUUID()}.known_hosts").apply { mkdir() }
        AppEvents.clear()
        open()
        assertTrue(skipped()); assertTrue(orphan.exists()); assertTrue(directory.isDirectory)
        isolateProcessSingleton()
        directory.delete()
        val outside = temp.newFile("outside").apply { writeText("outside") }
        Files.createSymbolicLink(File(trusted, "${UUID.randomUUID()}.known_hosts").toPath(), outside.toPath())
        AppEvents.clear()
        open()
        assertTrue(skipped()); assertTrue(orphan.exists()); assertEquals("outside", outside.readText())
    }

    @Test fun symlinkedTreeDeletesNothing() = runBlocking<Unit> {
        val outside = temp.newFolder("elsewhere").canonicalFile
        val foreign = File(outside, "${UUID.randomUUID()}.known_hosts").apply { writeText("$line\n") }
        Files.createSymbolicLink(File(root, "trusted-hosts").toPath(), outside.toPath())
        AppEvents.clear()
        open()
        assertTrue(skipped()); assertTrue(foreign.exists())
    }
}
