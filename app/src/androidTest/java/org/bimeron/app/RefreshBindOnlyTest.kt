package org.bimeron.app

import android.app.ActivityManager
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contract §13.2 RefreshBindOnlyTest. Drives the production [AppModel.refreshSnapshot] wiring (real store,
 * real fetchSSConf, real Core validation) against a loopback source with no listener, so the fetch fails.
 * The bind-only guarantees hold on that path: no foreground service, no connect-permission state, no VPN
 * state or attempt-token change, and the SSConf network observer is released. Run only on an isolated test
 * device; uses [SSConfConsentFixture].
 */
@RunWith(AndroidJUnit4::class)
class RefreshBindOnlyTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    /** Loopback discard port: no listener, so the bounded request fails without leaving the device. */
    private val source = "ssconf://127.0.0.1:9/bimeron-refresh-test-secret"

    @Suppress("DEPRECATION") // Still returns this app's own services.
    private fun serviceRunning() = application.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE).any { it.service.className == BimeronVpnService::class.java.name }

    private fun refreshScenario(prepared: Boolean, block: suspend (AppModel, Profile) -> Unit) =
        SSConfConsentFixture.withConsent(application, prepared) {
            runBlocking {
                val store = ProfileStore.get(application)
                val profile = store.save(Conf.fromKey("Refresh fixture", source))
                val model = withContext(Dispatchers.Main) { AppModel(application) }
                try {
                    withTimeout(10_000) { model.profiles.first { items -> items.any { it.id == profile.id } } }
                    val token = VpnLive.gate.next()
                    val state = VpnLive.state.value
                    val protection = VpnLive.protection.value
                    assertEquals("No other lease may be open", 0, SSConfNetworkLease.observerCount)
                    assertFalse(serviceRunning())
                    block(model, profile)
                    assertEquals(0, SSConfNetworkLease.observerCount)
                    assertTrue("Refresh must not advance the attempt token", VpnLive.gate.accepts(token))
                    assertEquals(state, VpnLive.state.value)
                    assertEquals(protection, VpnLive.protection.value)
                    assertNull(model.pendingPermissionId)
                    assertNull(model.pendingApply.value)
                    assertFalse("Refresh must not start the VPN service", serviceRunning())
                    assertNull("A failed fetch persists nothing", store.readForSession(profile.id) { true }.snapshot)
                } finally {
                    withContext(Dispatchers.Main) { model.ssconfRefresh.cancel() }
                    store.delete(profile.id)
                }
            }
        }

    private suspend fun AppModel.finished(): SSConfRefreshResult = withTimeout(40_000) {
        ssconfRefresh.state.first { it is SSConfRefreshState.Finished } as SSConfRefreshState.Finished
    }.result

    @Test fun preparedRefreshIsBindOnly() = refreshScenario(prepared = true) { model, profile ->
        val job = withContext(Dispatchers.Main) { model.refreshSnapshot(profile.id) }
        val result = model.finished()
        job.join()
        // FETCH_FAILED when a physical network was leased; NETWORK_UNAVAILABLE when none is available.
        assertTrue(result.name, result in setOf(SSConfRefreshResult.FETCH_FAILED, SSConfRefreshResult.NETWORK_UNAVAILABLE))
    }

    /** Without consent the refresh asks through its own request; a denial ends it before any network use. */
    @Test fun unpreparedRefreshUsesItsOwnConsentAndNeverBinds() = refreshScenario(prepared = false) { model, profile ->
        var maxObservers = 0
        val watcher = CoroutineScope(Dispatchers.Default).launch {
            while (true) { maxObservers = maxOf(maxObservers, SSConfNetworkLease.observerCount); delay(1) }
        }
        val job = withContext(Dispatchers.Main) { model.refreshSnapshot(profile.id) }
        val request = withTimeout(10_000) { model.ssconfRefresh.consent.filterNotNull().first() }
        assertEquals(profile.id, request.profileId)
        assertNull("The connect permission flow is not used", model.pendingPermissionId)
        withContext(Dispatchers.Main) {
            assertTrue(model.ssconfRefresh.markLaunched(request))
            model.ssconfRefresh.answerConsent(false)
        }
        assertEquals(SSConfRefreshResult.CONSENT_REQUIRED, model.finished())
        job.join()
        watcher.cancel()
        assertEquals("A denied refresh never acquires the SSConf network", 0, maxObservers)
    }
}
