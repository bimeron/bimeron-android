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
class VlessProfileEditorTest {
    @Test fun secretsRevealRemaskAndResetOnProfileChange() {
        var passwordLabel = ""
        var show = ""
        var hide = ""
        val current = androidx.compose.runtime.mutableStateOf(EditorDraft(Profile("one", "One", "vless", "vpn",
            "[AccessKey]\nType = vless\nServer = host\nPort = 443\nID = fixture-secret\nEncryption = none\nNetwork = tcp\nSecurity = tls\n")))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                passwordLabel = activity.getString(R.string.vless_id)
                show = activity.getString(R.string.protocol_form_show)
                hide = activity.getString(R.string.protocol_form_hide)
                activity.setContent {
                    androidx.compose.material3.MaterialTheme {
                        androidx.compose.foundation.layout.Column {
                                androidx.compose.material3.TextButton(onClick = { current.value.set("AccessKey", "ID", "changed-fixture") }) { androidx.compose.material3.Text("Fixture edit") }
                                androidx.compose.material3.TextButton(onClick = { current.value.undo() }) { androidx.compose.material3.Text("Fixture undo") }
                                ProtocolSettings(current.value, true)
                            }
                    }
                }
            }
            fun passwordField(phase: String, revealed: Boolean): AccessibilityNodeInfo = waitNode(phase) { node ->
                node.isEditable && find(node) { it.text?.toString() == passwordLabel } != null &&
                    (if (revealed) node.text?.toString() == "fixture-secret" else node.isPassword && node.text?.toString() != "fixture-secret") &&
                    find(node) { it.text?.toString() == if (revealed) hide else show } != null
            }
            fun toggle(field: AccessibilityNodeInfo, label: String) {
                click(find(field) { it.text?.toString() == label } ?: error("Password toggle missing: $label"))
            }
            val masked = passwordField("initial Password masked with Show", false)
            waitAbsent { it.text?.toString() == "fixture-secret" }
            toggle(masked, show)
            val revealed = passwordField("first Password reveal with Hide", true)
            click(waitNode { it.text?.toString() == "Fixture edit" })
            waitNode { it.isEditable && it.text?.toString() == "changed-fixture" }
            click(waitNode { it.text?.toString() == "Fixture undo" })
            passwordField("undo restores ID", true)
            toggle(revealed, hide)
            val remasked = passwordField("Password remasked with Show", false)
            waitAbsent { it.text?.toString() == "fixture-secret" }
            toggle(remasked, show)
            passwordField("second Password reveal before profile change", true)
            scenario.onActivity { current.value = EditorDraft(current.value.profile.copy(id = "two")) }
            passwordField("new profile Password masked with Show", false)
            waitAbsent { it.text?.toString() == "fixture-secret" }
        }
    }

    @Test fun explicitAndUriRawDefaultsAreProtected() {
        for (type in listOf("vless", "uri-vless")) {
            val profile = Profile(type, type, type, "vpn",
                "[AccessKey]\n${if (type == "vless") "Type = vless\nID = fixture-secret\nEncryption = none\nNetwork = tcp\nSecurity = tls" else "VLESS = vless://fixture-secret@host:443?security=tls"}\n")
            var show = ""
            var hide = ""
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                // The activity's own model (contract D5: one production store behind it).
                val model = scenario.loadedActivityModel()
                scenario.onActivity { activity ->
                    show = activity.getString(R.string.protocol_form_show)
                    hide = activity.getString(R.string.protocol_form_hide)
                    model.editorDraft = EditorDraft(profile).also { it.tab = 3 }
                    activity.setContent {
                        androidx.compose.material3.MaterialTheme {
                            ProfileEditor(model, profile, false, null, VpnState()) {}
                        }
                    }
                }
                run {
                    val reveal = waitNode { it.text?.toString() == show }
                    AppWindowHelper.assertAbsent {
                        it.text?.contains("fixture-secret") == true
                    }
                    click(reveal)
                    waitNode { it.text?.contains("fixture-secret") == true }
                    click(waitNode { it.text?.toString() == hide })
                    waitNode { it.text?.toString() == show }
                    waitAbsent { it.text?.contains("fixture-secret") == true }
                    AppWindowHelper.assertAbsent {
                        it.text?.contains("fixture-secret") == true
                    }
                }
            }
        }
    }
    @Test fun advancedOptionsUseRawFallbackWithoutMutatingDraft() {
        val text = "[AccessKey]\nType = vless\nEncryption = none\nNetwork = xhttp\nSecurity = tls\nXHTTPMode = auto\nXHTTPExtra = {malformed-secret\n"
        val draft = EditorDraft(Profile("raw-fixture", "Fixture", "vless", "vpn", text))
        var rawLabel = ""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                rawLabel = activity.getString(R.string.protocol_form_raw_only)
                activity.setContent {
                    androidx.compose.material3.MaterialTheme {
                        androidx.compose.foundation.layout.Column { ProtocolSettings(draft, true) }
                    }
                }
            }
            waitNode { it.text?.toString() == rawLabel }
            waitAbsent { it.isEditable || it.text?.contains("malformed-secret") == true }
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
