package org.bimeron.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Audit M-1: the service's real drop notice against the notification manager, driven through the same
 * [noteSessionFinished] / [noteStartCommand] calls as `BimeronVpnService`'s Command.Finished and Command.Start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DropNotificationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val shadow get() = shadowOf(manager)
    private val notice get() = BimeronVpnService.dropNoticeFor(context)
    private val id = "00000000-0000-0000-0000-000000000002"
    private val profileName = "Drop notice fixture"

    @After fun reset() { VpnLive.resetAfterProcessStart() }

    private object NoRecoveryPort : RecoveryStartPort {
        override suspend fun read(id: String, token: Long): SessionInput? = null
        override suspend fun clearRecovery(): Boolean = true
        override val destroyed = false
        override fun postNotification() {}
        override fun cancelNotification() {}
        override fun recordSkip(message: Int) {}
        override fun publishSkipped(token: Long) {}
    }

    private fun dropNotification(): Notification? = shadow.getNotification(BimeronVpnService.DROP_NOTIFICATION_ID)

    /** A connected attempt that then failed with [code] under its own token, as the session would publish it. */
    private fun connectedThenFailed(code: VpnErrorCode): Long {
        val token = VpnLive.nextAttempt()
        VpnLive.publish(VpnState("connecting", attemptProfileId = id), token)
        VpnLive.publishCoreStatus(token, id, "connected", 1, 1, 100)
        VpnLive.publishFailure(code, profileName, id, token)
        return token
    }

    @Test fun failureAfterConnectedPostsFixedTextThatOpensTheApp() {
        val token = connectedThenFailed(VpnErrorCode.PACKET_IO)
        assertTrue(noteSessionFinished(token, VpnLive.state.value.code, wasConnected = true, recovery = false, stopPending = false, notice))
        val posted = assertNotNullPosted()
        assertEquals(BimeronVpnService.RECOVERY_CHANNEL, posted.channelId)
        val extras = posted.extras
        assertEquals(context.getString(R.string.vpn_drop_notification_title), extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(context.getString(R.string.vpn_drop_notification_text), extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        // No profile id, name or failure text in anything the notification carries.
        val carried = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT, Notification.EXTRA_INFO_TEXT, Notification.EXTRA_SUMMARY_TEXT)
            .joinToString { "$it=${extras.getCharSequence(it)}" } + " ticker=${posted.tickerText}"
        assertTrue(carried, !carried.contains(id) && !carried.contains(profileName))
        assertTrue(posted.flags and Notification.FLAG_AUTO_CANCEL != 0)
        val tap = shadowOf(posted.contentIntent)
        assertTrue(tap.isActivity)
        assertEquals(MainActivity::class.java.name, tap.savedIntent.component?.className)
        assertEquals(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            tap.savedIntent.flags and (Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        assertTrue(tap.flags and PendingIntent.FLAG_UPDATE_CURRENT != 0)
        assertTrue(tap.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        // Default (private) lock-screen visibility, like the recovery notification; it alerts once per post.
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)
        assertTrue(posted.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertNotEquals(BimeronVpnService.RECOVERY_NOTIFICATION_ID, BimeronVpnService.DROP_NOTIFICATION_ID)
    }

    @Test fun userStopPostsNothing() {
        val token = connectedThenFailed(VpnErrorCode.PACKET_IO)
        VpnLive.nextAttempt() // Command.Stop
        noteSessionFinished(token, VpnLive.state.value.code, wasConnected = true, recovery = false, stopPending = false, notice)
        assertEquals(0, shadow.size())
    }

    @Test fun supersededAttemptPostsNothing() {
        val token = connectedThenFailed(VpnErrorCode.CORE_CONNECT_FAILED)
        VpnLive.publish(VpnState("connecting", attemptProfileId = id), VpnLive.nextAttempt()) // a newer Start
        noteSessionFinished(token, VpnLive.state.value.code, wasConnected = true, recovery = false, stopPending = false, notice)
        assertEquals(0, shadow.size())
    }

    @Test fun userSshCancelPostsNothing() {
        val token = VpnLive.nextAttempt()
        VpnLive.publishCoreStatus(token, id, "connected", 1, 1, 100)
        stopAfterUserCancel(id, token, "fixed", owner = null)
        noteSessionFinished(token, VpnLive.state.value.code, wasConnected = true, recovery = false, stopPending = false, notice)
        assertEquals(0, shadow.size())
    }

    @Test fun nextUserStartCancelsTheNoticeButLeavesTheRecoveryNotification() {
        manager.notify(BimeronVpnService.RECOVERY_NOTIFICATION_ID, Notification.Builder(context, BimeronVpnService.RECOVERY_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bimeron).build())
        val token = connectedThenFailed(VpnErrorCode.NETWORK_ERROR)
        noteSessionFinished(token, VpnLive.state.value.code, wasConnected = true, recovery = false, stopPending = false, notice)
        assertNotNullPosted()
        noteStartCommand(recovery = true, NoRecoveryPort, notice)
        assertNotNullPosted()
        noteStartCommand(recovery = false, NoRecoveryPort, notice)
        assertEquals(null, dropNotification())
        notice.cancel() // the successful-connect cancel with nothing posted is harmless
        assertNotNull(shadow.getNotification(BimeronVpnService.RECOVERY_NOTIFICATION_ID))
    }

    @Test fun notificationsOffPostsNothingAndDoesNotThrow() {
        shadow.setNotificationsEnabled(false)
        val token = connectedThenFailed(VpnErrorCode.PACKET_IO)
        assertTrue(noteSessionFinished(token, VpnLive.state.value.code, wasConnected = true, recovery = false, stopPending = false, notice))
        assertEquals(0, shadow.size())
        notice.cancel()
    }

    private fun assertNotNullPosted(): Notification = dropNotification().also { assertNotNull("drop notice not posted", it) }!!
}
