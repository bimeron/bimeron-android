package org.bimeron.app

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnRuntimeCleanupTest {
    @Test fun failedCleanupBlocksRecreatedService() {
        assertTrue(NativeCleanupGate.acquire())
        NativeCleanupGate.completed(false)
        VpnLive.serviceDestroyed("cleanup failed")
        assertFalse(NativeCleanupGate.acquire())
        // Only confirmed teardown releases ownership; service construction does not.
        NativeCleanupGate.completed(true)
        assertTrue(NativeCleanupGate.acquire())
        NativeCleanupGate.completed(true)
    }

    @Test fun stopFailureStillDrainsWorkers() = runBlocking {
        val worker = Job()
        assertFalse(finishPacketWorkers(listOf(worker)) {
            worker.complete()
            error("stop failed")
        })
        assertTrue(worker.isCompleted)
    }

    @Test fun stuckWorkerFailsWithinBound() = runBlocking {
        val worker = Job()
        try {
            assertFalse(finishPacketWorkers(listOf(worker), 20) {})
        } finally {
            worker.cancel()
        }
    }

    @Test fun completedWorkersAllowReplacement() = runBlocking {
        val worker = Job()
        assertTrue(finishPacketWorkers(listOf(worker)) { worker.complete() })
    }
}
