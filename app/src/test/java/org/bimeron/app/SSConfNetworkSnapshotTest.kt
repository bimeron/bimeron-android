package org.bimeron.app

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SSConfNetworkSnapshotTest {
    @Test fun diagnosticCodesKeepFirstInvalidationAndRedactOperationErrors() = runBlocking {
        for (reason in listOf(SSConfDiagnostic.NETWORK_SELECTION, SSConfDiagnostic.NETWORK_LOST,
            SSConfDiagnostic.NETWORK_DNS)) {
            val snapshot = SSConfNetworkSnapshot<String>()
            snapshot.capabilities("private-network", 2)
            snapshot.properties("private-network", "private-dns")
            val state = snapshot.capture().state
            when (reason) {
                SSConfDiagnostic.NETWORK_SELECTION -> snapshot.capabilities("replacement", 3)
                SSConfDiagnostic.NETWORK_LOST -> snapshot.lost("private-network")
                else -> snapshot.properties("private-network", "changed-private-dns")
            }
            state.invalidate(SSConfDiagnostic.CANCELLATION)
            state.close()
            val report = state.diagnostics()
            assertTrue(report, report.contains("first=$reason "))
            assertTrue(report, report.contains("captureCandidates=1 captureReady=1 captureScore=2"))
            assertFalse(report.contains("private"))
        }
        val state = SSConfLeaseState()
        assertFalse(state.fd(0) { throw IllegalStateException("private-exception") })
        assertTrue(state.diagnostics().contains("fdResult=EXCEPTION"))
        assertFalse(state.diagnostics().contains("private-exception"))
        state.close()
        state.invalidate(SSConfDiagnostic.CANCELLATION)
        assertTrue(state.diagnostics().contains("state=CLOSED"))
        assertTrue(state.diagnostics().contains("first=NONE"))
    }

    @Test fun cellCaptureIsInvalidatedByDelayedInitialWifiCapabilities() = runBlocking {
        val snapshot = SSConfNetworkSnapshot<String>()
        snapshot.capabilities("cell", 2)
        snapshot.properties("cell", "1.1.1.1")
        val lease = snapshot.capture()
        var cancellations = 0
        lease.state.onInvalidated { cancellations++ }
        assertEquals("cell", lease.network)
        assertEquals("1.1.1.1", lease.dns)
        assertTrue(lease.state.isValid())

        // Model delayed INITIAL delivery; the state machine cannot distinguish it
        // from a genuinely new network arriving after acquisition.
        snapshot.capabilities("wifi", 3)
        assertFalse(lease.state.isValid())
        assertEquals(1, cancellations)
        snapshot.properties("wifi", "9.9.9.9")
        assertFalse(lease.state.isValid())
        assertEquals(1, cancellations)
        assertEquals("cell", lease.network)
        assertEquals("1.1.1.1", lease.dns)
    }

    @Test fun laterInitialNetworkWithinSettleWindowIsCapturedInsteadOfInvalidating() = runBlocking {
        // API 34 emulator: cell (validated, 2) is delivered first, Wi-Fi (3) shortly after.
        val snapshot = SSConfNetworkSnapshot<String>()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { snapshot.capture() }
        snapshot.capabilities("cell", 2)
        snapshot.properties("cell", "1.1.1.1")
        delay(50)
        assertFalse("Capture must wait for the settle window", pending.isCompleted)
        snapshot.capabilities("wifi", 3)
        snapshot.properties("wifi", "9.9.9.9")
        val lease = pending.await()
        assertEquals("wifi", lease.network)
        assertEquals("9.9.9.9", lease.dns)
        assertTrue(lease.state.isValid())
        snapshot.capabilities("cell", 2)
        snapshot.properties("cell", "1.1.1.1")
        assertTrue("Unselected repeat callbacks keep the lease", lease.state.isValid())
    }

    @Test fun initialCallbacksLandingRightAfterNotReadyObservationStillSettle() = runBlocking {
        // Push run 42113514780: capture observed "not ready", the cell's initial callbacks landed
        // before publication, the cell was published without any quiet window (test took 53 ms)
        // and the Wi-Fi's initial callbacks then invalidated it with NETWORK_SELECTION.
        lateinit var snapshot: SSConfNetworkSnapshot<String>
        var injected = false
        snapshot = SSConfNetworkSnapshot(afterObservation = {
            if (!injected) {
                injected = true
                snapshot.capabilities("cell", 2)
                snapshot.properties("cell", "1.1.1.1")
            }
        })
        val pending = async(start = CoroutineStart.UNDISPATCHED) { snapshot.capture() }
        assertTrue(injected)
        assertFalse("A change after the readiness observation must start a quiet window", pending.isCompleted)
        snapshot.capabilities("wifi", 3)
        snapshot.properties("wifi", "9.9.9.9")
        val lease = pending.await()
        assertEquals("wifi", lease.network)
        assertEquals("9.9.9.9", lease.dns)
        assertTrue(lease.state.diagnostics(), lease.state.isValid())
    }

    @Test fun identicalRepeatedCallbacksDoNotExtendSettling() = runBlocking {
        val snapshot = SSConfNetworkSnapshot<String>(settleMillis = 200)
        snapshot.capabilities("wifi", 3)
        snapshot.properties("wifi", "1.1.1.1")
        val pending = async(Dispatchers.Default) { snapshot.capture() }
        val chatter = launch(Dispatchers.Default) {
            while (isActive) {
                snapshot.capabilities("wifi", 3)
                snapshot.properties("wifi", "1.1.1.1")
                delay(20)
            }
        }
        try {
            val lease = withTimeout(2_000) { pending.await() }
            assertEquals("wifi", lease.network)
            assertTrue(lease.state.isValid())
        } finally {
            chatter.cancelAndJoin()
        }
    }

    @Test fun selectedLossDuringSettleWaitsForReplacement() = runBlocking {
        val snapshot = SSConfNetworkSnapshot<String>()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { snapshot.capture() }
        snapshot.capabilities("wifi", 3)
        snapshot.properties("wifi", "1.1.1.1")
        delay(50)
        snapshot.lost("wifi")
        delay(400)
        assertFalse("No network: nothing to capture", pending.isCompleted)
        snapshot.capabilities("cell", 2)
        snapshot.properties("cell", "2.2.2.2")
        val lease = pending.await()
        assertEquals("cell", lease.network)
        assertTrue(lease.state.isValid())
    }

    @Test fun initialDeliveryWaitsForBothFieldsWithoutInvalidation() = runBlocking {
        val snapshot = SSConfNetworkSnapshot<String>()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { snapshot.capture() }
        snapshot.capabilities("wifi", 3)
        yield()
        assertFalse(pending.isCompleted)
        snapshot.properties("wifi", "1.1.1.1")
        val lease = pending.await()
        snapshot.capabilities("wifi", 3)
        snapshot.properties("wifi", "1.1.1.1")
        assertTrue(lease.state.isValid())
    }

    @Test fun propertiesBeforeCapabilitiesAndDnsChangeUseCallbackValues() = runBlocking {
        val snapshot = SSConfNetworkSnapshot<String>()
        snapshot.properties("wifi", "1.1.1.1")
        snapshot.capabilities("wifi", 3)
        val lease = snapshot.capture()
        var calls = 0
        lease.state.onInvalidated { calls++ }
        snapshot.properties("wifi", "8.8.8.8")
        assertFalse(lease.state.isValid())
        assertEquals("1.1.1.1", lease.dns)
        assertEquals(1, calls)
    }

    @Test fun replacementAndLossInvalidateButUnselectedChangesDoNot() = runBlocking {
        for (replace in listOf(true, false)) {
            val snapshot = SSConfNetworkSnapshot<String>()
            snapshot.capabilities("cell", 2)
            snapshot.properties("cell", "1.1.1.1")
            val lease = snapshot.capture()
            snapshot.capabilities("other", 0)
            snapshot.properties("other", "9.9.9.9")
            snapshot.lost("other")
            assertTrue(lease.state.isValid())
            if (replace) snapshot.capabilities("wifi", 3) else snapshot.lost("cell")
            assertFalse(lease.state.isValid())
            assertEquals("cell", lease.network)
        }
    }

    @Test fun selectedNetworkLosingEligibilityInvalidatesImmediately() = runBlocking {
        val snapshot = SSConfNetworkSnapshot<String>()
        snapshot.capabilities("wifi", 3)
        snapshot.properties("wifi", "1.1.1.1")
        val lease = snapshot.capture()
        snapshot.capabilities("wifi", null)
        assertFalse(lease.state.isValid())
    }

    @Test fun captureRacingDnsCallbackNeverPublishesStaleValidLease() = runBlocking {
        repeat(100) {
            // No settle window: this exercises the capture/callback lock race itself.
            val snapshot = SSConfNetworkSnapshot<String>(settleMillis = 0)
            snapshot.capabilities("wifi", 3)
            snapshot.properties("wifi", "1.1.1.1")
            val start = CountDownLatch(1)
            val callback = Thread {
                start.await()
                snapshot.properties("wifi", "8.8.8.8")
            }
            callback.start()
            start.countDown()
            val lease = snapshot.capture()
            callback.join(1000)
            assertFalse(callback.isAlive)
            assertTrue(lease.dns == "8.8.8.8" || !lease.state.isValid())
        }
    }

    @Test fun cancellationWhileWaitingDoesNotProduceLease() = runBlocking {
        val snapshot = SSConfNetworkSnapshot<String>()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { snapshot.capture() }
        snapshot.capabilities("wifi", 3)
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
    }

    @Test fun concurrentCallbackInvalidatesBeforeNotificationAndReleasesNetworkLock() = runBlocking {
        val snapshot = SSConfNetworkSnapshot<String>()
        snapshot.capabilities("wifi", 3)
        snapshot.properties("wifi", "1.1.1.1")
        val lease = snapshot.capture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        lease.state.onInvalidated {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val first = Thread { snapshot.properties("wifi", "8.8.8.8") }
        first.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(lease.state.isValid())
            val second = Thread { snapshot.lost("wifi") }
            second.start()
            second.join(1000)
            assertFalse("Listener must not hold network lock", second.isAlive)
        } finally {
            release.countDown()
            first.join(1000)
        }
    }
}
