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
                Thread.sleep(300)
                val target = waitForText(profile.name)
                val row = target.ancestor { node -> node.isCheckable }
                val rect = Rect().also { row.getBoundsInScreen(it) }
                val settings = bounds(findDescription(ui.rootInActiveWindow,
                    app.getString(R.string.configure_profile, profile.name)) ?: error("Missing settings action"))
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

                val selected = findChecked(ui.rootInActiveWindow) ?: error("No selected profile")
                val selectedRect = bounds(selected)
                val selectedSettings = bounds(findDescription(ui.rootInActiveWindow,
                    app.getString(R.string.configure_profile, selectedProfile.name)) ?: error("Missing selected settings action"))
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

    private fun findDescription(node: AccessibilityNodeInfo?, value: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.contentDescription?.toString() == value) return node
        for (i in 0 until node.childCount) findDescription(node.getChild(i), value)?.let { return it }
        return null
    }

    private fun waitForText(value: String): AccessibilityNodeInfo {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        repeat(200) {
            val match = findByText(ui.rootInActiveWindow, value)
            if (match != null) return match
            if (it % 5 == 4) {
                findScrollable(ui.rootInActiveWindow)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                Thread.sleep(300)
            }
            Thread.sleep(100)
        }
        error("Element not found: $value; window: ${ui.rootInActiveWindow?.packageName}; text: ${allText(ui.rootInActiveWindow)}")
    }

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) findScrollable(node.getChild(i))?.let { return it }
        return null
    }

    private fun allText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        return buildString {
            node.text?.let { append(it).append(' ') }
            for (i in 0 until node.childCount) append(allText(node.getChild(i)))
        }
    }

    private fun findByText(node: AccessibilityNodeInfo?, value: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == value) return node
        for (i in 0 until node.childCount) findByText(node.getChild(i), value)?.let { return it }
        return null
    }

    private fun AccessibilityNodeInfo.ancestor(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var node: AccessibilityNodeInfo? = this
        while (node != null) { if (predicate(node)) return node; node = node.parent }
        error("Profile row not found")
    }

    private fun findChecked(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isCheckable && node.isChecked) return node
        for (i in 0 until node.childCount) findChecked(node.getChild(i))?.let { return it }
        return null
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
