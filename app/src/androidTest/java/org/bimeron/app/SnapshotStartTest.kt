package org.bimeron.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bimeron.core.androidcore.Androidcore
import org.bimeron.core.androidcore.SocketProtector
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contract §13.2 SnapshotStartTest, at the level reachable without a server or a started VPN: the
 * service's snapshot step ([loadSessionSnapshot], exactly as runSession calls it) with a real
 * [ProfileStore] in a private test directory and real native Core validation. The service itself is
 * not started, so no Session, TUN or consent is involved; a fetch is either the real [fetchSSConf]
 * (which must never run) or a counting fake that returns a valid body.
 */
@RunWith(AndroidJUnit4::class)
class SnapshotStartTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val source = "ssconf://snapshot-start.invalid/config"
    private val valid = "ss://YWVzLTEyOC1nY206cGFzcw@192.0.2.10:8388".toByteArray()
    private val replacement = "ss://YWVzLTEyOC1nY206b3RoZXI@192.0.2.11:8388".toByteArray()
    private val root = File(context.cacheDir, "snapshot-start-${UUID.randomUUID()}")
    private val store by lazy {
        ProfileStore(null, File(root, "profiles").apply { mkdirs() }, SshProfileResources(File(root, "ssh-resources"))) {}
    }
    private val id = UUID.randomUUID()

    @After fun cleanUp() { root.deleteRecursively() }

    private suspend fun saveProfile() {
        assertTrue(store.compareAndSave(UUID.randomUUID(), id, null, Conf.fromKey("Snapshot start", source), null)
            is CommitResult.Committed)
    }

    private suspend fun persist(body: ByteArray) {
        val ticket = store.readForSession(id.toString()) { true }.ticket!!
        assertTrue(store.commitSnapshot(ticket, body) is SnapshotCommitResult.Committed)
    }

    /** One snapshot step with the attempt token, sampling the lease observer count throughout. */
    private fun attempt(fetch: suspend (String) -> ByteArray): Pair<org.bimeron.core.androidcore.SSConfSnapshot, Int> =
        runBlocking(Dispatchers.Default) {
            val token = VpnLive.gate.next()
            var maxObservers = SSConfNetworkLease.observerCount
            val sampler = launch { while (true) { maxObservers = maxOf(maxObservers, SSConfNetworkLease.observerCount); delay(5) } }
            try {
                val input = readSessionInput(store, id.toString(), token)
                loadSessionSnapshot(store, input, token, fetch) to maxObservers
            } finally {
                sampler.cancel()
            }
        }

    @Test fun fixtureBodiesAreAcceptedByCore() {
        Androidcore.newSSConfSnapshot(source, valid)
        Androidcore.newSSConfSnapshot(source, replacement)
    }

    @Test fun validPersistedSnapshotStartsWithoutFetch() = runBlocking {
        saveProfile()
        persist(valid)
        val state = VpnLive.state.value
        var fetches = 0
        val (snapshot, observers) = attempt { fetches++; fetchSSConf(context, it) }
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

    @Test fun corruptPersistedSnapshotFetchesExactlyOnceAndReplaces() = runBlocking {
        saveProfile()
        persist(valid)
        val file = File(root, "ssconf/$id.snap")
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() })
        assertNull(store.readForSession(id.toString()) { true }.snapshot)
        var fetches = 0
        attempt { fetched -> assertEquals(source, fetched); fetches++; replacement }
        assertEquals(1, fetches)
        assertArrayEquals(replacement, store.readForSession(id.toString()) { true }.snapshot!!.body)
    }

    @Test fun coreRejectedPersistedSnapshotIsTreatedAsAbsent() = runBlocking {
        saveProfile()
        persist("not a shadowsocks body".toByteArray())
        assertNotNull(store.readForSession(id.toString()) { true }.snapshot)
        var fetches = 0
        attempt { fetches++; replacement }
        assertEquals(1, fetches)
        assertArrayEquals(replacement, store.readForSession(id.toString()) { true }.snapshot!!.body)
    }
}
