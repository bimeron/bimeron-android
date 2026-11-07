package org.bimeron.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bimeron.core.androidcore.Androidcore
import org.bimeron.core.androidcore.SocketProtector
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contract §13.2 SnapshotStartTest, at the level reachable without a server or a started VPN: the
 * service's snapshot step ([loadSessionSnapshot], exactly as runSession calls it) with the process-wide
 * [ProfileStore] the service itself uses (contract D5) and real native Core validation. The service itself is
 * not started, so no Session, TUN or consent is involved; a fetch is either the real [fetchSSConf]
 * (which must never run) or a counting fake that returns a valid body. A recovery start never fetches;
 * a user connect always fetches once (see [SSConfConnectFetchTest] for the bound network).
 */
@RunWith(AndroidJUnit4::class)
class SnapshotStartTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val source = "ssconf://snapshot-start.invalid/config"
    private val valid = "ss://YWVzLTEyOC1nY206cGFzcw@192.0.2.10:8388".toByteArray()
    private val replacement = "ss://YWVzLTEyOC1nY206b3RoZXI@192.0.2.11:8388".toByteArray()
    private val store by lazy { runBlocking { sharedStore(context) } }
    private val id = UUID.randomUUID()

    /**
     * Runs [body] between two deletions of every profile named [FIXTURE]: the first removes leftovers of an
     * aborted run, the second this test's fixture. Deleting a profile also deletes its ssconf snapshot.
     */
    private fun withFixture(body: suspend () -> Unit) = runBlocking {
        store.deleteFixturesNamed(FIXTURE)
        runThenCleanUp({ body() }) { store.deleteFixturesNamed(FIXTURE) }
    }

    private suspend fun saveProfile() {
        assertTrue(store.compareAndSave(UUID.randomUUID(), id, null, Conf.fromKey(FIXTURE, source), null)
            is CommitResult.Committed)
    }

    private suspend fun persist(body: ByteArray) {
        val ticket = store.readForSession(id.toString()) { true }.ticket!!
        assertTrue(store.commitSnapshot(ticket, body) is SnapshotCommitResult.Committed)
    }

    /** One snapshot step with the attempt token, sampling the lease observer count throughout. */
    private fun attempt(
        recovery: Boolean,
        fetch: suspend (String) -> ByteArray,
    ): Pair<org.bimeron.core.androidcore.SSConfSnapshot, Int> =
        runBlocking(Dispatchers.Default) {
            val token = VpnLive.gate.next()
            val maxObservers = AtomicInteger(SSConfNetworkLease.observerCount)
            val sampler = launch {
                while (true) { maxObservers.accumulateAndGet(SSConfNetworkLease.observerCount) { a, b -> maxOf(a, b) }; delay(5) }
            }
            val snapshot = try {
                val input = readSessionInput(store, id.toString(), token)
                loadSessionSnapshot(store, input, token, recovery, fetch)
            } finally {
                sampler.cancelAndJoin()
            }
            maxObservers.accumulateAndGet(SSConfNetworkLease.observerCount) { a, b -> maxOf(a, b) }
            snapshot to maxObservers.get()
        }

    @Test fun fixtureBodiesAreAcceptedByCore() {
        Androidcore.newSSConfSnapshot(source, valid)
        Androidcore.newSSConfSnapshot(source, replacement)
    }

    @Test fun recoveryWithValidPersistedSnapshotStartsWithoutFetch() = withFixture {
        saveProfile()
        persist(valid)
        val state = VpnLive.state.value
        var fetches = 0
        val (snapshot, observers) = attempt(recovery = true) { fetches++; fetchSSConf(context, it) }
        assertEquals(0, fetches)
        assertEquals(0, observers)
        assertEquals(0, SSConfNetworkLease.observerCount)
        assertEquals(state, VpnLive.state.value)
        // The handle feeds the SSConf session factory; construction is local and starts nothing.
        val session = Androidcore.newSessionWithSSConfSnapshot(
            store.readForSession(id.toString()) { true }.bytes, snapshot, null, null,
            object : SocketProtector {
                override fun protect(fd: Long): Boolean = throw AssertionError("Unstarted session opened a socket")
            })
        session.stop()
    }

    @Test fun userConnectWithValidPersistedSnapshotFetchesOnceAndReplaces() = withFixture {
        saveProfile()
        persist(valid)
        var fetches = 0
        attempt(recovery = false) { fetched -> assertEquals(source, fetched); fetches++; replacement }
        assertEquals(1, fetches)
        assertArrayEquals(replacement, store.readForSession(id.toString()) { true }.snapshot!!.body)
    }

    @Test fun corruptPersistedSnapshotIsReplacedByTheUserConnectFetch() = withFixture {
        saveProfile()
        persist(valid)
        // The store keeps snapshots in ssconf/ beside its profiles directory, under the app's files directory.
        val file = File(context.filesDir, "ssconf/$id.snap")
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() })
        assertNull(store.readForSession(id.toString()) { true }.snapshot)
        var fetches = 0
        attempt(recovery = false) { fetched -> assertEquals(source, fetched); fetches++; replacement }
        assertEquals(1, fetches)
        assertArrayEquals(replacement, store.readForSession(id.toString()) { true }.snapshot!!.body)
    }

    /** A recovery start discards a Core-rejected persisted body and, never fetching, needs the user. */
    @Test fun coreRejectedPersistedSnapshotIsDiscardedOnRecoveryWithoutFetch() = withFixture {
        saveProfile()
        persist("not a shadowsocks body".toByteArray())
        assertNotNull(store.readForSession(id.toString()) { true }.snapshot)
        var fetches = 0
        try {
            attempt(recovery = true) { fetches++; replacement }
            fail("recovery started without a usable snapshot")
        } catch (failure: SSConfFailure) {
            assertEquals(VpnErrorCode.RECOVERY_NEEDS_USER, failure.code)
        }
        assertEquals(0, fetches)
        assertNull(store.readForSession(id.toString()) { true }.snapshot)
    }

    private companion object {
        const val FIXTURE = "Snapshot start fixture"
    }
}
