package org.bimeron.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §13.2: a recovery start of an SSH profile never starts a session. A sticky restart's null intent cannot
 * be sent from a test, so this sends an action-less explicit intent, which takes the same recovery path
 * (anything but the user START is classified, and an action-less start reads the persisted recovery id).
 * Never establishes a VPN: the skip happens before any session or TUN.
 */
@RunWith(AndroidJUnit4::class)
class RecoverySkipTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val preferences get() = context.getSharedPreferences(BimeronVpnService.RECOVERY, Context.MODE_PRIVATE)
    private var profileId: String? = null
    private var previousRecoveryId: String? = null

    @After fun cleanUp(): Unit = runBlocking {
        context.getSystemService(NotificationManager::class.java).cancel(BimeronVpnService.RECOVERY_NOTIFICATION_ID)
        preferences.edit().putString(BimeronVpnService.ACTIVE_ID, previousRecoveryId).commit()
        profileId?.let { id -> withContext(Dispatchers.IO) { ProfileStore.get(context).delete(id) } }
        VpnLive.resetAfterProcessStart()
    }

    @Test fun sshRecoveryIsSkippedWithNotificationAndNoSession(): Unit = runBlocking {
        previousRecoveryId = preferences.getString(BimeronVpnService.ACTIVE_ID, null)
        val store = withContext(Dispatchers.IO) { ProfileStore.get(context) }
        val profile = store.save(Conf.fromKey("Recovery skip fixture", "ssh://TEST@example.invalid:22"))
        profileId = profile.id
        assertTrue(preferences.edit().putString(BimeronVpnService.ACTIVE_ID, profile.id).commit())
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        VpnLive.resetAfterProcessStart()

        // Shell identity allows a foreground-service start while the test app is in the background.
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
        try {
            context.startForegroundService(Intent(context, BimeronVpnService::class.java))
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }

        val deadline = SystemClock.elapsedRealtime() + 10_000
        var sawSession = false
        while (VpnLive.state.value.code != VpnErrorCode.RECOVERY_NEEDS_USER && SystemClock.elapsedRealtime() < deadline) {
            val state = VpnLive.state.value
            if (state.state == "connecting" || state.attemptProfileId != null || state.activeProfileId != null) sawSession = true
            Thread.sleep(10)
        }
        val state = VpnLive.state.value
        assertEquals(VpnErrorCode.RECOVERY_NEEDS_USER, state.code)
        assertEquals("failed", state.state)
        assertFalse("A session attempt was published", sawSession)
        assertNull(state.attemptProfileId)
        assertNull(state.activeProfileId)
        assertNull(VpnLive.auth.value)
        assertNull(preferences.getString(BimeronVpnService.ACTIVE_ID, null))

        val notifications = context.getSystemService(NotificationManager::class.java)
        if (notifications.areNotificationsEnabled()) {
            val posted = notifications.activeNotifications.singleOrNull { it.id == BimeronVpnService.RECOVERY_NOTIFICATION_ID }
            assertTrue("Recovery notification not posted", posted != null)
            val extras = posted!!.notification.extras
            assertEquals(context.getString(R.string.vpn_recovery_notification_text),
                extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString())
            assertFalse(extras.toString().contains(profile.id))
        }
    }
}
