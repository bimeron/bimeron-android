package org.bimeron.app

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeoutException

internal object AppWindowHelper {
    /*
     * Only the active window root is observed. Enabling FLAG_RETRIEVE_INTERACTIVE_WINDOWS via
     * UiAutomation.setServiceInfo would expose other windows, but on API 34/35 emulators it left
     * the Compose accessibility tree frozen at its first observation (every later wait timed out,
     * including rootInActiveWindow), so the service info is deliberately left untouched.
     * A foreign or missing active window yields no roots and therefore never proves absence.
     */
    private fun roots(): List<AccessibilityNodeInfo> {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        return appWindowRoots(listOfNotNull(ui.rootInActiveWindow)) { it.packageName?.toString() }
    }

    /** Value-free description of the active window for timeout reports. */
    private fun describe(): String {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            ?: return "activeWindow=null"
        fun count(node: AccessibilityNodeInfo?, depth: Int): Int = if (node == null || depth > 40) 0 else
            1 + (0 until node.childCount).sumOf { count(node.getChild(it), depth + 1) }
        return "activeWindow=${root.windowId} package=${root.packageName} nodes=${count(root, 0)}"
    }

    private fun poll(): AppWindowPoll<AccessibilityNodeInfo> {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        return AppWindowPoll(
            roots = ::roots,
            now = SystemClock::uptimeMillis,
            idle = { budget ->
                try { ui.waitForIdle(budget, budget) } catch (_: TimeoutException) {
                    // A busy UI still gets another observation within the original deadline.
                }
            },
            find = ::find,
        )
    }

    fun waitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo =
        poll().waitNode(10_000, predicate) ?: error("App window node wait timed out (${describe()})")

    fun waitAbsent(predicate: (AccessibilityNodeInfo) -> Boolean) {
        check(poll().waitAbsent(10_000, predicate)) { "App window absence wait timed out (${describe()})" }
    }

    fun isAbsent(predicate: (AccessibilityNodeInfo) -> Boolean): Boolean = poll().isAbsent(predicate)

    fun assertAbsent(predicate: (AccessibilityNodeInfo) -> Boolean) {
        check(isAbsent(predicate)) { "App window absence not observed (${describe()})" }
    }

    fun visit(predicate: (AccessibilityNodeInfo) -> Boolean) {
        roots().forEach { find(it, predicate) }
    }

    private fun find(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { find(it, predicate) }?.let { return it }
        }
        return null
    }
}
