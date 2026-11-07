package org.bimeron.app

import android.content.Context
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Decision 8 in the editor: an SSH profile stored with a typed shared-storage path (saved before save-time
 * validation) opens, shows the field-level error at the path, and Done shows the fixed save error, which SSH
 * profiles (protected) show as is, while the stored profile stays unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class SshFileFieldErrorTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val owner = ViewModelStore()
    private val singleton = ProfileStore::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private var previous: Any? = null
    private lateinit var store: ProfileStore
    private lateinit var directory: File

    @Before fun installPermissiveStore() {
        previous = singleton.get(null)
        val root = File(temp.root.canonicalFile, "files")
        directory = File(root, "profiles").apply { mkdirs() }
        store = ProfileStore(null, directory, SshProfileResources(File(root, "ssh-resources"))) { /* Core accepts anything */ }
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

    private fun pumpUntil(timeoutMillis: Long = 30_000, done: () -> Boolean) = runBlocking {
        withTimeout(timeoutMillis) {
            while (!done()) { shadowOf(Looper.getMainLooper()).idle(); delay(10) }
        }
    }

    private val legacy = "[Profile]\nVersion = 1\nName = Legacy\n\n[AccessKey]\nType = ssh\nServer = ssh.example.com\nPort = 22\n" +
        "Username = u\nIdentityFile = /sdcard/.ssh/id_rsa\n"

    /** Stores [legacy] as written before save-time validation, opens it in the editor and returns the model and draft. */
    private fun openLegacy(id: UUID): Pair<AppModel, EditorDraft> {
        File(directory, "$id.conf").writeText(legacy)
        val model = model()
        val refresh = model.refresh()
        pumpUntil { refresh.isCompleted }
        shadowOf(Looper.getMainLooper()).idle()
        val profile = model.profiles.value.single { it.id == id.toString() }
        compose.setContent {
            val error by model.error.collectAsState()
            MaterialTheme(colorScheme = lightScheme) { Surface { ProfileEditor(model, profile, false, error, VpnState()) {} } }
        }
        compose.waitForIdle()
        return model to checkNotNull(model.editorDraft)
    }

    @Test fun storedOutsidePathShowsFieldErrorAndDoneShowsTheSaveError() {
        val id = UUID.randomUUID()
        val text = legacy
        val (model, draft) = openLegacy(id)
        compose.onNodeWithText(context.getString(R.string.ssh_form_file_not_imported)).assertExists()
        compose.onNodeWithText(context.getString(R.string.editor_done)).performClick()
        pumpUntil { !draft.validating && !model.busy.value }
        compose.waitForIdle()
        val message = context.getString(R.string.ssh_files_not_imported)
        assertEquals(message, draft.error)
        assertEquals(1, compose.onAllNodesWithText(message).fetchSemanticsNodes().size)
        compose.onNodeWithText(context.getString(R.string.protocol_form_failed)).assertDoesNotExist()
        assertEquals(text, File(directory, "$id.conf").readText())
    }

    /** The conflict panel's failure line uses the same allow-list: save-as-new of an outside path shows the message. */
    @Test fun conflictPanelShowsTheSaveErrorOnProtectedProfiles() {
        val id = UUID.randomUUID()
        val (model, draft) = openLegacy(id)
        compose.runOnIdle { draft.conflict = EditorConflict.Gone(deleted = false) }
        compose.onNodeWithText(context.getString(R.string.profile_conflict_save_new)).performClick()
        pumpUntil { !model.busy.value && model.error.value != null }
        compose.waitForIdle()
        val message = context.getString(R.string.ssh_files_not_imported)
        assertEquals(message, model.error.value)
        // The editor's message line and the panel's failure line, never the generic protected-profile text.
        assertEquals(2, compose.onAllNodesWithText(message).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText(context.getString(R.string.protocol_form_failed)).fetchSemanticsNodes().size)
        assertEquals(listOf("$id.conf"), directory.list()!!.filter { it.endsWith(".conf") })
    }
}
