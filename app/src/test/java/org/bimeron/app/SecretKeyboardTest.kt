package org.bimeron.app

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fields that can hold a secret ask for a keyboard that neither learns nor predicts: autocorrect and
 * capitalization off, IME_FLAG_NO_PERSONALIZED_LEARNING and TYPE_TEXT_FLAG_NO_SUGGESTIONS. The EditorInfo is
 * read from the focused field's real InputConnection request, so the shared [SecretInput] wiring is covered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecretKeyboardTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val owner = ViewModelStore()
    private val editable = SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)
    private val masked = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)

    @After fun clear() = owner.clear()

    private fun model(): AppModel {
        val root = temp.root.canonicalFile
        val application = object : android.app.Application() {
            override fun getFilesDir() = root
            override fun getApplicationInfo() = context.applicationInfo
            override fun getApplicationContext(): Context = this
            override fun getResources() = context.resources
            override fun getSystemService(name: String): Any? = context.getSystemService(name)
        }
        return AppModel(application).also { owner.put("model", it) }
    }

    private fun profile(text: String) = Profile(UUID.randomUUID().toString(), "Fixture", "fixture", "vpn", text)

    private fun rootViews(): List<View> {
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        val views = global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(global)
        @Suppress("UNCHECKED_CAST") return (views as List<View>).toList()
    }

    private fun View.withDescendants(): Sequence<View> = sequence {
        yield(this@withDescendants)
        if (this@withDescendants is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).withDescendants())
    }

    /** Focuses [field] and returns the EditorInfo its text input session hands to the IME. */
    private fun editorInfo(field: SemanticsNodeInteraction): EditorInfo {
        field.requestFocus()
        compose.waitForIdle()
        var info: EditorInfo? = null
        compose.runOnUiThread {
            val editor = rootViews().flatMap { it.withDescendants() }.filter { it.isShown && it.onCheckIsTextEditor() }.single()
            info = EditorInfo().also { assertNotNull(editor.onCreateInputConnection(it)) }
        }
        return checkNotNull(info)
    }

    private fun EditorInfo.learns() = imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0
    private fun EditorInfo.variation() = inputType and InputType.TYPE_MASK_VARIATION

    private fun assertSecret(name: String, info: EditorInfo, variation: Int, multiLine: Boolean = false) {
        assertFalse("$name: personalized learning is off", info.learns())
        assertEquals("$name: text class", InputType.TYPE_CLASS_TEXT, info.inputType and InputType.TYPE_MASK_CLASS)
        assertTrue("$name: no suggestions", info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
        assertEquals("$name: no autocorrect", 0, info.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        assertEquals("$name: no capitalization", 0, info.inputType and (InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
            InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES))
        assertEquals("$name: variation", variation, info.variation())
        assertEquals("$name: multi-line", multiLine, info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
    }

    @Test fun everyKindTurnsOffAutocorrectAndCapitalization() {
        assertEquals(setOf(SecretKeyboard.Clear, SecretKeyboard.Link, SecretKeyboard.MultiLine),
            SecretKeyboard.entries.filter { it.visiblePassword }.toSet())
        val expected = mapOf(SecretKeyboard.Masked to KeyboardType.Password, SecretKeyboard.Clear to KeyboardType.Text,
            SecretKeyboard.Number to KeyboardType.Number, SecretKeyboard.Link to KeyboardType.Uri, SecretKeyboard.MultiLine to KeyboardType.Text)
        assertEquals(SecretKeyboard.entries.toSet(), expected.keys)
        for ((kind, type) in expected) {
            assertEquals(kind.name, type, kind.options.keyboardType)
            assertFalse(kind.name, kind.options.autoCorrectEnabled == true)
            assertEquals(kind.name, KeyboardCapitalization.None, kind.options.capitalization)
        }
    }

    @Test fun editorInfoGetsNoLearningAndNoSuggestions() {
        val text = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        secretEditorInfo(text, SecretKeyboard.MultiLine)
        assertEquals(EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, text.imeOptions)
        assertEquals(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, text.inputType)
        val password = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        secretEditorInfo(password, SecretKeyboard.Masked)
        assertEquals(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, password.inputType)
        val number = EditorInfo().apply { inputType = InputType.TYPE_CLASS_NUMBER }
        secretEditorInfo(number, SecretKeyboard.Number)
        assertEquals(InputType.TYPE_CLASS_NUMBER, number.inputType)
        assertEquals(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, number.imeOptions)
    }

    /** Every form text field the form masks or the conflict panel classes as secret gets a secret keyboard. */
    @Test fun protocolFieldClassificationFollowsMaskingAndMergeSecrets() {
        val descriptors = listOf(ShadowsocksForm, Socks5Form, SshProtocolForm, VlessProtocolForm, HttpConnectForm,
            NaiveH2Form, MasqueForm, TrojanProtocolForm)
        for (field in descriptors.flatMap { it.fields }) {
            val name = "${field.key} (${field.labelRes})"
            when {
                field.options != null -> assertNull(name, field.secretKeyboard)
                field.secret -> assertEquals(name, SecretKeyboard.Masked, field.secretKeyboard)
                ProfileMerge.isSecret("AccessKey", field.key) -> assertNotNull(name, field.secretKeyboard)
                field.keyboardType != ProtocolKeyboardType.PASSWORD -> assertNull(name, field.secretKeyboard)
            }
        }
        assertNull(ShadowsocksForm.fields.single { it.key == "Server" }.secretKeyboard)
        assertNull(ShadowsocksForm.fields.single { it.key == "Port" }.secretKeyboard)
        assertEquals(SecretKeyboard.Clear, ShadowsocksForm.fields.single { it.key == "Prefix" }.secretKeyboard)
        assertEquals(SecretKeyboard.Number, VlessProtocolForm.fields.single { it.key == "WSEarlyData" }.secretKeyboard)
    }

    @Test fun protocolFormFieldsUseTheirKeyboard() {
        val fixtures = listOf(
            "Type = ss\nServer = host\nPort = 443\nMethod = aes-128-gcm\nPassword = fixture-secret",
            "Type = socks5\nServer = host\nPort = 1080\nUsername = user\nPassword = fixture-secret",
            "Type = ssh\nServer = host\nPort = 22\nUsername = user",
            "Type = vless\nServer = host\nPort = 443\nID = fixture-secret\nEncryption = none\nNetwork = tcp\nSecurity = reality",
            "Type = vless\nServer = host\nPort = 443\nID = fixture-secret\nEncryption = none\nNetwork = ws\nSecurity = tls",
            "Type = vless\nServer = host\nPort = 443\nID = fixture-secret\nEncryption = none\nNetwork = grpc\nSecurity = tls",
            "Type = vless\nServer = host\nPort = 443\nID = fixture-secret\nEncryption = none\nNetwork = xhttp\nSecurity = tls",
            "Type = http\nServer = host\nPort = 8080\nUsername = user\nPassword = fixture-secret",
            "Type = naive\nServer = host\nPort = 443\nUsername = user\nPassword = fixture-secret",
            "Type = masque\nServer = host\nPort = 443\nUsername = user\nPassword = fixture-secret",
            "Type = trojan\nServer = host\nPort = 443\nPassword = fixture-secret",
        ).map { EditorDraft(profile("[AccessKey]\n$it\n")) }
        val current = mutableStateOf(fixtures.first())
        compose.setContent { MaterialTheme { Surface { Column { ProtocolSettings(current.value, true) } } } }
        var checked = 0
        for (draft in fixtures) {
            compose.runOnIdle { current.value = draft }
            val doc = draft.document
            val descriptor = checkNotNull(ProtocolForms.descriptor(doc))
            assertFalse(doc.render(), ProtocolForms.rawOnly(doc))
            for (field in descriptor.fields(doc).filter { it.options == null }) {
                val label = context.getString(field.labelRes)
                val name = "${doc.value("AccessKey", "Type")} ${field.key}"
                val info = editorInfo(compose.onNode(editable and hasText(label)))
                when (field.secretKeyboard) {
                    null -> assertTrue("$name: public field keeps learning", info.learns())
                    SecretKeyboard.Masked -> assertSecret(name, info, InputType.TYPE_TEXT_VARIATION_PASSWORD)
                    SecretKeyboard.Clear -> assertSecret(name, info, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                    SecretKeyboard.Number -> {
                        assertFalse("$name: personalized learning is off", info.learns())
                        assertEquals(name, InputType.TYPE_CLASS_NUMBER, info.inputType and InputType.TYPE_MASK_CLASS)
                    }
                    else -> error("$name: unexpected keyboard ${field.secretKeyboard}")
                }
                checked++
            }
        }
        assertTrue("checked $checked fields", checked >= 40)
    }

    @Test fun otherUriSecretsAreMaskedKeyboardsAndHostStaysPublic() {
        val draft = EditorDraft(profile("[AccessKey]\nHysteria2 = \"hy2://p%40ss@example.com:443?sni=example.com#inner\"\n"))
        compose.setContent { MaterialTheme { Surface { Column { OtherUriProtocolSettings(draft, true) } } } }
        assertSecret("password", editorInfo(compose.onNode(editable and hasText(context.getString(R.string.other_uri_password)))),
            InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertSecret("sni", editorInfo(compose.onNode(editable and hasText(context.getString(R.string.other_uri_option) + " (sni)"))),
            InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertTrue("host", editorInfo(compose.onNode(editable and hasText(context.getString(R.string.other_uri_host)))).learns())
    }

    @Test fun shadowTlsFieldsAreMaskedKeyboards() {
        val draft = EditorDraft(profile("[AccessKey]\nType = ss\nServer = host\nPort = 443\nMethod = aes-256-gcm\nPassword = inner\n" +
            "[ShadowTLS]\nServerName = cover.invalid\nPassword = fixture-secret\n"))
        compose.setContent { MaterialTheme { Surface { Column { ShadowTlsSettings(draft, true) } } } }
        for (label in listOf(R.string.shadowtls_name, R.string.shadowtls_password)) {
            assertSecret(context.getString(label), editorInfo(compose.onNode(editable and hasText(context.getString(label)))),
                InputType.TYPE_TEXT_VARIATION_PASSWORD)
        }
    }

    @Test fun editorKeyOrLinkStaysMaskedWithAPasswordKeyboard() {
        val model = model()
        val profile = profile("[Profile]\nName = Link\n[AccessKey]\nSSConf = ssconf://config.example/fixture-secret\n")
        compose.setContent { MaterialTheme(colorScheme = lightScheme) { Surface { ProfileEditor(model, profile, false, null, VpnState()) {} } } }
        val field = compose.onNode(editable and hasText(context.getString(R.string.editor_key_or_link)))
        field.assert(masked)
        assertSecret("key or link", editorInfo(field), InputType.TYPE_TEXT_VARIATION_PASSWORD)
        field.assert(masked)
    }

    @Test fun rawConfigIsMultiLineWithoutLearning() {
        val model = model()
        val profile = profile("[Profile]\nName = Raw\n[AccessKey]\nType = ss\nServer = host\nPort = 443\nMethod = aes-128-gcm\nPassword = fixture-secret\n")
        compose.setContent { MaterialTheme(colorScheme = lightScheme) { Surface { ProfileEditor(model, profile, false, null, VpnState()) {} } } }
        compose.runOnIdle { checkNotNull(model.editorDraft).tab = 3 }
        compose.onNodeWithText(context.getString(R.string.protocol_form_show)).performClick()
        val info = editorInfo(compose.onNode(editable and hasText(context.getString(R.string.editor_raw_config))))
        assertSecret("raw config", info, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, multiLine = true)
    }

    @Test fun addPageKeyIsAUriKeyboardWithoutLearningAndNameIsPublic() {
        val model = model()
        compose.setContent { MaterialTheme(colorScheme = lightScheme) { Surface { AddPage(model, false, null, {}, {}) } } }
        assertSecret("add key", editorInfo(compose.onNode(editable and hasText(context.getString(R.string.key_or_link)))),
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, multiLine = true)
        assertTrue("profile name", editorInfo(compose.onNode(editable and hasText(context.getString(R.string.profile_name)))).learns())
    }

    @Test fun sshAuthenticationAnswerIsAMaskedKeyboard() {
        val session = "a".repeat(48)
        val wire = mutableStateOf<String?>(null)
        compose.setContent { MaterialTheme { SshAuthenticationHost(1L to session, wire.value, reply = { _, _, _ -> true }, cancel = { _, _ -> true }) } }
        compose.waitForIdle()
        wire.value = """{"sessionId":"$session","requestId":"${"b".repeat(48)}","kind":"password","prompts":["Password"],"echo":[false]}"""
        compose.waitForIdle()
        assertSecret("ssh answer", editorInfo(compose.onNode(editable)), InputType.TYPE_TEXT_VARIATION_PASSWORD)
    }
}
