package org.bimeron.app

import android.content.Context
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** SshAuthenticationHost visibility, dismissal and result handling, rendered on the JVM with Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SshAuthenticationHostTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val session = "a".repeat(48)
    private fun wire(id: String = "b".repeat(48)) =
        """{"sessionId":"$session","requestId":"$id","kind":"password","prompts":["Password"],"echo":[false]}"""

    private var key by mutableStateOf<Any?>(1L to session)
    private var wire by mutableStateOf<String?>(null)
    private var replyResult: suspend () -> Boolean = { true }
    private val replies = mutableListOf<String>()
    private val cancels = mutableListOf<String>()

    private fun show() = compose.setContent {
        MaterialTheme {
            SshAuthenticationHost(key, wire,
                reply = { _, r, _ -> replies += r; replyResult() },
                cancel = { _, r -> cancels += r; true })
        }
    }
    private fun dialogCount() = compose.onAllNodes(isDialog()).fetchSemanticsNodes().size
    private val title get() = context.getString(R.string.ssh_auth_title)
    private val failedHint get() = context.getString(R.string.ssh_auth_reply_failed)

    @Test fun showsOnlyForKeyAndRealWireWithSecureWindow() {
        show()
        compose.waitForIdle(); assertEquals(0, dialogCount())
        wire = "null"; compose.waitForIdle(); assertEquals(0, dialogCount())
        wire = wire(); compose.waitForIdle()
        compose.onNodeWithText(title).assertExists()
        val flags = ShadowDialog.getLatestDialog().window!!.attributes.flags
        assertTrue(flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        wire = null; compose.waitForIdle(); assertEquals(0, dialogCount())
        wire = wire("c".repeat(48)); compose.waitForIdle(); assertEquals(1, dialogCount())
        key = null; compose.waitForIdle(); assertEquals(0, dialogCount())
    }

    @Test fun dismissCancelsAndHidesDialog() {
        wire = wire(); show()
        compose.onNodeWithText(title).assertExists()
        @Suppress("DEPRECATION") compose.runOnUiThread { ShadowDialog.getLatestDialog().onBackPressed() }
        compose.waitForIdle()
        assertEquals(listOf("b".repeat(48)), cancels); assertEquals(0, dialogCount())
    }

    @Test fun rejectedReplyShowsLocalizedHint() {
        replyResult = { false }
        wire = wire(); show()
        compose.onNodeWithText("Password").performTextInput("synthetic")
        compose.onNodeWithText(context.getString(R.string.ssh_reply)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("b".repeat(48)), replies)
        compose.onNodeWithText(failedHint).assertExists()
        // The rejected request offers only cancel; the next request keeps the hint until a new reply.
        wire = wire("c".repeat(48)); compose.waitForIdle()
        compose.onNodeWithText(failedHint).assertExists()
    }

    @Test fun keyChangeDropsInFlightResultOfOldController() {
        val release = CompletableDeferred<Boolean>()
        replyResult = { release.await() }
        wire = wire(); show()
        compose.onNodeWithText(context.getString(R.string.ssh_reply)).performClick()
        compose.waitForIdle(); assertEquals(1, replies.size)
        val old = key
        key = 2L to session; compose.waitForIdle()
        assertNotEquals(old, key)
        release.complete(false); compose.waitForIdle()
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithText(failedHint).assertDoesNotExist()
    }
}
