package org.bimeron.app

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShadowTlsProfileEditorTest {
    private val inner = "[AccessKey]\nType = ssh\nServer = host\nPort = 22\nUsername = user\nPassword = inner-secret\n"
    private val wrapper = "[ShadowTLS]\nServerName = cover.example\nPassword = wrapper-secret\n"

    @Test fun unsupportedProfileWithoutWrapperKeepsNetworkFieldsVisible() {
        val text = Conf.fromKey("Network fixture", "socks5://192.0.2.10:1080")
        val profile = Profile("no-shadow", "Network fixture", "socks5", "vpn", text)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // The activity's own model (contract D5: one production store behind it).
            val model = scenario.loadedActivityModel()
            var interfaceName = ""
            var shadowTitle = ""
            scenario.onActivity { activity ->
                interfaceName = activity.getString(R.string.editor_interface_name)
                shadowTitle = activity.getString(R.string.shadowtls_title)
                model.editorDraft = EditorDraft(profile).also { it.tab = 1 }
                activity.setContent { MaterialTheme { ProfileEditor(model, profile, false, null, VpnState()) {} } }
            }
            waitNode { it.text?.toString()?.contains(interfaceName) == true }
            AppWindowHelper.assertAbsent { it.text?.toString() == shadowTitle }
            scenario.onActivity { org.junit.Assert.assertEquals(text, model.editorDraft?.source?.text) }
        }
    }

    @Test fun originalAndCurrentWrappersProtectRawAndErrors() {
        for (originalWrapper in listOf(false, true)) {
            val profile = Profile("shadow", "Shadow", "ssh", "vpn", inner + if (originalWrapper) wrapper else "")
            val draft = EditorDraft(profile).also {
                it.update(TextFieldValue(inner + if (originalWrapper) "" else wrapper))
                it.tab = 3
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val model = scenario.loadedActivityModel()
                var show = ""
                var failed = ""
                scenario.onActivity { activity ->
                    show = activity.getString(R.string.protocol_form_show)
                    failed = activity.getString(R.string.protocol_form_failed)
                    model.editorDraft = draft
                    activity.setContent { MaterialTheme { ProfileEditor(model, profile, false, "wrapper-secret", VpnState()) {} } }
                }
                waitNode { it.text?.toString() == show }
                waitNode { it.text?.toString() == failed }
                AppWindowHelper.assertAbsent { it.isEditable || it.text?.contains("wrapper-secret") == true || it.text?.contains("inner-secret") == true }
            }
        }
    }

    @Test fun invalidWrapperBlocksDoneAndValidationWithSafeError() {
        val profile = Profile("invalid-shadow", "Shadow", "ssh", "vpn", inner + "[ShadowTLS]\nServerName = invalid name\nPassword = wrapper-secret\n")
        val draft = EditorDraft(profile).also { it.tab = 3 }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val model = scenario.loadedActivityModel()
            var done = ""
            var validate = ""
            var invalid = ""
            scenario.onActivity { activity ->
                done = activity.getString(R.string.editor_done)
                validate = activity.getString(R.string.editor_validate)
                invalid = activity.getString(R.string.shadowtls_invalid)
                model.editorDraft = draft
                activity.setContent { MaterialTheme { ProfileEditor(model, profile, false, null, VpnState()) { error("Invalid draft left editor") } } }
            }
            for (label in listOf(done, validate)) {
                click(waitNode { it.text?.toString() == label })
                waitNode { it.text?.toString() == invalid }
                scenario.onActivity {
                    org.junit.Assert.assertEquals(invalid, draft.error)
                    assertFalse(draft.validating)
                }
            }
        }
    }

    @Test fun uriUpstreamStillOffersCompatibilityBlock() {
        val profile = Profile("uri-shadow", "Shadow", "ssh", "vpn", "[AccessKey]\nSSH = ssh://user:secret@host:22\n" + wrapper)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val model = scenario.loadedActivityModel()
            var raw = ""
            scenario.onActivity { activity ->
                raw = activity.getString(R.string.shadowtls_raw)
                model.editorDraft = EditorDraft(profile).also { it.tab = 1 }
                activity.setContent { MaterialTheme { ProfileEditor(model, profile, false, null, VpnState()) {} } }
            }
            click(waitNode { it.text?.toString() == raw })
            scenario.onActivity { org.junit.Assert.assertEquals(3, model.editorDraft?.tab) }
        }
    }

    private fun waitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo =
        AppWindowHelper.waitNode(predicate)
    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
}
