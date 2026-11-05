package org.bimeron.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttemptGateTest {
    @Test fun staleAttemptCannotPublishAfterCancelOrReconnect() {
        val gate = AttemptGate()
        val first = gate.next()
        assertTrue(gate.accepts(first))
        val cancelled = gate.next()
        assertFalse(gate.accepts(first))
        val replacement = gate.next()
        assertFalse(gate.accepts(cancelled))
        assertTrue(gate.accepts(replacement))
    }
    @Test fun staleCommitDoesNotRun() {
        val gate = AttemptGate()
        val stale = gate.next()
        gate.next()
        gate.commit(stale) { throw AssertionError("stale publication") }
    }

    @Test fun nextWaitsForCommitAndRejectsItsStaleToken() {
        val gate = AttemptGate()
        val token = gate.next()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val attemptingNext = java.util.concurrent.CountDownLatch(1)
        val nextDone = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val result = java.util.concurrent.atomic.AtomicReference("initial")
        fun worker(block: () -> Unit) = Thread {
            try { block() } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }.apply { isDaemon = true; start() }
        val publisher = worker {
            gate.commit(token) {
                entered.countDown()
                assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                result.set("committed")
            }
        }
        var successor: Thread? = null
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            successor = worker {
                attemptingNext.countDown()
                gate.next()
                result.set("replacement")
                nextDone.countDown()
            }
            assertTrue(attemptingNext.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (successor.state != Thread.State.BLOCKED && nextDone.count > 0 && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertTrue("next must block on commit's monitor", successor.state == Thread.State.BLOCKED)
            assertTrue(nextDone.count == 1L)
        } finally {
            release.countDown()
            publisher.join(5_000)
            successor?.join(5_000)
        }
        failure.get()?.let { throw AssertionError("worker failed", it) }
        assertFalse(publisher.isAlive)
        assertFalse(requireNotNull(successor).isAlive)
        assertTrue(nextDone.count == 0L)
        gate.commit(token) { result.set("stale") }
        org.junit.Assert.assertEquals("replacement", result.get())
    }

    @Test fun resetRejectsPublicationFromPreviousLifetime() {
        try {
            val token = VpnLive.gate.next()
            VpnLive.publish(VpnState("connecting"), token)
            VpnLive.resetAfterProcessStart()
            VpnLive.publish(VpnState("connected"), token)
            org.junit.Assert.assertEquals(VpnState(), VpnLive.state.value)
            val replacement = VpnLive.gate.next()
            VpnLive.publish(VpnState("connecting"), replacement)
            org.junit.Assert.assertEquals("connecting", VpnLive.state.value.state)
        } finally {
            VpnLive.resetAfterProcessStart()
        }
    }
    @org.junit.Test fun destructionPreservesFailureAndRejectsLateCompletion() {
        VpnLive.resetAfterProcessStart()
        val token = VpnLive.gate.next()
        val failed = VpnState("failed", message = "cleanup failed")
        VpnLive.publish(failed, token)
        // Finished preserves failed before Android destroys the stopped service.
        VpnLive.serviceDestroyed("stop failed")
        VpnLive.publish(VpnState(), token)
        org.junit.Assert.assertEquals(failed, VpnLive.state.value)
    }

    @org.junit.Test fun unexpectedDestructionDoesNotClaimCleanupSuccess() {
        VpnLive.resetAfterProcessStart()
        val token = VpnLive.gate.next()
        VpnLive.publish(VpnState("connected"), token)
        VpnLive.serviceDestroyed("cleanup unconfirmed")
        VpnLive.publish(VpnState(), token)
        org.junit.Assert.assertEquals("failed", VpnLive.state.value.state)
    }
}
