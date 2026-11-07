package org.bimeron.app

import androidx.activity.compose.setContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EditorSecretMaskingTest {
    @Test fun secretsRevealRemaskAndResetOnProfileChange() {
        var passwordLabel = ""
        var show = ""
        var hide = ""
        val current = androidx.compose.runtime.mutableStateOf(EditorDraft(Profile("one", "One", "ss", "vpn",
            "[AccessKey]\nType = ss\nServer = host\nPort = 443\nMethod = aes-128-gcm\nPassword = fixture-secret\n")))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                passwordLabel = activity.getString(R.string.protocol_form_password)
                show = activity.getString(R.string.protocol_form_show)
                hide = activity.getString(R.string.protocol_form_hide)
                activity.setContent {
                    androidx.compose.material3.MaterialTheme {
                        androidx.compose.foundation.layout.Column { ProtocolSettings(current.value, true) }
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

    @Test fun rawProfilesAreMaskedUntilRevealedAndRemaskedOnTabReturn() {
        val fixtures = listOf("ss", "shadowsocks", "socks5", "ssh", "trojan", "future").map { type ->
            type to "[AccessKey]\nType = $type\nServer = host\nPort = 443\nMethod = aes-128-gcm\nPassword = fixture-secret\n"
        } + Conf.sources.map { source ->
            source to "[AccessKey]\n$source = fixture-secret\n"
        } + listOf("unknown-section" to "[FutureTransport]\nToken = fixture-secret\n")
        val current = androidx.compose.runtime.mutableStateOf<EditorDraft?>(null)
        var show = ""
        var hide = ""
        var network = ""
        var raw = ""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // The activity's own model (contract D5: one production store behind it).
            val model = scenario.loadedActivityModel()
            scenario.onActivity { activity ->
                show = activity.getString(R.string.protocol_form_show)
                hide = activity.getString(R.string.protocol_form_hide)
                network = activity.getString(R.string.editor_network)
                raw = activity.getString(R.string.editor_raw_config)
                activity.setContent {
                    androidx.compose.material3.MaterialTheme {
                        current.value?.let { draft ->
                            androidx.compose.runtime.key(draft.profile.id) {
                                androidx.compose.foundation.layout.Box(Modifier.semantics {
                                    contentDescription = "raw-mask-fixture:${draft.profile.id}"
                                }) {
                                    ProfileEditor(model, draft.profile, false, null, VpnState()) {}
                                }
                            }
                        }
                    }
                }
            }
            for ((type, text) in fixtures) {
                var phase = "install fixture"
                try {
                    scenario.onActivity {
                        val draft = EditorDraft(Profile(type, type, type, "vpn", text)).also { it.tab = 3 }
                        model.editorDraft = draft
                        current.value = draft
                    }
                    waitNode("$type: fixture installed") {
                        it.contentDescription?.toString() == "raw-mask-fixture:$type"
                    }
                    fun assertMasked(phase: String) {
                        waitNode("$type: $phase Show control") { it.text?.toString() == show }
                        waitAbsent { it.text?.contains("fixture-secret") == true }
                    }
                    fun reveal() {
                        click(waitNode("$type: explicit Show control") { it.text?.toString() == show })
                        waitNode("$type: revealed raw editor") {
                            it.isEditable && it.text?.contains("fixture-secret") == true
                        }
                        waitNode("$type: Hide control") { it.text?.toString() == hide }
                    }
                    phase = "initially masked"
                    assertMasked(phase)
                    phase = "first explicit reveal"
                    reveal()
                    phase = "explicit hide"
                    click(waitNode("$type: explicit Hide control") { it.text?.toString() == hide })
                    assertMasked("explicitly remasked")
                    phase = "second explicit reveal"
                    reveal()
                    phase = "leave raw tab"
                    click(waitNode("$type: Network tab") { it.text?.toString() == network })
                    waitAbsent { it.text?.toString() == hide || it.text?.contains("fixture-secret") == true }
                    phase = "return to raw tab"
                    click(waitNode("$type: Raw tab") { it.text?.toString() == raw })
                    assertMasked("masked after tab return")
                } catch (failure: Throwable) {
                    // Count structure only: never dump editor text or content descriptions.
                    var nodes = 0
                    var editable = 0
                    var visible = 0
                    AppWindowHelper.visit {
                        nodes++
                        if (it.isEditable) editable++
                        if (it.isVisibleToUser) visible++
                        false
                    }
                    throw AssertionError("$type: phase=$phase scenario=${scenario.state} " +
                        "nodes=$nodes editable=$editable visible=$visible", failure)
                }
            }
        }
    }
    @Test fun ssconfGeneralDispatchMasksSourceAndGatesInvalidSave() {
        for (sourceKey in listOf("SSConf", "Key")) {
            val source = "ssconf://host/fixture-secret with-space"
            val profile = Profile("ssconf-$sourceKey", "SSConf", "ssconf", "vpn",
                "[AccessKey]\n$sourceKey = $source\n")
            val draft = EditorDraft(profile)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val model = scenario.loadedActivityModel()
                var sourceLabel = ""
                var done = ""
                var invalid = ""
                scenario.onActivity { activity ->
                    sourceLabel = activity.getString(R.string.editor_key_or_link)
                    done = activity.getString(R.string.editor_done)
                    invalid = activity.getString(R.string.protocol_form_invalid)
                    model.editorDraft = draft
                    activity.setContent {
                        androidx.compose.material3.MaterialTheme {
                            ProfileEditor(model, profile, false, null, VpnState()) {}
                        }
                    }
                }
                waitNode("masked ssconf link field") { node ->
                    node.isEditable && node.isPassword &&
                        find(node) { it.text?.toString() == sourceLabel } != null
                }
                waitAbsent { it.text?.contains(source) == true }
                click(waitNode { it.text?.toString() == done })
                waitNode("local validation error") { it.text?.toString() == invalid }
                scenario.onActivity {
                    assertEquals(invalid, draft.error)
                    assertFalse(draft.validating)
                    assertEquals(sourceKey, draft.document.source()?.first)
                    assertEquals(source, draft.document.source()?.second)
                }
            }
        }
    }

    @Test fun ssconfOriginalAndCurrentSourcesProtectRawAndErrors() {
        val secret = "ssconf://host/fixture-secret?token=private"
        for (originalProtected in listOf(true, false)) {
            val protectedText = "[AccessKey]\nKey = $secret\n"
            val plainText = "[AccessKey]\nType = ssh\nServer = host\n"
            val profile = Profile("ssconf-$originalProtected", "SSConf", "ssconf", "vpn",
                if (originalProtected) protectedText else plainText)
            val draft = EditorDraft(profile).also {
                it.update(androidx.compose.ui.text.input.TextFieldValue(if (originalProtected) plainText else protectedText))
                it.tab = 3
                it.error = secret
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val model = scenario.loadedActivityModel()
                var show = ""
                var failed = ""
                scenario.onActivity { activity ->
                    show = activity.getString(R.string.protocol_form_show)
                    failed = activity.getString(R.string.protocol_form_failed)
                    model.editorDraft = draft
                    activity.setContent {
                        androidx.compose.material3.MaterialTheme {
                            ProfileEditor(model, profile, false, secret, VpnState()) {}
                        }
                    }
                }
                waitNode("protected raw reveal control") { it.text?.toString() == show }
                waitNode("sanitized error") { it.text?.toString() == failed }
                waitAbsent { it.isEditable || it.text?.contains(secret) == true }
            }
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
