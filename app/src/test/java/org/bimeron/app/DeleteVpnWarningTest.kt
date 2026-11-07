package org.bimeron.app

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The delete confirmations warn that the VPN will be stopped exactly when the delete path stops it
 * ([deleteStopsVpn]): for the connected profile and for the profile a connection attempt is for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class DeleteVpnWarningTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val id = UUID.randomUUID().toString()
    private val other = UUID.randomUUID().toString()
    private val profile = Profile(id, "Home", "SS", "vpn", Conf.fromKey("Home", "ss://YWVzLTEyOC1nY206cGFzcw@example.com:443"))
    private val owner = ViewModelStore()
    private val warning get() = context.getString(R.string.delete_active_warning)

    @After fun clear() = owner.clear()

    @Test fun deleteStopsVpnForTheConnectedProfileAndTheOneBeingConnected() {
        assertTrue(deleteStopsVpn(VpnState("connected", activeProfileId = id), id))
        assertTrue(deleteStopsVpn(VpnState("reconnecting", activeProfileId = id), id))
        assertTrue(deleteStopsVpn(VpnState("permission", attemptProfileId = id), id))
        assertTrue(deleteStopsVpn(VpnState("connecting", attemptProfileId = id), id))
        assertTrue(deleteStopsVpn(VpnState("failed", attemptProfileId = id), id))
        assertFalse(deleteStopsVpn(VpnState(), id))
        assertFalse(deleteStopsVpn(VpnState("connected", activeProfileId = other), id))
        assertFalse(deleteStopsVpn(VpnState("connecting", attemptProfileId = other), id))
    }

    private fun model(): AppModel {
        val root = temp.root.canonicalFile
        val application = object : android.app.Application() {
            override fun getFilesDir() = root
            override fun getApplicationInfo() = context.applicationInfo
            override fun getApplicationContext(): Context = this
            override fun getResources() = context.resources
        }
        return AppModel(application).also { owner.put("model", it) }
    }

    /** Renders the editor of [profile] over [initial]; returns a setter for the VPN state. */
    private fun showEditor(model: AppModel, initial: VpnState): (VpnState) -> Unit {
        var vpn by mutableStateOf(initial)
        compose.setContent {
            MaterialTheme(colorScheme = lightScheme) { Surface { ProfileEditor(model, profile, false, null, vpn) {} } }
        }
        return { vpn = it }
    }

    private fun openDeleteConfirmation() {
        compose.onNodeWithText(context.getString(R.string.editor_delete_profile)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.delete_confirm)).assertExists()
    }

    @Test fun editorDeleteConfirmationWarnsWhileTheProfileIsBeingConnected() {
        val setVpn = showEditor(model(), VpnState())
        openDeleteConfirmation()
        compose.onNodeWithText(warning).assertDoesNotExist()
        // The confirmation is open when a connection attempt for this profile starts: confirming stops it.
        setVpn(VpnState("connecting", attemptProfileId = id))
        compose.onNodeWithText(warning).assertExists()
        setVpn(VpnState("connecting", attemptProfileId = other))
        compose.onNodeWithText(warning).assertDoesNotExist()
        setVpn(VpnState("connected", activeProfileId = id))
        compose.onNodeWithText(warning).assertExists()
    }

    @Test fun editorDeleteConfirmationWarnsAfterAFailedAttemptForTheProfile() {
        showEditor(model(), VpnState("failed", attemptProfileId = id))
        openDeleteConfirmation()
        compose.onNodeWithText(warning).assertExists()
    }

    @Test fun deleteAnywayConfirmationWarnsWhileTheProfileIsBeingConnected() {
        val model = model()
        val setVpn = showEditor(model, VpnState())
        compose.runOnIdle { checkNotNull(model.editorDraft).conflict = EditorConflict.ChangedBeforeDelete }
        compose.onNodeWithText(context.getString(R.string.profile_delete_changed_message)).assertExists()
        compose.onNodeWithText(warning).assertDoesNotExist()
        setVpn(VpnState("connecting", attemptProfileId = id))
        compose.onNodeWithText(warning).assertExists()
        setVpn(VpnState("connecting", attemptProfileId = other))
        compose.onNodeWithText(warning).assertDoesNotExist()
    }
}
