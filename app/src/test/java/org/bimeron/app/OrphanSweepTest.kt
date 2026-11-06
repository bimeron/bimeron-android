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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Snapshot part of the singleton sweep (contract 8.7). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrphanSweepTest {
    @get:Rule val temp = TemporaryFolder()
    @Before @After fun isolateProcessSingleton() {
        ProfileStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }
    private val root by lazy { temp.root.canonicalFile }
    private val context by lazy { object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getFilesDir() = root
    } }
    private val snapshots by lazy { File(root, "ssconf").apply { mkdir() } }
    private fun profile(id: UUID) = File(root, "profiles").apply { mkdir() }.let { File(it, "$id.conf") }
        .apply { writeText(Conf.fromKey("P", "ssconf://example.com/$id")) }
    private fun snap(id: UUID) = File(snapshots, "$id.snap").apply { writeBytes(SSConfSnapshots.encode(id, "ssconf://example.com/$id", byteArrayOf(1), 0)) }

    @Test fun validInventoryRemovesOnlyOrphanSnapshotsAndTmpFiles() = runBlocking<Unit> {
        val kept = UUID.randomUUID(); profile(kept)
        val keptSnap = snap(kept)
        val orphan = snap(UUID.randomUUID())
        val tmp = File(snapshots, "$kept.snap.tmp-1234").apply { writeText("partial") }
        AppEvents.clear()
        val store = withContext(Dispatchers.IO) { ProfileStore.get(context) }
        assertFalse(AppEvents.entries.value.any { it.message == "STORE_SWEEP_SKIPPED" })
        assertTrue(keptSnap.exists()); assertFalse(orphan.exists()); assertFalse(tmp.exists())
        assertArrayEquals(byteArrayOf(1), store.readForSession(kept.toString()) { true }.snapshot!!.body)
    }

    @Test fun unknownEntryDeletesNothing() = runBlocking<Unit> {
        val orphan = snap(UUID.randomUUID())
        val tmp = File(snapshots, "${UUID.randomUUID()}.snap.tmp-1").apply { writeText("partial") }
        val unknown = File(snapshots, "notes.txt").apply { writeText("retain") }
        AppEvents.clear()
        withContext(Dispatchers.IO) { ProfileStore.get(context) }
        assertTrue(orphan.exists()); assertTrue(tmp.exists()); assertTrue(unknown.exists())
        assertTrue(AppEvents.entries.value.any { it.message == "STORE_SWEEP_SKIPPED" })
    }

    @Test fun nonCanonicalNamesAndDirectoriesDeleteNothing() = runBlocking<Unit> {
        for (name in listOf("${UUID.randomUUID().toString().uppercase()}.snap", "${UUID.randomUUID()}.snap.tmp-", "x.snap")) {
            isolateProcessSingleton()
            snapshots.listFiles()!!.forEach { it.deleteRecursively() }
            val orphan = snap(UUID.randomUUID())
            val odd = File(snapshots, name).apply { writeText("odd") }
            withContext(Dispatchers.IO) { ProfileStore.get(context) }
            assertTrue(name, orphan.exists()); assertTrue(name, odd.exists())
        }
        isolateProcessSingleton()
        snapshots.listFiles()!!.forEach { it.deleteRecursively() }
        val orphan = snap(UUID.randomUUID())
        val directory = File(snapshots, "${UUID.randomUUID()}.snap").apply { mkdir() }
        withContext(Dispatchers.IO) { ProfileStore.get(context) }
        assertTrue(orphan.exists()); assertTrue(directory.isDirectory)
    }

    @Test fun symlinkEntryDeletesNothing() = runBlocking<Unit> {
        val orphan = snap(UUID.randomUUID())
        val outside = temp.newFile("outside.snap").apply { writeText("outside") }
        Files.createSymbolicLink(File(snapshots, "${UUID.randomUUID()}.snap").toPath(), outside.toPath())
        AppEvents.clear()
        withContext(Dispatchers.IO) { ProfileStore.get(context) }
        assertTrue(orphan.exists()); assertEquals("outside", outside.readText())
        assertTrue(AppEvents.entries.value.any { it.message == "STORE_SWEEP_SKIPPED" })
    }

    @Test fun listingFailureDeletesNothing() = runBlocking<Unit> {
        val orphan = snap(UUID.randomUUID())
        val pending = File(root, "profiles").apply { mkdir() }.let { File(it, "profile-retain.tmp").apply { writeText("pending") } }
        val locked = File(root, "ssh-resources").apply { mkdir() }
        locked.setReadable(false)
        try {
            AppEvents.clear()
            withContext(Dispatchers.IO) { ProfileStore.get(context) }
            assertTrue(orphan.exists()); assertTrue(pending.exists())
            assertTrue(AppEvents.entries.value.any { it.message == "STORE_SWEEP_SKIPPED" })
        } finally { locked.setReadable(true) }
    }
}
