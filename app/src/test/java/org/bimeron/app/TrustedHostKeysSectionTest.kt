package org.bimeron.app

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    @Test fun lockedEditorAndEmptyStoreDisableForget() {
        show(enabled = false)
        compose.onNodeWithText(forget).assertIsNotEnabled()
    }

    @Test fun unsavedProfileShowsNothing() {
        show(profileId = "")
        compose.onNodeWithText(forget).assertDoesNotExist()
    }

    @Test fun sshDocumentsAreRecognized() {
        assertTrue(sshDocument(ConfigDocument.parse(Conf.fromKey("S", "ssh://u@h:22"))))
        assertTrue(sshDocument(ConfigDocument.parse("[Profile]\nVersion = 1\nName = S\n\n[AccessKey]\nType = ssh\nServer = h\nUsername = u\n")))
        assertFalse(sshDocument(ConfigDocument.parse(Conf.fromKey("S", "ss://YWVzLTEyOC1nY206c2VjcmV0@example.com:8388"))))
    }
}
