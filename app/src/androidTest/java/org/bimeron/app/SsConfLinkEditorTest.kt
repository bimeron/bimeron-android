package org.bimeron.app

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The editor's single "Key or link" field edits an ssconf link in place, and Done saves it (after real Core
 * validation) under the same AccessKey source key it was stored in.
 */
@RunWith(AndroidJUnit4::class)
class SsConfLinkEditorTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val name = "ssconf link editor fixture"

    @Test fun editedLinkIsSavedUnderItsSourceKey() = runBlocking {
        // The process-wide store that MainActivity's model also uses (contract D5: single writer).
        val store = sharedStore(app)
        store.deleteFixturesNamed(name)
        for (key in listOf("SSConf", "Key")) {
            val text = Conf.fromKey(name, "ssconf://old.invalid/fixture-secret")
            val profile = store.createFixture(if (key == "SSConf") text else text.replace("\nSSConf = ", "\n$key = "))
            assertEquals(key, ConfigDocument.parse(profile.text).source()?.first)
            val replacement = "ssconf://new.invalid/fixture-secret-$key"
            runThenCleanUp({
                var label = ""
                var done = ""
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    // The activity's own model opens the stored profile at its revision, as the UI would.
                    val model = scenario.loadedActivityModel()
                    checkNotNull(model.openStoredEditor(profile.id))
                    scenario.onActivity { activity ->
                        label = activity.getString(R.string.editor_key_or_link)
                        done = activity.getString(R.string.editor_done)
                        activity.setContent {
                            androidx.compose.material3.MaterialTheme {
                                ProfileEditor(model, profile, false, null, VpnState()) {}
                            }
                        }
                    }
                    val field = waitNode("$key: masked editable link field") { node ->
                        node.isEditable && node.isPassword && find(node) { it.text?.toString() == label } != null
                    }
                    assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, replacement)
                    }))
                    waitAbsent { it.text?.contains(replacement) == true }
                    click(waitNode("$key: Done") { it.text?.toString() == done })
                    val saved = withTimeout(20_000) {
                        while (true) {
                            val text = store.load().first.single { it.id == profile.id }.text
                            if (text.contains(replacement)) return@withTimeout text
                            delay(50)
                        }
                        @Suppress("UNREACHABLE_CODE") error("unreachable")
                    }
                    assertEquals(key to replacement, ConfigDocument.parse(saved).source())
                    assertEquals(1, ConfigDocument.parse(saved).values("AccessKey").size)
                }
            }) { runBlocking { store.deleteFixturesNamed(name) } }
        }
    }

    private fun waitNode(phase: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo = try {
        AppWindowHelper.waitNode(predicate)
    } catch (failure: Throwable) {
        throw AssertionError(phase, failure)
    }

    private fun waitAbsent(predicate: (AccessibilityNodeInfo) -> Boolean) = AppWindowHelper.waitAbsent(predicate)

    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
        return null
    }

    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
}
