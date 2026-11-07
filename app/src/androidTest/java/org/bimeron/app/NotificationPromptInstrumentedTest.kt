package org.bimeron.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
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
 * POST_NOTIFICATIONS decision #1 on a device: the system prompt appears once after the first connect observed while
 * the Activity is resumed and never again, also after Activity recreation. Opening the app while already connected
 * does not prompt. The VPN state is published directly; no tunnel is started.
 */
@RunWith(AndroidJUnit4::class)
class NotificationPromptInstrumentedTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val ui get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val preferences get() = app.getSharedPreferences(NotificationPromptStore.FILE, Context.MODE_PRIVATE)
    private var savedFlag: Boolean? = null

    @Before fun saveFlag() {
        savedFlag = if (preferences.contains(NotificationPromptStore.ASKED)) preferences.getBoolean(NotificationPromptStore.ASKED, false) else null
        VpnLive.resetAfterProcessStart()
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

    private fun awaitResumed(scenario: ActivityScenario<MainActivity>, phase: String) {
        assertTrue("$phase: activity not resumed (${scenario.state})", waitFor(10_000) { scenario.state == Lifecycle.State.RESUMED })
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
    /**
     * Publishes [state] and lets the RESUMED collector observe it before the next value: a StateFlow collector may
     * otherwise conflate back-to-back values and never see this one.
     */
    private fun publishObserved(state: String) {
        VpnLive.state.value = VpnState(state)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(OBSERVE_MS)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test fun askedFlagPreventsPromptAcrossRecreation() {
        preferences.edit().putBoolean(NotificationPromptStore.ASKED, true).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitResumed(scenario, "launched")
            publishObserved("connected")
            assertNoPrompt("connected")
            scenario.recreate()
            awaitResumed(scenario, "recreated")
            publishObserved("disconnected")
            publishObserved("connected")
            assertNoPrompt("recreated")
        }
    }

    @Test fun firstConnectPromptsOnceThenOffersSettingsRow() {
        assumeTrue("runtime permission exists from API 33", Build.VERSION.SDK_INT >= 33)
        assumeTrue("permission must not be granted yet",
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        preferences.edit().remove(NotificationPromptStore.ASKED).commit()
        // Connected before the app is opened (connect then Home, or a background recovery reconnect).
        VpnLive.state.value = VpnState("connected")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitResumed(scenario, "launched")
            // The replayed "connected" is not an observed transition: no prompt, and the flag stays unset.
            assertNoPrompt("opened while connected")
            assertFalse("flag must stay unset after a replay", preferences.getBoolean(NotificationPromptStore.ASKED, false))
            // Not before connect: disconnected and connecting do not prompt.
            publishObserved("disconnected")
            assertNoPrompt("disconnected", 1_500)
            publishObserved("connecting")
            assertNoPrompt("connecting", 1_500)
            VpnLive.state.value = VpnState("connected")
            assertTrue("prompt after first observed connect (${activePackage()})", waitFor(10_000) { permissionPromptShown() })
            assertTrue("flag committed at launch", preferences.getBoolean(NotificationPromptStore.ASKED, false))
            // Dismiss without answering; the decision stays recorded.
            ui.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            assertNoPrompt("dismissed")
            awaitResumed(scenario, "dismissed")
            // A real second transition: disconnected is observed before connected is published.
            publishObserved("disconnected")
            assertNoPrompt("second disconnect", 1_000)
            publishObserved("connected")
            assertNoPrompt("second connect")
            scenario.recreate()
            awaitResumed(scenario, "recreated")
            assertNoPrompt("recreated")
            publishObserved("disconnected")
            publishObserved("connected")
            assertNoPrompt("connect after recreation")
            clickText(app.getString(R.string.settings))
            assertTrue("notifications-off row", waitText(app.getString(R.string.notifications_off_title)) != null)
        }
    }

    private companion object {
        /** Bounded settle time after the main thread is idle, so the collector has run for the published value. */
        const val OBSERVE_MS = 300L
    }
}
