package org.bimeron.app

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The ssconf link is shown once, in the editor's "Key or link" field, which edits it in place under the key
 * it is stored in (`SSConf` or `Key`). It stays masked until revealed, can be copied, and shows the local
 * format hint and check. Other sources keep a read-only field.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class SsConfLinkFieldTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val owner = ViewModelStore()
    private val link = "ssconf://config.example/fixture-secret"
    private val keyOrLink get() = context.getString(R.string.editor_key_or_link)

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

    private fun profile(key: String, value: String = link) =
        Profile(UUID.randomUUID().toString(), "Link", "SSConf", "vpn", "[Profile]\nName = Link\n[AccessKey]\n$key = $value\n")

    private var shown: androidx.compose.runtime.MutableState<Profile>? = null
    private var model: AppModel? = null

    /** Renders the editor for [profile] (content is set once per test, then switched) and returns its draft. */
    private fun show(profile: Profile): EditorDraft {
        val model = model ?: model().also { model = it }
        val state = shown
        if (state == null) {
            val created = androidx.compose.runtime.mutableStateOf(profile).also { shown = it }
            compose.setContent {
                MaterialTheme(colorScheme = lightScheme) { Surface { ProfileEditor(model, created.value, false, null, VpnState()) {} } }
            }
        } else {
            compose.runOnIdle { model.editorDraft = null; state.value = profile }
        }
        compose.waitForIdle()
        return checkNotNull(model.editorDraft).also { assertEquals(profile.id, it.profile.id) }
    }

    private val password = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)
    private val editable = SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)
    private val linkField = hasText(keyOrLink)

    private fun field(): SemanticsNodeInteraction = compose.onNode(linkField and editable)

    @Test fun ssconfProfileShowsOneMaskedEditableLinkFieldAndLowercaseType() {
        for (key in listOf("SSConf", "Key")) {
            show(profile(key))
            assertEquals(1, compose.onAllNodes(linkField and editable).fetchSemanticsNodes().size)
            assertEquals("Only the link is a masked field", 1, compose.onAllNodes(password).fetchSemanticsNodes().size)
            field().assert(password)
            compose.onNodeWithText("ssconf").assertExists()
            compose.onNodeWithText(context.getString(R.string.ssconf_local_only)).assertExists()
            assertTrue(compose.onAllNodes(hasText(link, substring = true)).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test fun editingTheLinkWritesTheSameSourceKey() {
        for (key in listOf("SSConf", "Key")) {
            val draft = show(profile(key))
            val replacement = "ssconf://other.example/path?token=x"
            field().performTextReplacement(replacement)
            compose.waitForIdle()
            assertEquals(key to replacement, draft.document.source())
            assertTrue(draft.dirty)
            assertEquals(1, ConfigDocument.parse(draft.source.text).values("AccessKey").size)
        }
    }

    @Test fun invalidFormatShowsTheValidationMessage() {
        val invalid = context.getString(R.string.ssconf_invalid)
        for (key in listOf("SSConf", "Key")) {
            show(profile(key))
            compose.onNodeWithText(invalid).assertDoesNotExist()
            field().performTextReplacement("ssconf://host/a b")
            compose.onNodeWithText(invalid).assertExists()
            field().performTextReplacement(link)
            compose.onNodeWithText(invalid).assertDoesNotExist()
        }
    }

    /** A `Key` link that loses its prefix while typed stays in the link field and is reported invalid. */
    @Test fun removingTheKeyPrefixKeepsTheFieldEditableAndInvalid() {
        val draft = show(profile("Key"))
        field().performTextReplacement("config.example/path")
        compose.onNodeWithText(context.getString(R.string.ssconf_invalid)).assertExists()
        field().assert(editable)
        assertTrue(SsConfForms.linkInvalid(draft.document, editingLink = true))
        field().performTextReplacement(link)
        assertEquals("Key" to link, draft.document.source())
        compose.onNodeWithText(context.getString(R.string.ssconf_invalid)).assertDoesNotExist()
    }

    @Test fun copyActionCopiesTheLink() {
        show(profile("SSConf"))
        compose.onNodeWithContentDescription(context.getString(R.string.editor_copy_key)).assertExists().performClick()
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
        assertEquals(link, clip?.getItemAt(0)?.text?.toString())
    }

    @Test fun eyeTogglesMaskingWhileEditable() {
        show(profile("SSConf"))
        field().assert(password)
        compose.onNodeWithContentDescription(context.getString(R.string.editor_show_key)).performClick()
        field().assert(!password)
        assertEquals(link, field().fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text)
        compose.onNodeWithContentDescription(context.getString(R.string.editor_hide_key)).performClick()
        field().assert(password)
    }

    @Test fun otherSourcesKeepAReadOnlyField() {
        show(Profile(UUID.randomUUID().toString(), "Plain", "SS", "vpn",
            Conf.fromKey("Plain", "ss://YWVzLTEyOC1nY206cGFzcw@example.com:443")))
        val node = compose.onNode(linkField).fetchSemanticsNode()
        assertNull("A non-ssconf source is not editable here", node.config.getOrNull(SemanticsActions.SetText))
        compose.onNodeWithText(context.getString(R.string.ssconf_local_only)).assertDoesNotExist()
        assertFalse(compose.onAllNodes(hasText("ssconf")).fetchSemanticsNodes().isNotEmpty())
    }
}
