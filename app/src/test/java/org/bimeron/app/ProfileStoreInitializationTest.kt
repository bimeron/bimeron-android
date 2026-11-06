package org.bimeron.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileStoreInitializationTest {
    @get:Rule val temp = TemporaryFolder()
    @Before @After fun isolateProcessSingleton() {
        ProfileStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }
    @Test fun concurrentFirstGetPublishesOnlyAfterSweepAndUsesWrapperFilesDir() = runBlocking {
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getFilesDir() = temp.root.canonicalFile
        }
        val resources = SshProfileResources(File(temp.root.canonicalFile, "ssh-resources"))
        val staged = resources.stage(SshProfileResources.Kind.IDENTITY, "orphan".byteInputStream())
        val testStore = ProfileStore(context)
        assertTrue(staged.file.exists()) // Test constructors must never sweep.
        val returned = (1..8).map { async(Dispatchers.IO) {
            val store = ProfileStore.get(context)
            store to store.resources.stage(SshProfileResources.Kind.IDENTITY, "early".byteInputStream())
        } }.awaitAll()
        val stores = returned.map { it.first }
        stores.forEach { assertSame(stores.first(), it) }
        returned.forEach { assertTrue(it.second.file.exists()) }
        assertFalse(staged.file.exists())
        val firstImport = withContext(Dispatchers.IO) {
            stores.first().resources.stage(SshProfileResources.Kind.IDENTITY, "new".byteInputStream())
        }
        assertTrue(firstImport.file.canonicalPath.startsWith(temp.root.canonicalPath))
        assertTrue(firstImport.file.exists())
        assertNotSame(testStore, stores.first())
    }
    @Test fun malformedProfileInventoryPreventsAnyResourceOrTmpCleanup() = runBlocking {
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getFilesDir() = temp.root.canonicalFile
        }
        val directory = File(temp.root.canonicalFile, "profiles").apply { mkdir() }
        File(directory, "${UUID.randomUUID()}.conf").writeText("malformed")
        val pending = File(directory, "profile-retain.tmp").apply { writeText("pending") }
        val resources = SshProfileResources(File(temp.root.canonicalFile, "ssh-resources"))
        val staged = resources.stage(SshProfileResources.Kind.IDENTITY, "orphan".byteInputStream())
        withContext(Dispatchers.IO) { ProfileStore.get(context) }
        assertTrue(staged.file.exists()); assertTrue(pending.exists())
    }
    @Test fun unexpectedSnapshotInventoryPreventsAnyResourceOrTmpCleanup() = runBlocking {
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getFilesDir() = temp.root.canonicalFile
        }
        val directory = File(temp.root.canonicalFile, "profiles").apply { mkdir() }
        val pending = File(directory, "profile-retain.tmp").apply { writeText("pending") }
        val snapshots = File(temp.root.canonicalFile, "ssconf").apply { mkdir() }
        File(snapshots, "foreign").writeText("retain")
        val resources = SshProfileResources(File(temp.root.canonicalFile, "ssh-resources"))
        val staged = resources.stage(SshProfileResources.Kind.IDENTITY, "orphan".byteInputStream())
        withContext(Dispatchers.IO) { ProfileStore.get(context) }
        assertTrue(staged.file.exists()); assertTrue(pending.exists())
    }
    @Test fun symlinkFilesDirAncestorWithoutResourcesRetainsExternalTmp() = runBlocking {
        val outside = temp.newFolder("outside").canonicalFile
        val directory = File(outside, "profiles").apply { mkdir() }
        val pending = File(directory, "profile-retain.tmp").apply { writeText("pending") }
        val alias = File(temp.root.canonicalFile, "alias")
        java.nio.file.Files.createSymbolicLink(alias.toPath(), outside.toPath())
        val context = wrappedFilesDir(alias)
        AppEvents.clear()
        withContext(Dispatchers.IO) { ProfileStore.get(context) }
        assertEquals("pending", pending.readText())
        assertFalse(File(outside, "ssh-resources").exists())
        assertTrue(AppEvents.entries.value.any { it.message == "STORE_SWEEP_SKIPPED" })
    }
    @Test fun symlinkAboveFilesDirRetainsAllExternalInventory() = runBlocking {
        val outside = temp.newFolder("outside").canonicalFile
        val root = File(outside, "files").apply { mkdir() }
        val directory = File(root, "profiles").apply { mkdir() }
        File(directory, "profile-retain.tmp").writeText("pending")
        val snapshots = File(root, "ssconf").apply { mkdir() }
        File(snapshots, "${UUID.randomUUID()}.snap").writeText("snapshot")
        val resources = SshProfileResources(File(root, "ssh-resources"))
        resources.prepare(UUID.randomUUID(), resources.stage(SshProfileResources.Kind.IDENTITY, "generation".byteInputStream()))
        resources.stage(SshProfileResources.Kind.KNOWN_HOSTS, "staging".byteInputStream())
        val before = inventory(root)
        val alias = File(temp.root.canonicalFile, "alias")
        java.nio.file.Files.createSymbolicLink(alias.toPath(), outside.toPath())
        withContext(Dispatchers.IO) { ProfileStore.get(wrappedFilesDir(File(alias, "files"))) }
        assertEquals(before, inventory(root))
    }
    @Test fun realWrapperRootCleansInventoryWithoutTouchingBaseContext() = runBlocking {
        val root = temp.newFolder("private-files").canonicalFile
        val directory = File(root, "profiles").apply { mkdir() }
        val pending = File(directory, "profile-remove.tmp").apply { writeText("pending") }
        val resources = SshProfileResources(File(root, "ssh-resources"))
        val generation = resources.prepare(UUID.randomUUID(), resources.stage(SshProfileResources.Kind.IDENTITY, "generation".byteInputStream()))
        val staged = resources.stage(SshProfileResources.Kind.IDENTITY, "staging".byteInputStream())
        val otherRoot = temp.newFolder("base-files").canonicalFile
        val otherTmp = File(otherRoot, "profiles/profile-retain.tmp").apply { requireNotNull(parentFile).mkdirs(); writeText("retain") }
        val context = object : ContextWrapper(wrappedFilesDir(otherRoot)) {
            override fun getFilesDir() = root
        }
        ProfileStore(context)
        assertTrue(pending.exists()); assertTrue(staged.file.exists()); assertTrue(File(generation.path).exists())
        withContext(Dispatchers.IO) { ProfileStore.get(context) }
        assertFalse(pending.exists()); assertFalse(staged.file.exists()); assertFalse(File(generation.path).exists())
        assertEquals("retain", otherTmp.readText())
    }
    /** Mirrors Android: filesDir = /data/user/0/<pkg>/files where /data/user/0 -> /data/data. */
    private fun packageContext(): Triple<Context, File, File> {
        val realUser = temp.newFolder("data-data").canonicalFile
        val realData = File(realUser, "org.bimeron.app").apply { mkdir() }
        val userAlias = File(temp.newFolder("data-user").canonicalFile, "0")
        Files.createSymbolicLink(userAlias.toPath(), realUser.toPath())
        val dataDir = File(userAlias, "org.bimeron.app")
        File(realData, "files").mkdir()
        val filesDir = File(dataDir, "files")
        val base = ApplicationProvider.getApplicationContext<Context>()
        val info = android.content.pm.ApplicationInfo(base.applicationInfo).apply { this.dataDir = dataDir.path }
        val context = object : ContextWrapper(base) {
            override fun getApplicationInfo() = info
            override fun getFilesDir() = filesDir.apply { mkdirs() }
        }
        return Triple(context, filesDir, File(realData, "files"))
    }
    @Test fun rootUnderSymlinkedPlatformAncestorIsTrustedAndResolvedOnce() = runBlocking {
        val (context, filesDir, real) = packageContext()
        assertTrue(Files.isSymbolicLink(filesDir.parentFile!!.parentFile!!.toPath()))
        val pending = File(real, "profiles").apply { mkdirs() }.let { File(it, "profile-remove.tmp").apply { writeText("pending") } }
        AppEvents.clear()
        val store = withContext(Dispatchers.IO) { ProfileStore.get(context) }
        assertFalse(AppEvents.entries.value.any { it.message == "STORE_SWEEP_SKIPPED" })
        assertFalse(pending.exists())
        val id = UUID.randomUUID()
        File(real, "profiles/$id.conf").writeText(Conf.fromKey("A", "ss://YWVzLTEyOC1nY206cGFzcw@example.com:443"))
        assertEquals(listOf(id.toString()) to false, store.load().let { (list, warning) -> list.map { it.profile.id } to warning })
        assertEquals(id, store.readForSession(id.toString()) { true }.id)
        val staged = store.resources.stage(SshProfileResources.Kind.IDENTITY, "key".byteInputStream())
        assertTrue(staged.file.path.startsWith(real.path + File.separator))
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(File(real, "profiles").toPath()))
    }
    @Test fun symlinkInsideTrustedRootIsStillRejected() = runBlocking {
        val (context, _, real) = packageContext()
        val outside = temp.newFolder("outside-profiles").canonicalFile
        val retained = File(outside, "profile-retain.tmp").apply { writeText("pending") }
        Files.createSymbolicLink(File(real, "profiles").toPath(), outside.toPath())
        AppEvents.clear()
        val store = withContext(Dispatchers.IO) { ProfileStore.get(context) }
        assertEquals(emptyList<StoredProfile>() to true, store.load())
        try { store.resources; fail("Unsafe imports exposed") } catch (_: IllegalStateException) {}
        assertTrue(store.compareAndSave(UUID.randomUUID(), UUID.randomUUID(), null, "x") is CommitResult.Conflict)
        assertEquals("pending", retained.readText())
        assertEquals(listOf(retained.name), outside.list()!!.toList())
        assertTrue(AppEvents.entries.value.any { it.message == "STORE_SWEEP_SKIPPED" })
    }
    @Test fun symlinkedFilesComponentBelowDataDirIsRejected() = runBlocking {
        val (context, _, real) = packageContext()
        val outside = temp.newFolder("outside-files").canonicalFile
        val retained = File(outside, "profiles").apply { mkdir() }.let { File(it, "profile-retain.tmp").apply { writeText("pending") } }
        check(real.delete())
        Files.createSymbolicLink(real.toPath(), outside.toPath())
        val store = withContext(Dispatchers.IO) { ProfileStore.get(context) }
        assertEquals(emptyList<StoredProfile>() to true, store.load())
        try { store.resources; fail("Unsafe imports exposed") } catch (_: IllegalStateException) {}
        assertEquals("pending", retained.readText())
        assertFalse(File(outside, "ssh-resources").exists())
    }
    @Test fun symlinkInSweptAreaBelowTrustedRootDeletesNothing() = runBlocking {
        val (context, _, real) = packageContext()
        val pending = File(real, "profiles").apply { mkdirs() }.let { File(it, "profile-retain.tmp").apply { writeText("pending") } }
        val outside = temp.newFolder("outside-ssconf").canonicalFile
        val foreign = File(outside, "${UUID.randomUUID()}.snap").apply { writeText("snapshot") }
        Files.createSymbolicLink(File(real, "ssconf").toPath(), outside.toPath())
        AppEvents.clear()
        withContext(Dispatchers.IO) { ProfileStore.get(context) }
        assertTrue(pending.exists()); assertTrue(foreign.exists())
        assertTrue(AppEvents.entries.value.any { it.message == "STORE_SWEEP_SKIPPED" })
    }
    private fun wrappedFilesDir(root: File) = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getFilesDir() = root
    }
    private fun inventory(root: File): Map<String, String> = root.walkTopDown().associate {
        it.relativeTo(root).path to if (it.isDirectory) "directory" else it.readText()
    }
    @Test fun initializationFailureKeepsPublicLoadingGateClosed() = runBlocking {
        val blocked = temp.newFile("blocked")
        val application = object : android.app.Application() {
            override fun getFilesDir() = blocked
            override fun getApplicationContext(): Context = this
            // The failed refresh resolves its error text; without resources that NPE leaks into the next runTest.
            override fun getResources() = ApplicationProvider.getApplicationContext<Context>().resources
        }
        val model = AppModel(application)
        val owner = androidx.lifecycle.ViewModelStore().apply { put("model", model) }
        try {
            val refresh = model.refresh()
            withTimeout(5000) {
                while (!refresh.isCompleted) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(model.loading.value)
            try { model.store; fail() } catch (_: IllegalStateException) {}
        } finally { owner.clear() }
    }
}
