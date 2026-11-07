package org.bimeron.app

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Trusted server keys: N — Forget" in the SSH section of the profile editor. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrustedHostKeysSectionTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = org.junit.rules.TemporaryFolder()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val id = UUID.randomUUID()
    private var stored = 2
    private var forgetFails = false
    private val forgotten = mutableListOf<UUID>()
    private var failures = 0

    private fun show(profileId: String = id.toString(), enabled: Boolean = true) = compose.setContent {
        MaterialTheme {
            TrustedHostKeysSection(profileId, count = { stored }, forget = {
                forgotten += it
                if (forgetFails) throw IOException("synthetic")
                stored = 0
            }, enabled = enabled, onFailure = { failures++ })
        }
    }
    private val forget get() = context.getString(R.string.ssh_trusted_host_keys_forget)
    private val confirmButton get() = androidx.compose.ui.test.hasText(forget) and
        androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.isDialog())

    @Test fun confirmedForgetClearsKeys() {
        show()
        compose.onNodeWithText(context.getString(R.string.ssh_trusted_host_keys_count, 2)).assertExists()
        compose.onNode(hasContentDescription(context.getString(R.string.ssh_trusted_host_keys_forget_description))).assertIsEnabled().performClick()
        compose.onNodeWithText(context.getString(R.string.ssh_trusted_host_keys_forget_title)).assertExists()
        assertTrue(forgotten.isEmpty())
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        compose.waitForIdle(); assertTrue(forgotten.isEmpty())
        compose.onNode(hasContentDescription(context.getString(R.string.ssh_trusted_host_keys_forget_description))).performClick()
        compose.onNode(confirmButton).performClick()
        compose.waitForIdle()
        assertEquals(listOf(id), forgotten)
        compose.onNodeWithText(context.getString(R.string.ssh_trusted_host_keys_count, 0)).assertExists()
        compose.onNodeWithText(forget).assertIsNotEnabled()
    }

    @Test fun failedForgetReportsAndRereads() {
        forgetFails = true
        show()
        compose.onNode(hasContentDescription(context.getString(R.string.ssh_trusted_host_keys_forget_description))).performClick()
        compose.onNode(confirmButton).performClick()
        compose.waitForIdle()
        assertEquals(1, failures)
        compose.onNodeWithText(context.getString(R.string.ssh_trusted_host_keys_count, 2)).assertExists()
    }

    @Test fun unreadableStoreOffersForgetToRecover() {
        var unreadable = true
        compose.setContent {
            MaterialTheme {
                TrustedHostKeysSection(id.toString(), count = { if (unreadable) throw IOException("bad line") else 0 },
                    forget = { forgotten += it; unreadable = false }, enabled = true, onFailure = { failures++ })
            }
        }
        compose.onNodeWithText(context.getString(R.string.ssh_trusted_host_keys_unreadable)).assertExists()
        compose.onNode(hasContentDescription(context.getString(R.string.ssh_trusted_host_keys_forget_description))).assertIsEnabled().performClick()
        compose.onNode(confirmButton).performClick()
        compose.waitForIdle()
        assertEquals(listOf(id), forgotten)
        compose.onNodeWithText(context.getString(R.string.ssh_trusted_host_keys_count, 0)).assertExists()
    }

    @Test fun countReloadsWhenRefreshKeyChanges() {
        var refresh by androidx.compose.runtime.mutableStateOf("connecting")
        compose.setContent {
            MaterialTheme { TrustedHostKeysSection(id.toString(), count = { stored }, forget = {}, enabled = true, onFailure = {}, refresh = refresh) }
        }
        compose.onNodeWithText(context.getString(R.string.ssh_trusted_host_keys_count, 2)).assertExists()
        stored = 3
        refresh = "connected"
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.ssh_trusted_host_keys_count, 3)).assertExists()
    }

    @Test fun countReloadsOnResume() {
        val owner = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry(this).apply { currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
            override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
        }
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                MaterialTheme { TrustedHostKeysSection(id.toString(), count = { stored }, forget = {}, enabled = true, onFailure = {}) }
            }
        }
        compose.onNodeWithText(context.getString(R.string.ssh_trusted_host_keys_count, 2)).assertExists()
        stored = 5
        compose.runOnUiThread { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.STARTED }
        compose.runOnUiThread { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.ssh_trusted_host_keys_count, 5)).assertExists()
    }

    @Test fun lockedEditorAndEmptyStoreDisableForget() {
        show(enabled = false)
        compose.onNodeWithText(forget).assertIsNotEnabled()
    }

    @Test fun unsavedProfileShowsNothing() {
        show(profileId = "")
        compose.onNodeWithText(forget).assertDoesNotExist()
    }

    @Test fun deletedProfileShowsZeroAndForgetStaysDisabled() {
        val base = temporary.root.canonicalFile
        val store = ProfileStore(null, java.io.File(base, "profiles").apply { mkdirs() }, SshProfileResources(java.io.File(base, "ssh-resources"))) {}
        val saved = kotlinx.coroutines.runBlocking {
            store.save(Conf.fromKey("SSH", "ssh://user@ssh.example.com:2222")).also {
                store.rememberTrustedHostKey(UUID.fromString(it.id), HostKeyFixtures.line())
                store.delete(it.id)
            }
        }
        compose.setContent {
            MaterialTheme { TrustedHostKeysSection(saved.id, store::trustedHostKeyCount, store::forgetTrustedHostKeys, true, onFailure = { failures++ }) }
        }
        compose.waitUntil(5000) { compose.onAllNodes(androidx.compose.ui.test.hasText(context.getString(R.string.ssh_trusted_host_keys_count, 0))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(forget).assertIsNotEnabled()
        assertEquals(0, failures)
        assertEquals(emptyList<String>(), java.io.File(base, "trusted-hosts").list()!!.toList())
    }

    @Test fun sshDocumentsAreRecognized() {
        assertTrue(sshDocument(ConfigDocument.parse(Conf.fromKey("S", "ssh://u@h:22"))))
        assertTrue(sshDocument(ConfigDocument.parse("[Profile]\nVersion = 1\nName = S\n\n[AccessKey]\nType = ssh\nServer = h\nUsername = u\n")))
        assertFalse(sshDocument(ConfigDocument.parse(Conf.fromKey("S", "ss://YWVzLTEyOC1nY206c2VjcmV0@example.com:8388"))))
    }
}
