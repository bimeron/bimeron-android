package org.bimeron.app

import org.junit.Assert.*
import org.junit.Test

class SSConfLeaseStateTest {
    @Test fun validityChangesBeforeDeferredNotification() {
        val state = SSConfLeaseState()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        state.onInvalidated { calls.incrementAndGet() }
        val notify = state.invalidateDeferred()
        assertFalse(state.isValid())
        assertEquals(0, calls.get())
        val thread = Thread { state.invalidate() }
        thread.start()
        thread.join(1000)
        assertFalse(thread.isAlive)
        notify()
        assertEquals(1, calls.get())
    }

    @Test fun invalidationIsOnceAndLateRegistrationIsImmediate() {
        val state = SSConfLeaseState()
        var calls = 0
        state.onInvalidated { calls++; assertFalse(state.isValid()) }
        state.invalidate()
        state.invalidate()
        assertEquals(1, calls)
        state.onInvalidated { calls++ }
        assertEquals(2, calls)
    }

    @Test fun identicalCallbacksHaveIndependentRegistrations() {
        val state = SSConfLeaseState()
        var calls = 0
        val callback = { calls++; Unit }
        state.onInvalidated(callback).close()
        state.onInvalidated(callback)
        state.onInvalidated(callback)
        state.invalidate()
        assertEquals(2, calls)
    }

    @Test fun normalCloseIsSilentAndDisablesDescriptors() {
        val state = SSConfLeaseState()
        var calls = 0
        state.onInvalidated { calls++ }
        state.close()
        state.invalidate()
        assertFalse(state.isValid())
        assertFalse(state.fd(1) { error("must not run") })
        assertEquals(0, calls)
    }

    @Test fun removedListenersAndBadDescriptorsDoNotRun() {
        val state = SSConfLeaseState()
        state.onInvalidated { error("removed") }.close()
        assertFalse(state.fd(-1) { error("negative") })
        assertFalse(state.fd(Int.MAX_VALUE.toLong() + 1) { error("overflow") })
        assertTrue(state.fd(4) { it == 4 })
        assertFalse(state.fd(4) { throw IllegalStateException() })
        state.invalidate()
    }
}
