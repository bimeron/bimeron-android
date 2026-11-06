package org.bimeron.app

import android.graphics.Rect
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProfileHoverTest {
    @Test fun hoverCoversWholeUnselectedRowAndDoesNotChangeSelectedRow() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val store = ProfileStore(app)
        val selectedProfile = store.save(Conf.fromKey("Selection fixture", "socks5://192.0.2.24:1080"))
        val profile = store.save(Conf.fromKey("Hover fixture", "socks5://192.0.2.25:1080"))
        val model = AppModel(app)
        withTimeout(10_000) { model.profiles.first { items -> items.any { it.id == profile.id } } }
        val originalSelection = model.selected.value
        try {
            model.select(selectedProfile.id)
            withTimeout(10_000) { model.selected.first { it == selectedProfile.id } }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
                // Resolved from the current app locale (CI applies "ru"), so the test works in any locale.
                // On short screens or with large fonts the toggle can start below the visible page.
                waitFor("scrollable page") { it.isScrollable }
                val expand = revealText(app.getString(R.string.expand))
                assertTrue(expand.ancestor("Expand toggle") { it.isClickable }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                val settingsLabel = app.getString(R.string.configure_profile, profile.name)
                // The unselected fixture row only exists once the list is expanded. Bring it fully on screen
                // first, then measure it once its layout has settled.
                val geometry = showRow(profile.name, settingsLabel)
                val rect = geometry.row
                val settings = geometry.settings
                var screen = Rect()
                var inset = 0
                scenario.onActivity {
                    val windowManager = it.getSystemService(android.view.WindowManager::class.java)
                    screen = if (android.os.Build.VERSION.SDK_INT >= 30) {
                        windowManager.maximumWindowMetrics.bounds
                    } else {
                        val size = android.graphics.Point()
                        @Suppress("DEPRECATION")
                        windowManager.defaultDisplay.getRealSize(size)
                        Rect(0, 0, size.x, size.y)
                    }
                    inset = (8 * it.resources.displayMetrics.density).toInt()
                }
                val bodyPoint = android.graphics.Point(rect.left + inset, rect.centerY())
                val settingsPoint = android.graphics.Point(settings.left + settings.width() / 8, settings.centerY())
                fun sample(bitmap: Bitmap, point: android.graphics.Point, region: Rect): Int {
                    assertTrue("Sample $point outside region $region", region.contains(point.x, point.y))
                    assertTrue("Sample $point outside screen $screen", screen.contains(point.x, point.y))
                    val x = ((point.x - screen.left).toDouble() * bitmap.width / screen.width()).toInt()
                    val y = ((point.y - screen.top).toDouble() * bitmap.height / screen.height()).toInt()
                    assertTrue("Mapped ($x,$y) outside ${bitmap.width}x${bitmap.height}",
                        x in 0 until bitmap.width && y in 0 until bitmap.height)
                    android.util.Log.i("HoverGeometry", "region=$region screen=$screen bitmap=${bitmap.width}x${bitmap.height} point=$point pixel=($x,$y)")
                    return bitmap.getPixel(x, y)
                }
                val before = ui.takeScreenshot()
                saveScreenshot(app, before, "hover-before.png")
                val baseLeft = sample(before, bodyPoint, rect)
                val baseRight = sample(before, settingsPoint, settings)

                // The baseline and the hover point are only meaningful for the row as measured.
                assertEquals("Profile row moved after it was measured", geometry, readableRow(profile.name, settingsLabel))
                hover(rect.centerX(), rect.centerY())
                val deadline = android.os.SystemClock.uptimeMillis() + 5_000
                var overBody = ui.takeScreenshot()
                while ((sample(overBody, bodyPoint, rect) == baseLeft ||
                        sample(overBody, settingsPoint, settings) == baseRight) &&
                    android.os.SystemClock.uptimeMillis() < deadline) {
                    overBody.recycle()
                    Thread.sleep(50)
                    overBody = ui.takeScreenshot()
                }
                saveScreenshot(app, overBody, "hover-row.png")
                assertNotEquals("Body did not render hover within 5s", baseLeft, sample(overBody, bodyPoint, rect))
                assertNotEquals("Settings region did not render row hover within 5s", baseRight, sample(overBody, settingsPoint, settings))

                hover(settings.centerX(), settings.centerY())
                Thread.sleep(200)
                val overSettings = ui.takeScreenshot()
                assertEquals(sample(overBody, bodyPoint, rect), sample(overSettings, bodyPoint, rect))
                assertEquals(sample(overBody, settingsPoint, settings), sample(overSettings, settingsPoint, settings))
                saveScreenshot(app, overSettings, "hover-settings.png")

                val selectedGeometry = showRow(selectedProfile.name, app.getString(R.string.configure_profile, selectedProfile.name))
                assertTrue("Selection fixture row is not checked", selectedGeometry.checked)
                val selectedRect = selectedGeometry.row
                val selectedSettings = selectedGeometry.settings
                val selectedPoint = android.graphics.Point(selectedRect.left + inset, selectedRect.centerY())
                val selectedSettingsPoint = android.graphics.Point(selectedSettings.left + selectedSettings.width() / 8, selectedSettings.centerY())
                val selectedBefore = ui.takeScreenshot()
                for (targetPoint in listOf(android.graphics.Point(selectedRect.centerX(), selectedRect.centerY()),
                    android.graphics.Point(selectedSettings.centerX(), selectedSettings.centerY()))) {
                    hover(targetPoint.x, targetPoint.y)
                    Thread.sleep(200)
                    val selectedAfter = ui.takeScreenshot()
                    assertEquals(sample(selectedBefore, selectedPoint, selectedRect), sample(selectedAfter, selectedPoint, selectedRect))
                    assertEquals(sample(selectedBefore, selectedSettingsPoint, selectedSettings), sample(selectedAfter, selectedSettingsPoint, selectedSettings))
                    saveScreenshot(app, selectedAfter, "hover-selected-${targetPoint.x}.png")
                }
            }
        } finally {
            if (originalSelection != null && model.profiles.value.any { it.id == originalSelection }) {
                model.select(originalSelection)
                withTimeout(10_000) { model.selected.first { it == originalSelection } }
            }
            store.delete(profile.id)
            store.delete(selectedProfile.id)
        }
    }

    private fun bounds(node: AccessibilityNodeInfo) = Rect().also { node.getBoundsInScreen(it) }

    /**
     * Searches the current tree, refreshing every visited node. UiAutomation's node cache can lag
     * behind Compose by seconds (observed up to 9 s after expanding the list on an API 34 emulator),
     * so cached reads may miss new rows or report pre-scroll bounds.
     */
    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null || !node.refresh()) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
        return null
    }

    private fun root() = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow

    private fun hasText(value: String): (AccessibilityNodeInfo) -> Boolean = { it.text?.toString() == value }

    /** Waits without touching the UI: scrolling here would move rows between measurement and hover. */
    private fun waitForText(value: String) = waitFor(value, predicate = hasText(value))

    private fun waitFor(what: String, timeoutMs: Long = 20_000, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            find(root(), predicate)?.let { return it }
            if (SystemClock.uptimeMillis() >= deadline) break
            Thread.sleep(100)
        }
        error("Element not found within ${timeoutMs}ms: $what; window: ${root()?.packageName}; text: ${allText(root())}")
    }

    private fun allText(node: AccessibilityNodeInfo?): String {
        if (node == null || !node.refresh()) return ""
        return buildString {
            node.text?.let { append(it).append(' ') }
            for (i in 0 until node.childCount) append(allText(node.getChild(i)))
        }
    }

    private fun AccessibilityNodeInfo.ancestor(what: String = "Profile row", predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var node: AccessibilityNodeInfo? = this
        while (node != null) { if (predicate(node)) return node; node = node.parent }
        error("$what not found above '$text'")
    }

    private fun AccessibilityNodeInfo.can(action: AccessibilityNodeInfo.AccessibilityAction) = refresh() && actionList.any { it.id == action.id }

    /** Layout of every node on screen (bounds and child count), used to detect that a scroll has finished. */
    private fun snapshot(node: AccessibilityNodeInfo?): String {
        if (node == null || !node.refresh()) return ""
        return buildString {
            append(bounds(node)).append('/').append(node.childCount).append(';')
            for (i in 0 until node.childCount) append(snapshot(node.getChild(i)))
        }
    }

    /** Waits until two consecutive fresh reads of the whole window agree, e.g. after a deliberate scroll. */
    private fun awaitStill(timeoutMs: Long = 5_000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var previous = snapshot(root())
        while (SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(100)
            val current = snapshot(root())
            if (current == previous) return
            previous = current
        }
        error("Window did not stop changing within ${timeoutMs}ms")
    }

    /**
     * Returns the node with [value], scrolling the page deliberately if it is not in the tree.
     * Compose leaves fully clipped nodes out of the accessibility tree, so an off-screen node cannot be
     * found by waiting. This runs before any measurement, and every scroll is followed by a settle wait.
     */
    private fun revealText(value: String): AccessibilityNodeInfo {
        // Let the previous action (launch, expand click) finish laying out before deciding to scroll.
        awaitStill()
        find(root(), hasText(value))?.let { return it }
        val page = find(root()) { it.isScrollable } ?: return waitForText(value)
        for (direction in listOf(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)) {
            for (step in 1..10) {
                if (!page.can(direction) || !page.performAction(direction.id)) break
                awaitStill()
                Log.i("HoverVisibility", "scrolled page ${direction.label ?: direction.id} looking for '$value'")
                find(root(), hasText(value))?.let { return it }
            }
        }
        return waitForText(value)
    }

    /** [content] is the union of the row's text, which the row centres vertically between equal paddings. */
    private data class RowGeometry(val row: Rect, val settings: Rect, val content: Rect, val checked: Boolean)

    private sealed interface RowRead {
        data class Read(val geometry: RowGeometry) : RowRead
        data class Unreadable(val reason: String) : RowRead
    }

    private fun textBounds(node: AccessibilityNodeInfo, into: Rect) {
        if (!node.refresh()) return
        if (!node.text.isNullOrEmpty()) bounds(node).takeUnless { it.isEmpty }?.let { into.union(it) }
        for (i in 0 until node.childCount) node.getChild(i)?.let { textBounds(it, into) }
    }

    /** One fresh read of a profile row and its settings action, or the reason it is not fully laid out. */
    private fun rowGeometry(name: String, settingsLabel: String): RowRead {
        val root = root() ?: return RowRead.Unreadable("no active window")
        val text = find(root, hasText(name)) ?: return RowRead.Unreadable("name not in tree")
        val row = runCatching { text.ancestor { it.isCheckable } }.getOrNull()?.takeIf { it.refresh() }
            ?: return RowRead.Unreadable("no checkable row above the name")
        val settings = find(root) { it.contentDescription?.toString() == settingsLabel }
            ?: return RowRead.Unreadable("settings action not in tree")
        if (!row.isVisibleToUser) return RowRead.Unreadable("row not visible to user")
        if (!settings.isVisibleToUser) return RowRead.Unreadable("settings action not visible to user")
        val rowBounds = bounds(row)
        val settingsBounds = bounds(settings)
        val content = Rect().also { textBounds(row, it) }
        if (rowBounds.isEmpty || settingsBounds.isEmpty) return RowRead.Unreadable("empty bounds: row=$rowBounds settings=$settingsBounds")
        // Both halves of one rendered row share its vertical span; anything else is a mid-layout read.
        if (rowBounds.top != settingsBounds.top || rowBounds.bottom != settingsBounds.bottom) {
            return RowRead.Unreadable("row and settings span different heights: row=$rowBounds settings=$settingsBounds")
        }
        // The settings action sits beside the row body, on either side depending on layout direction.
        if (settingsBounds.left < rowBounds.right && rowBounds.left < settingsBounds.right) {
            return RowRead.Unreadable("settings overlaps the row horizontally: row=$rowBounds settings=$settingsBounds")
        }
        if (content.isEmpty || !rowBounds.contains(content)) return RowRead.Unreadable("row text $content not inside row $rowBounds")
        return RowRead.Read(RowGeometry(rowBounds, settingsBounds, content, row.isChecked))
    }

    /** A fresh read that tolerates brief unreadable moments, for comparing against a settled measurement. */
    private fun readableRow(name: String, settingsLabel: String): RowGeometry {
        val deadline = SystemClock.uptimeMillis() + 2_000
        while (true) {
            when (val read = rowGeometry(name, settingsLabel)) {
                is RowRead.Read -> return read.geometry
                is RowRead.Unreadable -> if (SystemClock.uptimeMillis() >= deadline) error("Profile row unreadable before hover: $name: ${read.reason}")
            }
            Thread.sleep(100)
        }
    }

    /** Waits until two consecutive fresh reads of the row agree, so the layout has settled. */
    private fun stableRow(name: String, settingsLabel: String, timeoutMs: Long = 10_000): RowGeometry {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var previous: RowGeometry? = null
        var last: RowRead? = null
        while (true) {
            val current = rowGeometry(name, settingsLabel)
            last = current
            val geometry = (current as? RowRead.Read)?.geometry
            if (geometry != null && geometry == previous) return geometry
            previous = geometry
            if (SystemClock.uptimeMillis() >= deadline) break
            Thread.sleep(100)
        }
        val why = when (last) {
            is RowRead.Unreadable -> last.reason
            is RowRead.Read -> "still moving, last=${last.geometry}"
            null -> "never read"
        }
        error("Profile row did not settle within ${timeoutMs}ms: $name: $why; text: ${allText(root())}")
    }

    /**
     * Null when the row is drawn in full; otherwise which edge a scrollable ancestor clips. Reported
     * bounds are already clipped, and a shown row often sits exactly on the viewport edge, so comparing
     * against the viewport cannot tell the two apart. The row centres its text between equal vertical
     * paddings, so clipping shows up as unequal gaps above and below the text.
     */
    private fun clippedEdge(geometry: RowGeometry): String? {
        val tolerance = maxOf(2, InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density.toInt() * 2)
        val above = geometry.content.top - geometry.row.top
        val below = geometry.row.bottom - geometry.content.bottom
        return when {
            above + tolerance < below -> "top"
            below + tolerance < above -> "bottom"
            else -> null
        }
    }

    private fun AccessibilityNodeInfo.scrollableAncestor(): AccessibilityNodeInfo? {
        var node = parent
        while (node != null && !(node.refresh() && node.isScrollable)) node = node.parent
        return node
    }

    /**
     * Asks Compose to show the row. Compose handles ACTION_SHOW_ON_SCREEN by scrolling only the nearest
     * scrollable ancestor of the node that receives it, so a request on the row alone scrolls just the
     * profile list, never the page around it. The request therefore goes to every ancestor, outermost
     * first, so each nested scrollable is positioned before the one inside it, and to the row last.
     */
    private fun showOnScreen(row: AccessibilityNodeInfo): String {
        val chain = generateSequence(row) { it.parent }.toList().asReversed()
        val action = AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id
        return chain.count { it.refresh() && it.performAction(action) }.let { "$it of ${chain.size}" }
    }

    /**
     * Brings a profile row fully on screen, then measures it once it has settled. Scrolling is a
     * deliberate step here, never part of a wait: [stableRow] and the "row moved" check catch any
     * movement between this measurement and the hover.
     */
    private fun showRow(name: String, settingsLabel: String): RowGeometry {
        var geometry: RowGeometry? = null
        repeat(4) { attempt ->
            val row = revealText(name).ancestor { it.isCheckable }
            val clipped = geometry?.let(::clippedEdge)
            if (clipped == null) {
                Log.i("HoverVisibility", "'$name' show-on-screen performed=[${showOnScreen(row)}]")
            } else {
                // Fallback: scroll the nearest scrollable ancestor that can move towards the clipped edge.
                val direction = if (clipped == "top") AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD
                else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD
                var scrollable = row.scrollableAncestor()
                while (scrollable != null && !scrollable.can(direction)) scrollable = scrollable.scrollableAncestor()
                val scrolled = scrollable?.performAction(direction.id) ?: false
                Log.i("HoverVisibility", "'$name' clipped at $clipped; fallback scroll ${direction.id} on ${scrollable?.let(::bounds)} performed=$scrolled")
            }
            awaitStill()
            val settled = stableRow(name, settingsLabel)
            geometry = settled
            val edge = clippedEdge(settled)
            Log.i("HoverVisibility", "'$name' attempt=$attempt settled at $settled clipped=${edge ?: "no"}")
            if (edge == null) return settled
        }
        error("Profile row is still clipped at the ${geometry?.let(::clippedEdge)} after scrolling: $name: $geometry")
    }

    private fun hover(x: Int, y: Int) {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply { this.x = x.toFloat(); this.y = y.toFloat() }
        val now = android.os.SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, 1, arrayOf(pointer), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        assertTrue("Mouse hover injection failed at ($x,$y)", ui.injectInputEvent(event, true))
        event.recycle()
    }

    private fun saveScreenshot(app: android.app.Application, bitmap: Bitmap, name: String) {
        val dir = app.getExternalFilesDir("screenshots") ?: error("No screenshots directory")
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
