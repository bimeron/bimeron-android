package org.bimeron.app

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.bimeron.app.AuthTestSupport.REQUEST_A
import org.bimeron.app.AuthTestSupport.REQUEST_B
import org.bimeron.app.AuthTestSupport.SESSION
import org.bimeron.app.AuthTestSupport.attempt
import org.bimeron.app.AuthTestSupport.wire
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/** The app-root auth host wired to VpnLive.auth and VpnAuthEndpoint, rendered on the JVM with Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VpnAuthHostTest {
    @get:Rule val compose = createComposeRule()
    @After fun reset() = VpnLive.resetAfterProcessStart()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val title get() = context.getString(R.string.ssh_auth_title)
    private val replyLabel get() = context.getString(R.string.ssh_reply)
    private val failedHint get() = context.getString(R.string.ssh_auth_reply_failed)

    /** Records which token's sink received each call; answer text is not kept. */
    private val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private fun sink(token: Long) = object : AuthSink {
        override suspend fun deliver(sessionId: String, requestId: String, answers: String?): Boolean {
            calls += "$token:${if (answers == null) "cancel" else "reply"}:$requestId"
            return true
        }
    }
    private fun offer(token: Long, request: String = REQUEST_A) = AuthOffer(token, SESSION, request, wire(request))
    private fun dialogCount() = compose.onAllNodes(isDialog()).fetchSemanticsNodes().size
    private fun show(offers: MutableStateFlow<AuthOffer?>? = null) = compose.setContent {
        MaterialTheme { if (offers == null) VpnAuthHost() else VpnAuthHost(offers) }
    }
    private fun submit() {
        compose.onNodeWithText("Password:").performTextInput("synthetic")
        compose.onNodeWithText(replyLabel).performClick()
    }

    @Test fun publishedOfferShowsDialogAndNullHidesIt() {
        val token = attempt()
        show()
        compose.waitForIdle(); assertEquals(0, dialogCount())
        VpnLive.publishAuth(token, offer(token))
        compose.waitForIdle()
        compose.onNodeWithText(title).assertExists()
        VpnLive.publishAuth(token, null)
        compose.waitForIdle(); assertEquals(0, dialogCount())
        // A gate advance clears the offer atomically, so the dialog goes away too.
        VpnLive.publishAuth(token, offer(token, REQUEST_B))
        compose.waitForIdle(); assertEquals(1, dialogCount())
        VpnLive.nextAttempt()
        compose.waitForIdle(); assertEquals(0, dialogCount())
    }

    @Test fun replyUsesOfferTokenAndStaleResultMarksControllerFailed() {
        val token = attempt()
        VpnAuthEndpoint.register(token, sink(token))
        VpnLive.publishAuth(token, offer(token))
        show()
        submit()
        compose.waitUntil(5_000) { calls.isNotEmpty() }
        assertEquals(listOf("$token:reply:$REQUEST_A"), calls)

        // The next request under the same token, but the attempt's sink is gone: the endpoint answers STALE.
        VpnLive.publishAuth(token, offer(token, REQUEST_B))
        compose.waitForIdle()
        VpnAuthEndpoint.unregister(token)
        submit()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(failedHint)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(replyLabel).assertIsNotEnabled()
        assertEquals(1, calls.size)
    }

    @Test fun repliesNeverRouteToANewerTokenAndNewOfferGetsFreshController() {
        // The host still shows the old offer while the gate has already moved on.
        val old = attempt()
        val offers = MutableStateFlow<AuthOffer?>(offer(old))
        val token = VpnLive.nextAttempt()
        VpnLive.publish(VpnState("connecting", attemptProfileId = AuthTestSupport.PROFILE), token)
        VpnAuthEndpoint.register(token, sink(token))
        VpnLive.publishAuth(token, offer(token))
        show(offers)
        submit()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(failedHint)).fetchSemanticsNodes().isNotEmpty() }
        // Same session and request ids, but the old token is STALE: the newer attempt's sink never saw it.
        assertTrue(calls.isEmpty())

        offers.value = offer(token)
        compose.waitForIdle()
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithText(failedHint).assertDoesNotExist()
        compose.onNodeWithText(replyLabel).assertIsEnabled()
        submit()
        compose.waitUntil(5_000) { calls.isNotEmpty() }
        assertEquals(listOf("$token:reply:$REQUEST_A"), calls)
    }
}
