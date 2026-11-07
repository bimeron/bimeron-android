package org.bimeron.app

import android.content.Context
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.bimeron.app.HostKeyFixtures.REQUEST_A
import org.bimeron.app.HostKeyFixtures.SESSION
import org.bimeron.app.HostKeyFixtures.offer
import org.bimeron.app.HostKeyFixtures.wire
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.util.Collections

/** The host-key trust dialog: rendering, FLAG_SECURE, and Trust/Cancel routing, on the JVM with Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SshHostKeyDialogTest {
    @get:Rule val compose = createComposeRule()
    @After fun reset() = VpnLive.resetAfterProcessStart()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val offer = offer("ssh.example.com:2222")
    private var wire by mutableStateOf<String?>(null)
    private var replyResult = true
    private val replies = mutableListOf<Pair<String, String>>()
    private val cancels = mutableListOf<String>()

    @Composable private fun Host() = MaterialTheme {
        SshAuthenticationHost(1L to SESSION, wire,
            reply = { _, r, w -> replies += r to w; replyResult },
            cancel = { _, r -> cancels += r; true })
    }
    private fun show() { wire = wire(offer); compose.setContent { Host() }; compose.waitForIdle() }
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun dialogCount() = compose.onAllNodes(isDialog()).fetchSemanticsNodes().size

    @Test fun rendersHostTypeFullFingerprintAndAdviceInSecureWindow() {
        show()
        compose.onNodeWithText(text(R.string.ssh_host_key_title)).assertExists()
        compose.onNodeWithText(text(R.string.ssh_auth_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.ssh_host_key_server, "ssh.example.com:2222")).assertExists()
        compose.onNodeWithText(text(R.string.ssh_host_key_type, "ssh-ed25519")).assertExists()
        compose.onNodeWithText(text(R.string.ssh_host_key_fingerprint_label)).assertExists()
        // The full fingerprint, never truncated.
        assertEquals(50, offer.fingerprint.length)
        compose.onNodeWithText(offer.fingerprint).assertExists()
        compose.onNodeWithText(text(R.string.ssh_host_key_verify_hint)).assertExists()
        compose.onNodeWithText(text(R.string.ssh_host_key_trust)).assertIsEnabled()
        compose.onNodeWithText(text(R.string.ssh_cancel)).assertIsEnabled()
        compose.onNodeWithText(text(R.string.ssh_reply)).assertDoesNotExist()
        // No answer field, and the persisted line itself is never shown.
        assertEquals(0, compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
        compose.onNodeWithText(offer.knownHostsLine, substring = true).assertDoesNotExist()
        val flags = ShadowDialog.getLatestDialog().window!!.attributes.flags
        assertTrue(flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    @Test fun englishCopyMatchesTheDecision() {
        assertEquals("New server", text(R.string.ssh_host_key_title))
        assertEquals("Trust", text(R.string.ssh_host_key_trust))
        assertEquals("Verify this fingerprint with the server administrator before trusting.", text(R.string.ssh_host_key_verify_hint))
    }

    @Test fun trustSendsFixedAcceptanceAndStaysBusyUntilWithdrawn() {
        show()
        compose.onNodeWithText(text(R.string.ssh_host_key_trust)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(REQUEST_A to "[\"accept\"]"), replies)
        assertTrue(cancels.isEmpty())
        compose.onNodeWithText(text(R.string.ssh_host_key_trust)).assertIsNotEnabled()
        wire = null; compose.waitForIdle()
        assertEquals(0, dialogCount())
    }

    @Test fun cancelDeclinesAndCloses() {
        show()
        compose.onNodeWithText(text(R.string.ssh_cancel)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(REQUEST_A), cancels)
        assertTrue(replies.isEmpty())
        assertEquals(0, dialogCount())
    }

    @Test fun backDeclines() {
        show()
        @Suppress("DEPRECATION") compose.runOnUiThread { ShadowDialog.getLatestDialog().onBackPressed() }
        compose.waitForIdle()
        assertEquals(listOf(REQUEST_A), cancels)
    }

    @Test fun rejectedTrustUsesExistingFailedState() {
        replyResult = false
        show()
        compose.onNodeWithText(text(R.string.ssh_host_key_trust)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.ssh_auth_reply_failed)).assertExists()
        compose.onNodeWithText(text(R.string.ssh_host_key_trust)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.ssh_cancel)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(REQUEST_A), cancels); assertEquals(0, dialogCount())
    }

    @Test fun appRootHostRoutesTrustAndCancelThroughTheEndpoint() {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val token = AuthTestSupport.attempt()
        VpnAuthEndpoint.register(token) { _, r, answers -> calls += "$r:${answers ?: "cancel"}"; true }
        VpnLive.publishAuth(token, AuthOffer(token, SESSION, REQUEST_A, wire(offer)))
        compose.setContent { MaterialTheme { VpnAuthHost() } }
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.ssh_host_key_trust)).performClick()
        compose.waitUntil(5000) { calls.isNotEmpty() }
        assertEquals(listOf("$REQUEST_A:[\"accept\"]"), calls)
    }
}
