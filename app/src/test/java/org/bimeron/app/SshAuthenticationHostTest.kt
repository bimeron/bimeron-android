package org.bimeron.app

import android.content.Context
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import kotlinx.coroutines.CancellationException
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
    private var cancelResult: suspend () -> Boolean = { true }
    private val replies = mutableListOf<String>()
    private val cancels = mutableListOf<String>()

    @Composable private fun Host() = MaterialTheme {
        SshAuthenticationHost(key, wire,
            reply = { _, r, _ -> replies += r; replyResult() },
            cancel = { _, r -> cancels += r; cancelResult() })
    }
    private fun show() = compose.setContent { Host() }
    private fun pressBack() {
        @Suppress("DEPRECATION") compose.runOnUiThread { ShadowDialog.getLatestDialog().onBackPressed() }
        compose.waitForIdle()
    }
    private fun answerLength() = compose.onNode(hasSetTextAction()).fetchSemanticsNode()
        .config[SemanticsProperties.EditableText].text.length
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
        pressBack()
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

    @Test fun keyChangeCancelsOldReplyAndStartsFreshController() {
        var oldReplyCancelled = false
        replyResult = {
            try { CompletableDeferred<Boolean>().await() }
            catch (e: CancellationException) { oldReplyCancelled = true; throw e }
        }
        wire = wire(); show()
        compose.onNodeWithText("Password").performTextInput("synthetic")
        compose.onNodeWithText(context.getString(R.string.ssh_reply)).performClick()
        compose.waitForIdle(); assertEquals(1, replies.size)
        key = 2L to session; compose.waitForIdle()
        // The old dialog scope is gone, so its pending reply cannot complete into any controller.
        assertTrue(oldReplyCancelled)
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithText(context.getString(R.string.ssh_reply)).assertIsEnabled()
        compose.onNodeWithText(failedHint).assertDoesNotExist()
        assertEquals(0, answerLength())
    }

    @Test fun acceptedReplyIgnoresBackUntilWireIsWithdrawn() {
        wire = wire(); show()
        compose.onNodeWithText(context.getString(R.string.ssh_reply)).performClick()
        compose.waitForIdle()
        pressBack()
        assertTrue(cancels.isEmpty()); assertEquals(1, dialogCount())
        wire = null; compose.waitForIdle()
        assertEquals(0, dialogCount()); assertTrue(cancels.isEmpty())
    }

    @Test fun rejectedReplyThenCancelCallsCancelOnce() {
        replyResult = { false }
        wire = wire(); show()
        compose.onNodeWithText(context.getString(R.string.ssh_reply)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.ssh_reply)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.ssh_cancel)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("b".repeat(48)), cancels); assertEquals(0, dialogCount())
    }

    @Test fun rejectedCancelShowsDedicatedMessage() {
        cancelResult = { false }
        wire = wire(); show()
        compose.onNodeWithText(context.getString(R.string.ssh_cancel)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.ssh_auth_cancel_failed)).assertExists()
        compose.onNodeWithText(failedHint).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.ssh_reply)).assertIsNotEnabled()
        // A second cancel closes the dialog without another host call.
        compose.onNodeWithText(context.getString(R.string.ssh_cancel)).performClick()
        compose.waitForIdle()
        assertEquals(1, cancels.size); assertEquals(0, dialogCount())
        wire = wire("c".repeat(48)); compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.ssh_auth_cancel_failed)).assertDoesNotExist()
        compose.onNodeWithText(failedHint).assertDoesNotExist()
    }

    @Test fun typedAnswersAreNotRestoredAfterRecreation() {
        wire = wire()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Host() }
        compose.onNodeWithText("Password").performTextInput("synthetic")
        assertEquals(9, answerLength())
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithText(title).assertExists()
        assertEquals(0, answerLength())
    }

    @Test fun endpointResultsPlugInAsAcceptedComparison() {
        // The MainActivity wiring shape: with no registered attempt the endpoint answers STALE, i.e. not accepted.
        wire = wire()
        compose.setContent {
            MaterialTheme {
                SshAuthenticationHost(key, wire,
                    reply = { s, r, w -> VpnAuthEndpoint.reply(1L, s, r, w) == AuthResult.ACCEPTED },
                    cancel = { s, r -> VpnAuthEndpoint.cancel(1L, s, r) == AuthResult.ACCEPTED })
            }
        }
        compose.onNodeWithText(context.getString(R.string.ssh_reply)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(failedHint).assertExists()
    }
}
