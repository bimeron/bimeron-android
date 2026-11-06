package org.bimeron.app

import android.content.Context
import android.content.Intent
import androidx.compose.material3.Text
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.bimeron.app.AuthTestSupport.REQUEST_A
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Placement of the auth host in the real app root, and the notification tap target, on the JVM with Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppShellAuthHostTest {
    @get:Rule val compose = createComposeRule()
    @After fun reset() = VpnLive.resetAfterProcessStart()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val title get() = context.getString(R.string.ssh_auth_title)
    private val pageText = "page content"
    private fun dialogCount() = compose.onAllNodes(isDialog()).fetchSemanticsNodes().size
    private fun publishOffer() {
        val token = attempt()
        VpnLive.publishAuth(token, AuthOffer(token, SESSION, REQUEST_A, wire(REQUEST_A)))
    }

    @Test fun authDialogShowsOverLoadingScreen() {
        publishOffer()
        compose.setContent { AppShell(dark = false, loading = true) { Text(pageText) } }
        compose.waitForIdle()
        compose.onNodeWithText(pageText).assertDoesNotExist()
        compose.onNodeWithText(title).assertExists()
        assertEquals(1, dialogCount())
    }

    @Test fun authDialogShowsOverPages() {
        publishOffer()
        compose.setContent { AppShell(dark = false, loading = false) { Text(pageText) } }
        compose.waitForIdle()
        compose.onNodeWithText(pageText).assertExists()
        compose.onNodeWithText(title).assertExists()
        assertEquals(1, dialogCount())
    }

    @Test fun notificationReusesRunningActivity() {
        val saved = shadowOf(BimeronVpnService.openAppIntent(context)).savedIntent
        assertEquals(MainActivity::class.java.name, saved.component?.className)
        val required = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        assertTrue("flags=${Integer.toHexString(saved.flags)}", saved.flags and required == required)
    }
}
