package org.bimeron.app

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import android.os.Looper
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.robolectric.Shadows.shadowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Done never saves a broken ssconf link from the form, also after a tab switch, for both the `SSConf` and
 * the `Key` source. The store's Core validation is replaced by one that accepts everything, so only the
 * editor's own link check stands between the edit and the store: a `Key` link that lost its `ssconf://`
 * prefix is a valid Shadowsocks key and would otherwise be saved as one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class SsConfLinkSaveGateTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val owner = ViewModelStore()
    private val link = "ssconf://config.example/fixture-secret"
    private val singleton = ProfileStore::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private var previous: Any? = null
    private lateinit var store: ProfileStore

    @Before fun installPermissiveStore() {
        previous = singleton.get(null)
        store = ProfileStore(null, temp.newFolder(), SshProfileResources(temp.newFolder())) { /* Core accepts anything */ }
        singleton.set(null, store)
    }

    @After fun restore() {
        owner.clear()
        singleton.set(null, previous)
    }

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

    /** Runs main-looper work (viewModelScope coroutines need explicit idling under Robolectric) until [done]. */
    private fun pumpUntil(timeoutMillis: Long = 30_000, done: () -> Boolean) = runBlocking {
        withTimeout(timeoutMillis) {
            while (!done()) { shadowOf(Looper.getMainLooper()).idle(); delay(10) }
        }
    }

    private class Case(val key: String, val broken: String, val switchTab: Boolean, val id: UUID, val text: String)

    @Test fun brokenLinkIsNeverSavedFromTheFormIncludingAfterATabSwitch() {
        val broken = mapOf(
            // A Key link without its prefix: a valid Shadowsocks key.
            "Key" to "ss://YWVzLTEyOC1nY206cGFzcw@192.0.2.10:8388",
            "SSConf" to "ssconf://host/with space",
        )
        val cases = broken.flatMap { (key, value) ->
            listOf(false, true).map { switchTab ->
                val text = Conf.fromKey("Gate $key $switchTab", link).replace("\nSSConf = ", "\n$key = ")
                check(ConfigDocument.parse(text).source() == key to link)
                Case(key, value, switchTab, UUID.randomUUID(), text)
            }
        }
        runBlocking {
            cases.forEach { assertTrue(store.compareAndSave(UUID.randomUUID(), it.id, null, it.text) is CommitResult.Committed) }
        }
        val model = model()
        // Load the stored profiles (and their revisions) the way ProfileStorePreferencesFailureTest does.
        val refresh = model.refresh()
        pumpUntil { refresh.isCompleted }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(cases.size, model.profiles.value.size)
        val shown = mutableStateOf<Profile?>(null)
        compose.setContent {
            MaterialTheme(colorScheme = lightScheme) {
                Surface { shown.value?.let { ProfileEditor(model, it, false, null, VpnState()) {} } }
            }
        }
        val invalid = context.getString(R.string.protocol_form_invalid)
        // Every case runs; the failures are reported together, so a regression shows which variants it saves.
        val failures = mutableListOf<String>()
        for (case in cases) {
            val label = "${case.key} switchTab=${case.switchTab}"
            compose.runOnIdle {
                model.editorDraft = null
                shown.value = model.profiles.value.single { it.id == case.id.toString() }
            }
            compose.waitForIdle()
            val draft = checkNotNull(model.editorDraft)
            assertEquals(label, case.id.toString(), draft.profile.id)
            compose.onNode(hasText(context.getString(R.string.editor_key_or_link)) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)).performTextReplacement(case.broken)
            if (case.switchTab) {
                compose.onNodeWithText(context.getString(R.string.editor_network)).performClick()
                compose.waitForIdle()
                assertEquals(label, 1, draft.tab)
            }
            compose.onNodeWithText(context.getString(R.string.editor_done)).performClick()
            // A save, if one was started, finishes before the store is read; with the gate intact none starts.
            pumpUntil { !draft.validating && !model.busy.value }
            compose.waitForIdle()
            val stored = runBlocking { store.load().first.single { it.id == case.id.toString() }.text }
            if (stored != case.text) failures += "$label: saved as " +
                ConfigDocument.parse(stored).source()?.let { (key, value) -> "$key = ${value.substringBefore("://")}://…" }
            if (draft.error != invalid) failures += "$label: error=${draft.error}"
            assertEquals(label, case.key to case.broken, draft.document.source())
        }
        assertTrue(failures.joinToString("; "), failures.isEmpty())
    }
}
