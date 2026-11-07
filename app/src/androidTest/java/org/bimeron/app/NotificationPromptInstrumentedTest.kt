package org.bimeron.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * POST_NOTIFICATIONS decision #1 on a device: the system prompt appears once after the first connect and never again,
 * also after Activity recreation. The VPN state is published directly; no tunnel is started.
 */
@RunWith(AndroidJUnit4::class)
class NotificationPromptInstrumentedTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val ui get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val preferences get() = app.getSharedPreferences(NotificationPromptStore.FILE, Context.MODE_PRIVATE)
    private var savedFlag: Boolean? = null

    @Before fun saveFlag() {
        savedFlag = if (preferences.contains(NotificationPromptStore.ASKED)) preferences.getBoolean(NotificationPromptStore.ASKED, false) else null
    }

    @After fun restore() {
        VpnLive.resetAfterProcessStart()
        preferences.edit().apply { savedFlag?.let { putBoolean(NotificationPromptStore.ASKED, it) } ?: remove(NotificationPromptStore.ASKED) }.commit()
    }

    private fun activePackage(): String? = runCatching { ui.rootInActiveWindow?.packageName?.toString() }.getOrNull()
    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) { if (condition()) return true; SystemClock.sleep(100) }
        return condition()
    }
    private fun permissionPromptShown() = activePackage().let { it != null && it != app.packageName && it.contains("permissioncontroller") }
    /** The app window stays in front for [ms]: no permission prompt was launched. */
    private fun assertNoPrompt(phase: String, ms: Long = 3_000) {
        assertTrue("$phase: app window not active (${activePackage()})", waitFor(10_000) { activePackage() == app.packageName })
        assertFalse("$phase: permission prompt shown", waitFor(ms) { permissionPromptShown() })
    }
    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
            return null
        }
        return visit(ui.rootInActiveWindow)
    }
    private fun waitText(text: String): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        waitFor(10_000) { findNode { it.text?.toString()?.contains(text) == true }?.also { found = it } != null }
        return found
    }
    private fun clickText(text: String) {
        var node = checkNotNull(waitText(text)) { "node not found: $text" }
        while (!node.isClickable) node = checkNotNull(node.parent) { "no clickable ancestor: $text" }
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test fun askedFlagPreventsPromptAcrossRecreation() {
        preferences.edit().putBoolean(NotificationPromptStore.ASKED, true).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            VpnLive.state.value = VpnState("connected")
            assertNoPrompt("connected")
            scenario.recreate()
            assertNoPrompt("recreated")
        }
    }

    @Test fun firstConnectPromptsOnceThenOffersSettingsRow() {
        assumeTrue("runtime permission exists from API 33", Build.VERSION.SDK_INT >= 33)
        assumeTrue("permission must not be granted yet",
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        preferences.edit().remove(NotificationPromptStore.ASKED).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Not before connect: disconnected and connecting do not prompt.
            assertNoPrompt("disconnected", 1_500)
            VpnLive.state.value = VpnState("connecting")
            assertNoPrompt("connecting", 1_500)
            VpnLive.state.value = VpnState("connected")
            assertTrue("prompt after first connect (${activePackage()})", waitFor(10_000) { permissionPromptShown() })
            assertTrue("flag committed at launch", preferences.getBoolean(NotificationPromptStore.ASKED, false))
            // Dismiss without answering; the decision stays recorded.
            ui.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            assertNoPrompt("dismissed")
            VpnLive.state.value = VpnState("disconnected")
            VpnLive.state.value = VpnState("connected")
            assertNoPrompt("second connect")
            scenario.recreate()
            assertNoPrompt("recreated")
            clickText(app.getString(R.string.settings))
            assertTrue("notifications-off row", waitText(app.getString(R.string.notifications_off_title)) != null)
        }
    }
}
