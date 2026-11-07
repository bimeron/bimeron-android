package org.bimeron.app

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * Audit M-1 through the real `BimeronVpnService` command loop: intents, Command.Start/Stop/Finished, the
 * session's catch/finally and the connected callback. Only the native part of the session is replaced
 * ([BimeronVpnService.sessionDriverForTest]); no Core, TUN or VPN is involved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DropNoticeServiceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java))
    private val mainLooper get() = shadowOf(Looper.getMainLooper())
    private val id = "00000000-0000-0000-0000-000000000004"
    private lateinit var controller: ServiceController<BimeronVpnService>
    private val service get() = controller.get()
    private var startId = 0

    @Before fun setUp() {
        VpnLive.resetAfterProcessStart()
        controller = Robolectric.buildService(BimeronVpnService::class.java).create()
    }

    @After fun tearDown() {
        BimeronVpnService.sessionDriverForTest = null
        controller.destroy()
        mainLooper.idle()
        VpnLive.resetAfterProcessStart()
        context.getSharedPreferences(BimeronVpnService.RECOVERY, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun dropNotice(): Notification? = notifications.getNotification(BimeronVpnService.DROP_NOTIFICATION_ID)

    private fun userStart() = service.onStartCommand(Intent(context, BimeronVpnService::class.java)
        .setAction(BimeronVpnService.START).putExtra(BimeronVpnService.PROFILE, id), 0, ++startId)

    private fun userStop() = service.onStartCommand(Intent(context, BimeronVpnService::class.java)
        .setAction(BimeronVpnService.STOP), 0, ++startId)

    /** Runs the main looper (the command loop) until [condition] holds; the session itself runs on IO. */
    private fun idleUntil(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (true) {
            mainLooper.idle()
            if (condition()) return
            if (System.nanoTime() > deadline) fail(message)
            Thread.sleep(5)
        }
    }

    /** A session that reaches connected (published and reported through the callback), then fails on its own. */
    private fun connectsThenFails(code: VpnErrorCode): suspend (Long, () -> Unit) -> Unit = { token, connected ->
        assertEquals("connected", VpnLive.publishCoreStatus(token, id, "connected", 1, 1, 100))
        connected()
        throw BimeronVpnService.SessionFailure(code)
    }

    /** Command.Finished (or Stop) ran to its end: the decision is made before the foreground is left. */
    private fun finished() = shadowOf(service).isForegroundStopped

    private fun ended(code: VpnErrorCode) = VpnLive.state.value.let { it.state == "failed" && it.code == code }

    @Test fun failureAfterConnectedPostsAndTheNextUserStartRemovesIt() {
        BimeronVpnService.sessionDriverForTest = connectsThenFails(VpnErrorCode.PACKET_IO)
        userStart()
        idleUntil("drop notice not posted") { dropNotice() != null }
        assertTrue(ended(VpnErrorCode.PACKET_IO))
        assertEquals(context.getString(R.string.vpn_drop_notification_title),
            dropNotice()!!.extras.getCharSequence(Notification.EXTRA_TITLE).toString())

        BimeronVpnService.sessionDriverForTest = { _, _ -> awaitCancellation() }
        userStart()
        idleUntil("drop notice not removed by the next user start") { dropNotice() == null }
        userStop()
        idleUntil("stop not finished") { VpnLive.state.value.state == "disconnected" }
        assertNull(dropNotice())
    }

    @Test fun reachingConnectedRemovesAPendingNotice() {
        val running = CountDownLatch(1)
        val connect = CountDownLatch(1)
        BimeronVpnService.sessionDriverForTest = { token, connected ->
            running.countDown()
            assertTrue(connect.await(5, TimeUnit.SECONDS))
            VpnLive.publishCoreStatus(token, id, "connected", 1, 1, 100)
            connected()
            awaitCancellation()
        }
        userStart()
        idleUntil("session not started") { running.count == 0L }
        // Posted after the user start's own cancel, so only reaching connected can remove it.
        BimeronVpnService.dropNoticeFor(context).post()
        assertNotNull(dropNotice())
        connect.countDown()
        idleUntil("drop notice not removed on connected") { dropNotice() == null }
        userStop()
        idleUntil("stop not finished") { VpnLive.state.value.state == "disconnected" }
        assertNull(dropNotice())
    }

    @Test fun userStopOfAConnectedSessionPostsNothing() {
        BimeronVpnService.sessionDriverForTest = { token, connected ->
            VpnLive.publishCoreStatus(token, id, "connected", 1, 1, 100)
            connected()
            awaitCancellation()
        }
        userStart()
        idleUntil("not connected") { VpnLive.state.value.state == "connected" }
        userStop()
        idleUntil("stop not finished") { VpnLive.state.value.state == "disconnected" }
        assertNull(dropNotice())
    }

    /**
     * Connects, then fails on IO once released, and returns with that attempt's Command.Finished queued but not
     * yet handled: the command loop runs on the main looper, which is not run while waiting.
     */
    private fun failWithFinishedQueued() {
        val running = CountDownLatch(1)
        val fail = CountDownLatch(1)
        BimeronVpnService.sessionDriverForTest = { token, connected ->
            VpnLive.publishCoreStatus(token, id, "connected", 1, 1, 100)
            connected()
            running.countDown()
            assertTrue(fail.await(5, TimeUnit.SECONDS))
            throw BimeronVpnService.SessionFailure(VpnErrorCode.PACKET_IO)
        }
        userStart()
        idleUntil("session not connected") { running.count == 0L }
        assertTrue(mainLooper.isIdle)
        fail.countDown()
        val deadline = System.nanoTime() + 5_000_000_000
        while (!(ended(VpnErrorCode.PACKET_IO) && !mainLooper.isIdle)) {
            if (System.nanoTime() > deadline) fail("Command.Finished not queued")
            Thread.sleep(5)
        }
        // The finally (nothing native to clean up) sends Finished right after the failure is published.
        Thread.sleep(200)
        // Still current: nothing has advanced the gate, so only the queued stop marks it as the user's.
        assertNull(dropNotice())
    }

    /**
     * S-1: the system teardown of a user stop makes the session fail first, so its Command.Finished is queued,
     * with a still-current token, ahead of the Stop. The queued stop keeps it a user stop.
     */
    @Test fun failureQueuedAheadOfAUserStopPostsNothing() {
        failWithFinishedQueued()
        userStop()
        idleUntil("stop not finished") { VpnLive.state.value.state == "disconnected" }
        assertNull(dropNotice())
    }

    @Test fun revokeQueuedAheadOfTheFinishedSessionPostsNothing() {
        failWithFinishedQueued()
        service.onRevoke()
        idleUntil("revoke stop not finished") { VpnLive.state.value.state == "disconnected" }
        assertNull(dropNotice())
    }

    @Test fun userConnectFailingBeforeConnectedPostsNothing() {
        BimeronVpnService.sessionDriverForTest = { _, _ -> throw BimeronVpnService.SessionFailure(VpnErrorCode.CORE_CONNECT_FAILED) }
        userStart()
        idleUntil("Command.Finished not handled") { finished() }
        assertTrue(ended(VpnErrorCode.CORE_CONNECT_FAILED))
        assertNull(dropNotice())
    }

    @Test fun notificationsOffPostNothing() {
        notifications.setNotificationsEnabled(false)
        BimeronVpnService.sessionDriverForTest = connectsThenFails(VpnErrorCode.NETWORK_ERROR)
        userStart()
        idleUntil("Command.Finished not handled") { finished() }
        assertTrue(ended(VpnErrorCode.NETWORK_ERROR))
        assertNull(dropNotice())
        assertEquals(0, notifications.allNotifications.count { it.channelId == BimeronVpnService.RECOVERY_CHANNEL })
    }
}
