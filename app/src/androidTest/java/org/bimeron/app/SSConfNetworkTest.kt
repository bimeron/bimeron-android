package org.bimeron.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.DatagramSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Run only on an allocated isolated device. Temporarily sets consent app-op; never starts a VPN. */
@RunWith(AndroidJUnit4::class)
class SSConfNetworkTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun bound(block: (BimeronVpnService) -> Unit) {
        val ready = CountDownLatch(1)
        var service: BimeronVpnService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = (binder as BimeronVpnService.ResolverBinder).service()
                ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, BimeronVpnService::class.java)
            .setAction(SSConfNetworkLease.BIND_ACTION), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            block(checkNotNull(service))
        } finally {
            context.unbindService(connection)
            service?.let { awaitDestroyed(it) }
        }
    }

    private fun awaitDestroyed(service: BimeronVpnService) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        var destroyed = false
        do {
            // onDestroy sets the flag before cleanup; read on main after the lifecycle callback returns.
            InstrumentationRegistry.getInstrumentation().runOnMainSync { destroyed = service.resolverDestroyed }
            if (!destroyed) Thread.sleep(10)
        } while (!destroyed && android.os.SystemClock.elapsedRealtime() < deadline)
        assertTrue("Framework must finish destroying unbound service", destroyed)
    }

    @Test fun bindOnlyDestructionPreservesGlobalState() {
        val before = VpnLive.state.value
        val protection = VpnLive.protection.value
        val fixture = VpnState("connected", received = 123, sent = 456,
            activeProfileId = "synthetic-bind-only", attemptProfileId = "synthetic-bind-only",
            connectedAtElapsed = 789)
        val token = VpnLive.gate.next()
        try {
            VpnLive.state.value = fixture
            VpnLive.protection.value = true to true
            bound { service ->
                assertNotNull(service.onBind(Intent(VpnService.SERVICE_INTERFACE)))
                assertNull(service.onBind(Intent("unknown")))
            }
            assertTrue("Bind-only destruction must preserve the attempt token", VpnLive.gate.accepts(token))
            assertEquals(fixture, VpnLive.state.value)
            assertEquals(true to true, VpnLive.protection.value)
        } finally {
            VpnLive.state.value = before
            VpnLive.protection.value = protection
        }
    }

    @Test fun simulatedStartedJobStopsOnOffMainRevokeWhileBound() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val started = BimeronVpnService::class.java.getDeclaredField("started").apply { isAccessible = true }
        val runJob = BimeronVpnService::class.java.getDeclaredField("runJob").apply { isAccessible = true }
        // A cross-thread execution alone cannot deterministically expose a stale ordinary-field read.
        assertTrue("Off-main revoke requires visible started state",
            java.lang.reflect.Modifier.isVolatile(started.modifiers))
        val before = VpnLive.state.value
        val protection = VpnLive.protection.value
        val events = AppEvents.entries.value
        val recovery = context.getSharedPreferences("vpn_recovery", Context.MODE_PRIVATE)
        val hadRecovery = recovery.contains("active_id")
        val recoveryId = recovery.getString("active_id", null)
        val simulated = Job()
        try {
            bound { service ->
                try {
                    instrumentation.runOnMainSync {
                        assertFalse(started.getBoolean(service))
                        assertNull(runJob.get(service))
                        // Synthetic fields only: never call onStartCommand, Session.start, or establish.
                        VpnLive.state.value = VpnState("connected", activeProfileId = "synthetic-revoke")
                        started.setBoolean(service, true)
                        runJob.set(service, simulated)
                    }
                    runBlocking(Dispatchers.Default) {
                        assertNotEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
                        service.onRevoke()
                        withTimeout(5000) { simulated.join() }
                        withTimeout(5000) {
                            while (VpnLive.state.value.state != "disconnected") delay(10)
                        }
                    }
                    instrumentation.runOnMainSync {
                        assertTrue("Stop must cancel the synthetic job", simulated.isCancelled)
                        assertNull(runJob.get(service))
                        assertFalse("Resolver binding must retain service through Stop", service.resolverDestroyed)
                    }
                } finally {
                    instrumentation.runOnMainSync {
                        started.setBoolean(service, false)
                        runJob.set(service, null)
                    }
                    simulated.cancel()
                }
            }
        } finally {
            VpnLive.state.value = before
            VpnLive.protection.value = protection
            AppEvents.entries.value = events
            val editor = recovery.edit()
            if (hadRecovery) editor.putString("active_id", recoveryId) else editor.remove("active_id")
            assertTrue("Restore synthetic Stop recovery fixture", editor.commit())
        }
    }

    @Test fun simulatedStartedActiveStateSurvivesResolverBindRelease() = SSConfConsentFixture.withConsent(context, true) {
        bound { service ->
            val started = BimeronVpnService::class.java.getDeclaredField("started").apply { isAccessible = true }
            val runJob = BimeronVpnService::class.java.getDeclaredField("runJob").apply { isAccessible = true }
            assertFalse(started.getBoolean(service))
            assertNull(runJob.get(service))
            val simulated = Job()
            val before = VpnLive.state.value
            val protection = VpnLive.protection.value
            try {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    started.setBoolean(service, true)
                    runJob.set(service, simulated)
                }
                runBlocking {
                    withSSConfNetwork(context) {
                        assertEquals(1, service.resolverCount)
                        it.isValid().let { valid -> assertTrue(it.diagnostics(), valid) }
                    }
                }
                assertEquals(0, service.resolverCount)
                assertFalse(service.resolverDestroyed)
                assertTrue(started.getBoolean(service))
                assertSame(simulated, runJob.get(service))
                assertTrue(simulated.isActive)
                assertEquals(before, VpnLive.state.value)
                assertEquals(protection, VpnLive.protection.value)
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    started.setBoolean(service, false)
                    runJob.set(service, null)
                }
                simulated.cancel()
            }
        }
    }

    private class TrackingContext(base: Context, private val deliver: Boolean = true) : android.content.ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        val connected = CountDownLatch(1)
        @Volatile var bound = false
        @Volatile var service: BimeronVpnService? = null
        private var forwarded: ServiceConnection? = null
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            val bridge = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    service = (binder as BimeronVpnService.ResolverBinder).service()
                    connected.countDown()
                    if (deliver) connection.onServiceConnected(name, binder)
                }
                override fun onServiceDisconnected(name: ComponentName) = connection.onServiceDisconnected(name)
            }
            forwarded = bridge
            return super.bindService(intent, bridge, flags).also { bound = it }
        }
        override fun unbindService(connection: ServiceConnection) {
            super.unbindService(checkNotNull(forwarded))
            bound = false
        }
    }

    @Test fun bindingAcquisitionCancellationUnbindsBeforeCapture() = SSConfConsentFixture.withConsent(context, true) {
        runBlocking {
            val tracked = TrackingContext(context, deliver = false)
            val baseline = SSConfNetworkLease.observerCount
            val worker = launch(Dispatchers.Default) {
                withSSConfNetwork(tracked) { fail("Binding not delivered") }
            }
            try {
                assertTrue(tracked.connected.await(5, TimeUnit.SECONDS))
                assertTrue(tracked.bound)
            } finally { worker.cancelAndJoin() }
            assertFalse(tracked.bound)
            assertEquals(baseline, SSConfNetworkLease.observerCount)
            awaitDestroyed(checkNotNull(tracked.service))
        }
    }

    @Test fun failedBindingAttemptsStillUnbind() = SSConfConsentFixture.withConsent(context, true) {
        for (throws in listOf(false, true)) {
            var attempts = 0
            var unbinds = 0
            val fake = object : android.content.ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
                    attempts++
                    if (throws) throw SecurityException("test")
                    return false
                }
                override fun unbindService(connection: ServiceConnection) {
                    unbinds++
                    throw IllegalArgumentException("Not registered")
                }
            }
            try {
                runBlocking { withSSConfNetwork(fake) { fail("Failed binding entered block") } }
                fail("Expected unavailable")
            } catch (expected: SSConfNetworkUnavailableException) {
                assertEquals("SSConf network unavailable", expected.message)
            }
            assertEquals(1, attempts)
            assertEquals(1, unbinds)
        }
    }

    @Test fun acquisitionCancellationClosesObserverAndBinding() = SSConfConsentFixture.withConsent(context, true) {
        runBlocking {
            val tracked = TrackingContext(context)
            val registered = CompletableDeferred<Unit>()
            val baseline = SSConfNetworkLease.observerCount
            val worker = launch(Dispatchers.Default) {
                withSSConfNetworkCapture(tracked, { service ->
                    SSConfNetworkLease.capture(service) {
                        registered.complete(Unit)
                        awaitCancellation()
                    }
                }) { fail("Cancelled acquisition must not enter block") }
            }
            try {
                withTimeout(5000) { registered.await() }
                assertTrue(tracked.bound)
                assertEquals(baseline + 1, SSConfNetworkLease.observerCount)
            } finally { worker.cancelAndJoin() }
            assertFalse(tracked.bound)
            assertEquals(baseline, SSConfNetworkLease.observerCount)
            assertEquals(0, checkNotNull(tracked.service).resolverCount)
            awaitDestroyed(checkNotNull(tracked.service))
        }
    }

    @Test fun unpreparedAccessorFailsWithoutRunningBlock() = SSConfConsentFixture.withConsent(context, false) {
        assertTrue(VpnService.prepare(context) != null)
        try {
            runBlocking { withSSConfNetwork(context) { fail("Unprepared block executed") } }
            fail("Expected unavailable")
        } catch (expected: SSConfNetworkUnavailableException) {
            assertEquals("SSConf network unavailable", expected.message)
        }
    }

    @Test fun cancellationSignalsBeforeWorkerExitAndWaitsForCleanup() = SSConfConsentFixture.withConsent(context, true) { runBlocking {
        assertTrue(VpnService.prepare(context) == null)
        val tracked = TrackingContext(context)
        val baseline = SSConfNetworkLease.observerCount
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val exit = CountDownLatch(1)
        val worker = launch(Dispatchers.Default) {
            withSSConfNetwork(tracked) { lease ->
                lease.onInvalidated { cancelled.countDown() }.use {
                    entered.countDown()
                    check(exit.await(10, TimeUnit.SECONDS))
                }
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            worker.cancel()
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            assertFalse("Blocking resolver must still own its scope", worker.isCompleted)
            assertTrue(tracked.bound)
            assertFalse(checkNotNull(tracked.service).resolverDestroyed)
            assertEquals(1, checkNotNull(tracked.service).resolverCount)
            assertEquals(baseline + 1, SSConfNetworkLease.observerCount)
        } finally {
            exit.countDown()
            worker.cancelAndJoin()
        }
        assertFalse(tracked.bound)
        assertEquals(0, checkNotNull(tracked.service).resolverCount)
        assertEquals(baseline, SSConfNetworkLease.observerCount)
        awaitDestroyed(checkNotNull(tracked.service))
    }

    }

    @Test fun preparedSocketsKeepOriginalOwnershipAndRevokeInvalidates() = SSConfConsentFixture.withConsent(context, true) {
        assertTrue("Consent fixture must prepare VPN consent", VpnService.prepare(context) == null)
        bound { service ->
            var revoked = false
            try { runBlocking {
                withSSConfNetwork(context) { lease ->
                    lease.isValid().let { valid -> assertTrue(lease.diagnostics(), valid) }
                    assertTrue(lease.networkID.isNotEmpty())
                    assertTrue(lease.dns.split(',').size in 1..4)
                    DatagramSocket().use { socket ->
                        ParcelFileDescriptor.fromDatagramSocket(socket).use { original ->
                            lease.protect(original.fd.toLong()).let { accepted -> assertTrue(lease.diagnostics(), accepted) }
                            lease.bind(original.fd.toLong()).let { bound -> assertTrue(lease.diagnostics(), bound) }
                            assertTrue(original.fileDescriptor.valid())
                            assertFalse(socket.isClosed)
                            assertFalse(lease.bind(-1))
                            assertFalse(lease.protect(Int.MAX_VALUE.toLong() + 1))
                            var cancelled = false
                            lease.onInvalidated { cancelled = true }.use {
                                // Synthetic lifecycle signal; no real VPN/session is started.
                                InstrumentationRegistry.getInstrumentation().runOnMainSync { service.onRevoke() }
                                revoked = true
                                assertTrue(cancelled)
                                assertFalse(lease.isValid())
                                assertFalse(lease.protect(original.fd.toLong()))
                                assertFalse(lease.bind(original.fd.toLong()))
                            }
                        }
                    }
                }
            }; fail("Revoked scope returned successfully")
            } catch (failure: SSConfNetworkUnavailableException) { assertTrue("Must reach revoke: ${failure.message}", revoked) }
        }
    }
}
