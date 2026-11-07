package org.bimeron.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Audit M-1 on a real NotificationManagerService: the drop notice decided by [noteSessionFinished] is posted
 * with fixed text on the recovery channel, and the next user start removes it. No VPN or session is started;
 * the attempt is simulated through the published state only.
 */
@RunWith(AndroidJUnit4::class)
class DropNotificationInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val id = "00000000-0000-0000-0000-000000000003"

    private object NoRecoveryPort : RecoveryStartPort {
        override suspend fun read(id: String, token: Long): SessionInput? = null
        override suspend fun clearRecovery(): Boolean = true
        override val destroyed = false
        override fun postNotification() {}
        override fun cancelNotification() {}
        override fun recordSkip(message: Int) {}
        override fun publishSkipped(token: Long) {}
    }

    @After fun cleanUp() {
        manager.cancel(BimeronVpnService.DROP_NOTIFICATION_ID)
        VpnLive.resetAfterProcessStart()
    }

    private fun active(): StatusBarNotification? =
        manager.activeNotifications.singleOrNull { it.id == BimeronVpnService.DROP_NOTIFICATION_ID }

    /** NotificationManagerService applies posts and cancels asynchronously: poll for [expectPosted]. */
    private fun await(expectPosted: Boolean): StatusBarNotification? {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var current = active()
        while ((current != null) != expectPosted && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(50)
            current = active()
        }
        return current
    }

    @Test fun failureAfterConnectedPostsAndNextUserStartRemovesIt() {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        // The service creates this channel in onCreate before any session can end; no service runs here.
        manager.createNotificationChannel(NotificationChannel(BimeronVpnService.RECOVERY_CHANNEL,
            context.getString(R.string.vpn_recovery_notification_channel), NotificationManager.IMPORTANCE_DEFAULT))
        if (Build.VERSION.SDK_INT >= 33) assertTrue("POST_NOTIFICATIONS grant failed", manager.areNotificationsEnabled())
        // Below API 33 the user may have turned notifications off; there is nothing to post then.
        assumeTrue(manager.areNotificationsEnabled())
        val notice = BimeronVpnService.dropNoticeFor(context)

        val token = VpnLive.nextAttempt()
        VpnLive.publish(VpnState("connecting", attemptProfileId = id), token)
        assertEquals("connected", VpnLive.publishCoreStatus(token, id, "connected", 1, 1, SystemClock.elapsedRealtime()))
        VpnLive.publishFailure(VpnErrorCode.PACKET_IO, context.getString(VpnErrorCode.PACKET_IO.messageRes), id, token)
        assertTrue(noteSessionFinished(token, VpnLive.state.value.code, wasConnected = true, recovery = false, stopPending = false, notice))

        val posted = await(expectPosted = true)
        assertNotNull("Drop notice not posted", posted)
        val notification = posted!!.notification
        assertEquals(BimeronVpnService.RECOVERY_CHANNEL, notification.channelId)
        assertEquals(context.getString(R.string.vpn_drop_notification_title),
            notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals(context.getString(R.string.vpn_drop_notification_text),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertFalse(notification.extras.toString().contains(id))

        noteStartCommand(recovery = false, NoRecoveryPort, notice)
        assertNull("Drop notice still shown after a user start", await(expectPosted = false))
    }
}
