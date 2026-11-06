package org.bimeron.app

import android.graphics.Rect
import android.graphics.Bitmap
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
                val expand = waitForText(app.getString(R.string.expand))
                assertTrue(expand.ancestor { it.isClickable }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                val settingsLabel = app.getString(R.string.configure_profile, profile.name)
                // The unselected fixture row only exists once the list is expanded; wait for its layout to settle.
                val geometry = stableRow(profile.name, settingsLabel)
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
                assertEquals("Profile row moved after it was measured", geometry, rowGeometry(profile.name, settingsLabel))
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

                val selectedGeometry = stableRow(selectedProfile.name, app.getString(R.string.configure_profile, selectedProfile.name))
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

    /** Waits without touching the UI: scrolling here would move rows between measurement and hover. */
    private fun waitForText(value: String): AccessibilityNodeInfo {
        repeat(200) {
            find(root()) { it.text?.toString() == value }?.let { return it }
            Thread.sleep(100)
        }
        error("Element not found: $value; window: ${root()?.packageName}; text: ${allText(root())}")
    }

    private fun allText(node: AccessibilityNodeInfo?): String {
        if (node == null || !node.refresh()) return ""
        return buildString {
            node.text?.let { append(it).append(' ') }
            for (i in 0 until node.childCount) append(allText(node.getChild(i)))
        }
    }

    private fun AccessibilityNodeInfo.ancestor(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var node: AccessibilityNodeInfo? = this
        while (node != null) { if (predicate(node)) return node; node = node.parent }
        error("Profile row not found")
    }

    private data class RowGeometry(val row: Rect, val settings: Rect, val checked: Boolean)

    /** One fresh read of a profile row and its settings action, or null while it is not fully laid out. */
    private fun rowGeometry(name: String, settingsLabel: String): RowGeometry? {
        val root = root() ?: return null
        val text = find(root) { it.text?.toString() == name } ?: return null
        val row = runCatching { text.ancestor { it.isCheckable } }.getOrNull()?.takeIf { it.refresh() } ?: return null
        val settings = find(root) { it.contentDescription?.toString() == settingsLabel } ?: return null
        if (!row.isVisibleToUser || !settings.isVisibleToUser) return null
        val rowBounds = bounds(row)
        val settingsBounds = bounds(settings)
        // Both halves of one rendered row share its vertical span; anything else is a mid-layout read.
        if (rowBounds.isEmpty || settingsBounds.isEmpty || rowBounds.top != settingsBounds.top ||
            rowBounds.bottom != settingsBounds.bottom || settingsBounds.left < rowBounds.right) return null
        return RowGeometry(rowBounds, settingsBounds, row.isChecked)
    }

    /** Waits until two consecutive fresh reads of the row agree, so the layout has settled. */
    private fun stableRow(name: String, settingsLabel: String): RowGeometry {
        var previous: RowGeometry? = null
        repeat(100) {
            val current = rowGeometry(name, settingsLabel)
            if (current != null && current == previous) return current
            previous = current
            Thread.sleep(100)
        }
        error("Profile row did not settle: $name; last=$previous; text: ${allText(root())}")
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
