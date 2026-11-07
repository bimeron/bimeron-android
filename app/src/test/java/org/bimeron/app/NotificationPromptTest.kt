package org.bimeron.app

import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import org.bimeron.app.NotificationPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** POST_NOTIFICATIONS decision #1: ask once after the first successful connect, then only offer the settings row. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationPromptTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java))
    private val vpnStates = listOf("disconnected", "permission", "connecting", "connected", "reconnecting", "stopping", "failed")

    private fun decide(sdk: Int = 33, state: String = "connected", consent: Boolean = false, asked: Boolean = false, enabled: Boolean = false) =
        NotificationPolicy.onVpnState(sdk, state, consent, asked, enabled)

    @Test fun firstConnectWithoutFlagRequests() {
        assertEquals(Action.REQUEST, decide())
        assertEquals(Action.REQUEST, decide(sdk = 34))
    }

    @Test fun alreadyAskedNeverRequests() {
        vpnStates.forEach { state ->
            listOf(true, false).forEach { enabled -> assertEquals(Action.NONE, decide(state = state, asked = true, enabled = enabled)) }
        }
    }

    @Test fun belowApi33NeverRequestsOrMarks() {
        (26..32).forEach { sdk ->
            vpnStates.forEach { state -> assertEquals("sdk=$sdk state=$state", Action.NONE, decide(sdk = sdk, state = state)) }
        }
    }

    @Test fun neverDuringConsentFlowOrBeforeConnect() {
        vpnStates.filter { it != "connected" }.forEach { assertEquals(it, Action.NONE, decide(state = it)) }
        // A consent result still pending (pendingPermissionId) blocks the request even if a stale state says connected.
        assertEquals(Action.NONE, decide(consent = true))
    }

    @Test fun alreadyEnabledOnlyRecordsTheDecision() {
        assertEquals(Action.MARK_ASKED, decide(enabled = true))
    }

    @Test fun offRowVisibility() {
        // API 33+: hidden before the one-time request, shown once asked and still off, hidden when on.
        assertFalse(NotificationPolicy.showOffRow(33, enabled = false, alreadyAsked = false))
        assertTrue(NotificationPolicy.showOffRow(33, enabled = false, alreadyAsked = true))
        assertFalse(NotificationPolicy.showOffRow(33, enabled = true, alreadyAsked = true))
        // API < 33: shown whenever the user turned notifications off, hidden when on.
        assertTrue(NotificationPolicy.showOffRow(32, enabled = false, alreadyAsked = false))
        assertFalse(NotificationPolicy.showOffRow(26, enabled = true, alreadyAsked = false))
    }

    @Test fun settingsIntentOpensAppNotificationSettings() {
        val intent = NotificationPolicy.settingsIntent(context.packageName)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals("org.bimeron.app", intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(setOf(Settings.EXTRA_APP_PACKAGE), intent.extras?.keySet())
    }

    @Test fun flagPersistsAcrossStoreInstances() {
        assertFalse(NotificationPromptStore(context).asked)
        assertTrue(NotificationPromptStore(context).markAsked())
        // A new instance stands in for a recreated Activity or a new process reading the same file.
        assertTrue(NotificationPromptStore(context).asked)
        assertTrue(context.getSharedPreferences(NotificationPromptStore.FILE, Context.MODE_PRIVATE).getBoolean(NotificationPromptStore.ASKED, false))
    }

    @Test fun requestsOnceAndWritesFlagBeforeLaunch() {
        val store = NotificationPromptStore(context)
        var launches = 0
        var flagAtLaunch = false
        val prompt = NotificationPrompt(store, sdkInt = 34) { false }
        val request = { launches++; flagAtLaunch = NotificationPromptStore(context).asked }
        listOf("permission", "connecting", "connected", "disconnected", "connecting", "connected").forEach { prompt.onVpnState(it, false, request) }
        assertEquals(1, launches)
        assertTrue("flag must be committed before the launch", flagAtLaunch)
        // Rotation or process death: a fresh controller over the same preferences does not ask again.
        val recreated = NotificationPrompt(NotificationPromptStore(context), sdkInt = 34) { false }
        assertEquals(Action.NONE, recreated.onVpnState("connected", false, request))
        assertEquals(1, launches)
    }

    @Test fun consentPendingDefersUntilConnectedWithoutConsent() {
        var launches = 0
        val prompt = NotificationPrompt(NotificationPromptStore(context), sdkInt = 33) { false }
        assertEquals(Action.NONE, prompt.onVpnState("connected", true) { launches++ })
        assertFalse(NotificationPromptStore(context).asked)
        assertEquals(Action.REQUEST, prompt.onVpnState("connected", false) { launches++ })
        assertEquals(1, launches)
    }

    @Test fun enabledAtFirstConnectNeverPromptsLater() {
        var enabled = true
        var launches = 0
        val prompt = NotificationPrompt(NotificationPromptStore(context), sdkInt = 34) { enabled }
        assertEquals(Action.MARK_ASKED, prompt.onVpnState("connected", false) { launches++ })
        enabled = false
        assertEquals(Action.NONE, prompt.onVpnState("connected", false) { launches++ })
        assertEquals(0, launches)
        prompt.refresh()
        assertTrue(prompt.showOffRow.value)
    }

    @Test fun belowApi33DoesNothingButShowsRowWhenDisabled() {
        var enabled = false
        var launches = 0
        val prompt = NotificationPrompt(NotificationPromptStore(context), sdkInt = 32) { enabled }
        assertEquals(Action.NONE, prompt.onVpnState("connected", false) { launches++ })
        assertEquals(0, launches)
        assertFalse(NotificationPromptStore(context).asked)
        prompt.refresh(); assertTrue(prompt.showOffRow.value)
        enabled = true
        prompt.refresh(); assertFalse(prompt.showOffRow.value)
    }

    /** The settings row follows the system state through refresh() (called on resume) and hides once enabled. */
    @Test fun rowAppearsWhenDisabledAndHidesWhenEnabled() {
        val manager = androidx.core.app.NotificationManagerCompat.from(context)
        notifications.setNotificationsEnabled(false)
        val prompt = NotificationPrompt(NotificationPromptStore(context), sdkInt = 34) { manager.areNotificationsEnabled() }
        prompt.onVpnState("connected", false) {}
        prompt.refresh()
        var opened = 0
        compose.setContent {
            val off by prompt.showOffRow.collectAsStateWithLifecycle()
            MaterialTheme(colorScheme = lightScheme) { Surface { SettingsPage("system", {}, {}, {}, notificationsOff = off, onNotificationSettings = { opened++ }) } }
        }
        val title = context.getString(R.string.notifications_off_title)
        compose.onNodeWithText(title).assertExists().assertHasClickAction().performClick()
        assertEquals(1, opened)
        notifications.setNotificationsEnabled(true)
        prompt.refresh()
        compose.waitForIdle()
        compose.onNodeWithText(title).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.notifications_section_title)).assertDoesNotExist()
        notifications.setNotificationsEnabled(false)
        prompt.refresh()
        compose.waitForIdle()
        compose.onNodeWithText(title).assertExists()
    }
}
