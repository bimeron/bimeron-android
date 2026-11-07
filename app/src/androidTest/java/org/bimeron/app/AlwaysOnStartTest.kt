package org.bimeron.app

import android.content.Context
import android.content.Intent
import android.net.VpnService
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The always-on start Android sends (`VpnService.SERVICE_INTERFACE`, e.g. after a package update) resolves
 * its profile and takes the recovery path. An SSH profile is used so the start is skipped before any session
 * or TUN: no VPN consent is needed and no VPN is established.
 */
@RunWith(AndroidJUnit4::class)
class AlwaysOnStartTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val preferences get() = context.getSharedPreferences(BimeronVpnService.RECOVERY, Context.MODE_PRIVATE)
    private var profileId: String? = null
    private var previousRecoveryId: String? = null
    private var previousSelection: String? = null
    private var previousEvents: List<LogEntry> = emptyList()
    private var previousLogSettings = LogSettings()

    @Before fun saveState(): Unit = runBlocking {
        previousRecoveryId = preferences.getString(BimeronVpnService.ACTIVE_ID, null)
        previousSelection = readSelectedProfileId(context)
        previousEvents = AppEvents.entries.value
        previousLogSettings = AppEvents.settings.value
        // The no-target check reads a WARNING entry; a device journal level must not hide it.
        AppEvents.apply(LogSettings())
        assertTrue(preferences.edit().putString(BimeronVpnService.ACTIVE_ID, null).commit())
        VpnLive.resetAfterProcessStart()
    }

    @After fun restoreState(): Unit = runBlocking {
        context.getSystemService(android.app.NotificationManager::class.java).cancel(BimeronVpnService.RECOVERY_NOTIFICATION_ID)
        preferences.edit().putString(BimeronVpnService.ACTIVE_ID, previousRecoveryId).commit()
        writeSelectedProfileId(context, previousSelection)
        profileId?.let { id -> withContext(Dispatchers.IO) { ProfileStore.get(context).delete(id) } }
        AppEvents.settings.value = previousLogSettings
        AppEvents.entries.value = previousEvents
        VpnLive.resetAfterProcessStart()
    }

    private fun startAlwaysOn() {
        // Shell identity allows a foreground-service start while the test app is in the background.
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
        try {
            context.startForegroundService(Intent(VpnService.SERVICE_INTERFACE).setClass(context, BimeronVpnService::class.java))
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Suppress("DEPRECATION") // Still lists the caller's own services.
    private fun serviceRunning(): Boolean = context.getSystemService(android.app.ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE).any { it.service.className == BimeronVpnService::class.java.name && it.started }

    @Test fun alwaysOnStartRecoversTheSelectedProfileAfterAStop(): Unit = runBlocking {
        val store = withContext(Dispatchers.IO) { ProfileStore.get(context) }
        val profile = store.save(Conf.fromKey("Always-on fixture", "ssh://TEST@example.invalid:22"))
        profileId = profile.id
        writeSelectedProfileId(context, profile.id)

        startAlwaysOn()

        val deadline = SystemClock.elapsedRealtime() + 10_000
        var sawSession = false
        while (VpnLive.state.value.code != VpnErrorCode.RECOVERY_NEEDS_USER && SystemClock.elapsedRealtime() < deadline) {
            val state = VpnLive.state.value
            if (state.state == "connecting" || state.attemptProfileId != null || state.activeProfileId != null) sawSession = true
            Thread.sleep(10)
        }
        val state = VpnLive.state.value
        // Before the fix the always-on start resolved no profile and the state stayed idle.
        assertEquals(VpnErrorCode.RECOVERY_NEEDS_USER, state.code)
        assertFalse("A session attempt was published", sawSession)
        assertNull(state.activeProfileId)
        assertNull(preferences.getString(BimeronVpnService.ACTIVE_ID, null))
        assertEquals(profile.id, readSelectedProfileId(context))
    }

    @Test fun alwaysOnStartWithoutAProfileEndsQuietly(): Unit = runBlocking {
        writeSelectedProfileId(context, null)
        val expected = context.getString(R.string.journal_always_on_no_profile)

        startAlwaysOn()

        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (AppEvents.entries.value.none { it.message == expected } && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
        assertTrue("No journal entry for the skipped start", AppEvents.entries.value.any { it.message == expected })
        // The skipped start leaves the foreground and stops the service.
        val stopDeadline = SystemClock.elapsedRealtime() + 5_000
        while (serviceRunning() && SystemClock.elapsedRealtime() < stopDeadline) Thread.sleep(20)
        assertFalse("The service is still running", serviceRunning())
        val state = VpnLive.state.value
        assertEquals("disconnected", state.state)
        assertNull(state.code)
    }
}
