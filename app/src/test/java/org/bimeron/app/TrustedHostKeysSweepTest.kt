package org.bimeron.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `trusted-hosts/` is its own top-level tree: the fail-closed singleton sweep neither inspects nor skips on it. */
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

    @Test fun trustedHostsTreeDoesNotDisturbTheSweep() = runBlocking<Unit> {
        val id = UUID.randomUUID()
        File(root, "profiles").apply { mkdir() }.let { File(it, "$id.conf") }.writeText(Conf.fromKey("P", "ssh://u@ssh.example.com:22"))
        val orphanTmp = File(root, "profiles/profile-1.tmp").apply { writeText("partial") }
        val trusted = File(root, "trusted-hosts").apply { mkdir() }
        val line = HostKeyFixtures.line("ssh.example.com:22")
        File(trusted, "$id.known_hosts").writeText("$line\n")
        val unexpected = File(trusted, "notes.txt").apply { writeText("retain") }
        AppEvents.clear()
        val store = withContext(Dispatchers.IO) { ProfileStore.get(context) }
        assertFalse(AppEvents.entries.value.any { it.message == "STORE_SWEEP_SKIPPED" })
        assertFalse(orphanTmp.exists())
        assertTrue(unexpected.exists())
        assertArrayEquals("$line\n".toByteArray(), store.trustedHostKeys(id))
    }
}
