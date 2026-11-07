package org.bimeron.app

import androidx.activity.compose.setContent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedSafetyEditorTest {
    @Test fun generalTypeMasksUnknownRawEditAndUndoRestoresSafeLabel() {
        val text = "[AccessKey]\nType = socks5\nServer = host\nPort = 1080\nPassword = fixture-secret\n"
        val profile = Profile("shared-safety", "Fixture", "socks5", "vpn", text)
        val draft = EditorDraft(profile)
        val sentinel = "sentinel/path?token=secret"
        var fallback = ""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // The activity's own model (contract D5: one production store behind it).
            val model = scenario.loadedActivityModel()
            scenario.onActivity { activity ->
                fallback = activity.getString(R.string.editor_parameters)
                model.editorDraft = draft
                activity.setContent {
                    androidx.compose.material3.MaterialTheme {
                        ProfileEditor(model, profile, false, null, VpnState()) {}
                    }
                }
            }
            waitNode { it.text?.toString() == "SOCKS5" }
            scenario.onActivity {
                draft.update(androidx.compose.ui.text.input.TextFieldValue(text.replace("socks5", sentinel)))
            }
            waitNode { it.text?.toString() == fallback }
            waitAbsent { it.text?.contains(sentinel) == true || it.text?.contains("fixture-secret") == true }
            assertTrue(ProtocolForms.protected(draft.document))
            scenario.onActivity { draft.undo() }
            waitNode { it.text?.toString() == "SOCKS5" }
            waitAbsent { it.text?.contains(sentinel) == true || it.text?.contains("fixture-secret") == true }
            assertEquals(text, draft.source.text)
            assertFalse(draft.dirty)
        }
    }

    private fun waitAbsent(predicate: (AccessibilityNodeInfo) -> Boolean) {
        AppWindowHelper.waitAbsent(predicate)
    }
    private fun waitNode(phase: String = "editor UI element", predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        return AppWindowHelper.waitNode(predicate)
    }
    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
}
