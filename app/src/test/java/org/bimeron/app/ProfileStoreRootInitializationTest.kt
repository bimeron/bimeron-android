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
import org.junit.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileStoreRootInitializationTest {
    @get:Rule val temp = TemporaryFolder()
    @Before @After fun resetSingleton() {
        ProfileStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }
    @Test fun aliasPreservesExistingProfilesModeAndInventory() = checkAlias(true)
    @Test fun aliasDoesNotCreateProfilesAndPreservesInventory() = checkAlias(false)

    private fun checkAlias(existingProfiles: Boolean) = runBlocking {
        val outside = temp.newFolder("outside").canonicalFile
        val profiles = File(outside, "profiles")
        val mode = PosixFilePermissions.fromString("rwxr-xr-x")
        if (existingProfiles) {
            profiles.mkdir()
            Files.setPosixFilePermissions(profiles.toPath(), mode)
            File(profiles, "profile-retain.tmp").writeText("pending")
        }
        val snapshotTemp = File(outside, "ssconf/${UUID.randomUUID()}.snap.tmp-retain")
        snapshotTemp.parentFile!!.mkdir()
        snapshotTemp.writeText("snapshot")
        val resources = SshProfileResources(File(outside, "ssh-resources"))
        val generation = resources.prepare(UUID.randomUUID(), resources.stage(SshProfileResources.Kind.IDENTITY, "generation".byteInputStream()))
        val staged = resources.stage(SshProfileResources.Kind.IDENTITY, "staging".byteInputStream())
        val before = inventory(outside)
        val alias = File(temp.root.canonicalFile, "alias")
        Files.createSymbolicLink(alias.toPath(), outside.toPath())
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getFilesDir() = alias
        }
        AppEvents.clear()
        val store = withContext(Dispatchers.IO) { ProfileStore.get(context) }
        assertEquals(emptyList<StoredProfile>() to true, store.load())
        try { store.resources; fail("Unsafe imports exposed") } catch (_: IllegalStateException) {}
        if (existingProfiles) assertEquals(mode, Files.getPosixFilePermissions(profiles.toPath()))
        else assertFalse(profiles.exists())
        assertEquals(before, inventory(outside))
        assertTrue(File(generation.path).exists())
        assertTrue(staged.file.exists())
        assertTrue(snapshotTemp.exists())
        assertTrue(AppEvents.entries.value.any { it.message == "STORE_SWEEP_SKIPPED" })
    }
    private fun inventory(root: File) = root.walkTopDown().associate {
        it.relativeTo(root).path to if (it.isDirectory) "directory" else it.readText()
    }
}
