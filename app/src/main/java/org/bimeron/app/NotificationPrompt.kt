package org.bimeron.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * POST_NOTIFICATIONS policy (user decision #1, 2026-11-07).
 *
 * - API 33+: the first time the VPN goes from a non-connected state to `connected` while the Activity is resumed
 *   and no VPN consent is pending, the app records that the decision was made and, if notifications are off, asks
 *   once. It never asks again automatically.
 * - Only an observed transition counts: opening the app while the VPN is already connected (connect then Home,
 *   or a background recovery reconnect) does not prompt and does not set the flag, so a later observed connect
 *   still asks once. Each RESUMED period is a new [ResumedSession] whose first, replayed value is never a trigger.
 * - API < 33: nothing is requested and nothing is recorded.
 * - The "Notifications are off" row is shown whenever notifications are disabled, except on API 33+
 *   before the one-time request: there the default "off" state is not something the user chose yet.
 */
internal object NotificationPolicy {
    /** Android 13, the first release with the runtime POST_NOTIFICATIONS permission. */
    const val RUNTIME_PERMISSION_SDK = Build.VERSION_CODES.TIRAMISU

    enum class Action {
        /** Do nothing now. */
        NONE,
        /** First connect, notifications already on: record the decision so a later "off" never re-prompts. */
        MARK_ASKED,
        /** First connect, notifications off: record the decision, then launch the request. */
        REQUEST,
    }

    /**
     * [previousState] is the state observed just before [vpnState] in the same RESUMED session, or null for the first
     * value of the session (the StateFlow replay of whatever the state already was).
     */
    fun onVpnState(sdkInt: Int, previousState: String?, vpnState: String, consentPending: Boolean, alreadyAsked: Boolean, enabled: Boolean): Action = when {
        sdkInt < RUNTIME_PERMISSION_SDK -> Action.NONE
        // Only after a successful connect: never next to the VPN consent dialog or before the tunnel is up.
        vpnState != "connected" || consentPending -> Action.NONE
        // Only a non-connected -> connected transition seen while resumed; an already-connected replay is not one.
        !isObservedConnect(previousState, vpnState) -> Action.NONE
        alreadyAsked -> Action.NONE
        enabled -> Action.MARK_ASKED
        else -> Action.REQUEST
    }

    fun isObservedConnect(previousState: String?, vpnState: String): Boolean =
        previousState != null && previousState != "connected" && vpnState == "connected"

    fun showOffRow(sdkInt: Int, enabled: Boolean, alreadyAsked: Boolean): Boolean =
        !enabled && (sdkInt < RUNTIME_PERMISSION_SDK || alreadyAsked)

    /** The app's own notification settings page (API 26+, minSdk is 26). */
    fun settingsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
}

/** The "already asked" flag. Written synchronously so process death right after a launch cannot re-prompt. */
internal class NotificationPromptStore(context: Context) {
    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    val asked: Boolean get() = preferences.getBoolean(ASKED, false)
    fun markAsked(): Boolean = preferences.edit().putBoolean(ASKED, true).commit()

    companion object {
        internal const val FILE = "notification_prompt"
        internal const val ASKED = "post_notifications_asked"
    }
}

/**
 * Activity-side coordination. MainActivity opens a [ResumedSession] for every RESUMED period, feeds it the VPN
 * states it observes and calls [refresh] on every resume and after the permission result; the store is the only
 * state that survives recreation.
 */
internal class NotificationPrompt(
    private val store: NotificationPromptStore,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val enabled: () -> Boolean,
) {
    private val offRow = MutableStateFlow(false)
    val showOffRow: StateFlow<Boolean> = offRow.asStateFlow()

    fun refresh() { offRow.value = NotificationPolicy.showOffRow(sdkInt, enabled(), store.asked) }

    /** A new session for each RESUMED period: its first value is a replay, not an observed transition. */
    fun resumedSession(): ResumedSession = ResumedSession()

    inner class ResumedSession internal constructor() {
        private var previous: String? = null

        /** Calls [request] at most once per install; the flag is committed first, and a failed commit skips the request. */
        fun onVpnState(state: String, consentPending: Boolean, request: () -> Unit): NotificationPolicy.Action {
            val before = previous
            previous = state
            val action = NotificationPolicy.onVpnState(sdkInt, before, state, consentPending, store.asked, enabled())
            if (action == NotificationPolicy.Action.NONE || !store.markAsked()) return NotificationPolicy.Action.NONE
            if (action == NotificationPolicy.Action.REQUEST) request()
            return action
        }
    }
}

/** Settings row shown while app notifications are off; opens the system notification settings for the app. */
@Composable internal fun NotificationsOffRow(onOpenSettings: () -> Unit) {
    val action = stringResource(R.string.notifications_open_settings)
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = MUTED_TEXT_ALPHA)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = action, onClick = onOpenSettings).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.NotificationsOff, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.notifications_off_title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.notifications_off_hint), style = MaterialTheme.typography.bodySmall, color = muted)
            // Visual caption of the action; TalkBack gets it as the click label of the row.
            Text(action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp).clearAndSetSemantics {})
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = muted)
    }
}
