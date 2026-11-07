package org.bimeron.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bimeron.core.androidcore.Androidcore
import org.bimeron.core.androidcore.Session
import org.bimeron.core.androidcore.SocketProtector
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contract §13.2 SSConfConnectFetchTest (formerly RefreshBindOnlyTest): the connect-time ssconf fetch is
 * bind-only. Each attempt runs the service's composition from runSession, `createCoreSession(snapshot =
 * loadSessionSnapshot(...))`, with a private [ProfileStore], real Core validation, the production lease
 * scope of [fetchSSConf] ([withSSConfNetwork], consent pre-granted by [SSConfConsentFixture]) and real
 * unstarted `newSessionWithSSConfSnapshot`.
 *
 * Core's own request accepts only HTTPS with a system-trusted certificate, so a successful fetch uses a
 * test request instead of the native one: it opens a socket, has the lease protect and bind it to the
 * leased physical network, and downloads the body over plain HTTP from a local endpoint inside this test.
 * Failure paths use the real native request against a loopback port with no listener.
 *
 * Scope: up to unstarted session creation. The service is not started, no TUN is established and no
 * Session is started; the point where runSession would establish the TUN is recorded as "tun". Run only
 * on the CI emulator or an isolated test device with a physical IPv4 network.
 */
@RunWith(AndroidJUnit4::class)
class SSConfConnectFetchTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    /** Served by [LocalEndpoint]; the test request maps it to the leased network's own address. */
    private val localSource = "ssconf://connect-fetch.invalid/bimeron-connect-fetch-secret"
    /** Loopback discard port: no listener, so the real native request fails without leaving the device. */
    private val refusedSource = "ssconf://127.0.0.1:9/bimeron-connect-fetch-secret"
    private val valid = "ss://YWVzLTEyOC1nY206cGFzcw@192.0.2.10:8388".toByteArray()
    private val replacement = "ss://YWVzLTEyOC1nY206b3RoZXI@192.0.2.11:8388".toByteArray()
    private val root = File(context.cacheDir, "connect-fetch-${UUID.randomUUID()}")
    private val store by lazy {
        ProfileStore(null, File(root, "profiles").apply { mkdirs() }, SshProfileResources(File(root, "ssh-resources"))) {}
    }
    private val id = UUID.randomUUID()
    private var journal: android.content.res.Resources? = null

    @Before fun setUp() {
        journal = AppEvents.resources
        AppEvents.resources = context.resources
    }

    @After fun cleanUp() {
        AppEvents.resources = journal
        root.deleteRecursively()
    }

    /** A plain HTTP endpoint on all local addresses that serves [body] and counts requests. */
    private class LocalEndpoint(private val body: ByteArray) : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("0.0.0.0"))
        val port get() = server.localPort
        val requests = AtomicInteger()
        private val worker = thread(name = "ssconf test endpoint", isDaemon = true) {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: Exception) { break }
                client.use {
                    val reader = it.getInputStream().bufferedReader()
                    while (reader.readLine().orEmpty().isNotEmpty()) Unit
                    requests.incrementAndGet()
                    it.getOutputStream().apply {
                        write("HTTP/1.0 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
        }
        override fun close() { server.close(); worker.join(2_000) }
    }

    /** What the test request observed inside the lease scope. */
    private class Observed {
        val sources = Collections.synchronizedList(mutableListOf<String>())
        @Volatile var networkVpn: Boolean? = null
        @Volatile var protected = false
        @Volatile var bound = false
        @Volatile var vpnActive = false
    }

    /**
     * A request that downloads from [endpoint] at the leased network's own IPv4 address, through a socket
     * that the lease's protector protects and binds before connecting. Fails closed if either is refused.
     */
    private fun localRequest(endpoint: LocalEndpoint, observed: Observed): SSConfRequestFactory =
        { source, networkID, _, protector ->
            observed.sources += source
            val network = connectivity.allNetworks.single { it.toString() == networkID }
            val caps = checkNotNull(connectivity.getNetworkCapabilities(network))
            observed.networkVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            observed.vpnActive = vpnActive()
            val address = checkNotNull(connectivity.getLinkProperties(network)).linkAddresses
                .map { it.address }.first { it is Inet4Address }
            object : SSConfFetchRequest {
                override fun resolve(): ByteArray {
                    val raw = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0)
                    ParcelFileDescriptor.dup(raw).use { socket ->
                        Os.close(raw)
                        observed.protected = protector.protect(socket.fd.toLong())
                        observed.bound = protector.bind(socket.fd.toLong())
                        check(observed.protected && observed.bound) { "lease refused the socket" }
                        Os.connect(socket.fileDescriptor, address, endpoint.port)
                        val request = "GET / HTTP/1.0\r\nHost: test\r\n\r\n".toByteArray()
                        check(Os.write(socket.fileDescriptor, request, 0, request.size) == request.size)
                        val response = ParcelFileDescriptor.AutoCloseInputStream(socket.dup()).use { it.readBytes() }
                        val split = String(response, Charsets.ISO_8859_1).indexOf("\r\n\r\n")
                        check(split > 0) { "no HTTP response" }
                        return response.copyOfRange(split + 4, response.size)
                    }
                }
                override fun cancel() = Unit
            }
        }

    private fun vpnActive() = connectivity.allNetworks.any {
        connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    private suspend fun saveProfile(source: String) {
        assertTrue(store.compareAndSave(UUID.randomUUID(), id, null, Conf.fromKey("Connect fetch", source), null)
            is CommitResult.Committed)
    }

    private suspend fun persist(body: ByteArray) {
        val ticket = store.readForSession(id.toString()) { true }.ticket!!
        assertTrue(store.commitSnapshot(ticket, body) is SnapshotCommitResult.Committed)
    }

    private class Attempt(val session: Session?, val failure: SSConfFailure?, val events: List<String>, val maxObservers: Int)

    /**
     * One attempt composed as runSession composes it, sampling the lease observer count throughout.
     * Events: "native" (cleanup gate, with the observer count at that moment), "session", "tun".
     */
    private fun attempt(recovery: Boolean, fetch: suspend (String) -> ByteArray): Attempt = runBlocking(Dispatchers.Default) {
        val token = VpnLive.gate.next()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val maxObservers = AtomicInteger(SSConfNetworkLease.observerCount)
        val sampler = launch {
            while (true) { maxObservers.accumulateAndGet(SSConfNetworkLease.observerCount) { a, b -> maxOf(a, b) }; delay(2) }
        }
        val result = try {
            withTimeout(40_000) {
                val input = readSessionInput(store, id.toString(), token)
                val session = createCoreSession(
                    snapshot = { loadSessionSnapshot(store, input, token, recovery, fetch) },
                    ensureAttempt = { ensureSessionAttempt(token) },
                    acquireNative = { events += "native:${SSConfNetworkLease.observerCount}"; true },
                    canStartVpn = { true },
                    newSession = { throw AssertionError("ssconf must use the snapshot factory") },
                    newSessionWithSnapshot = { snapshot ->
                        events += "session"
                        Androidcore.newSessionWithSSConfSnapshot(input.bytes, snapshot, null, null, object : SocketProtector {
                            override fun protect(fd: Long): Boolean = throw AssertionError("Unstarted session opened a socket")
                        })
                    },
                )
                events += "tun"
                Attempt(session, null, events.toList(), 0)
            }
        } catch (failure: SSConfFailure) {
            Attempt(null, failure, events.toList(), 0)
        } finally {
            sampler.cancelAndJoin()
        }
        Attempt(result.session, result.failure, result.events, maxOf(maxObservers.get(), SSConfNetworkLease.observerCount))
    }

    /** Asserts bind-only: no VPN, VPN state or token change, and the lease released afterwards. */
    private fun bindOnly(block: () -> Unit) {
        assertEquals("No other lease may be open", 0, SSConfNetworkLease.observerCount)
        val state = VpnLive.state.value
        assertFalse("Fixture requires no active VPN", vpnActive())
        block()
        assertEquals(0, SSConfNetworkLease.observerCount)
        assertEquals(state, VpnLive.state.value)
        assertFalse(vpnActive())
    }

    private fun journalLeaks() = AppEvents.entries.value.any {
        it.message.contains("bimeron-connect-fetch-secret") || it.message.contains("ssconf://") || it.message.contains("YWVz")
    }

    @Test fun userConnectFetchesOnceThroughTheLeasedPhysicalNetworkBeforeTheTun() =
        SSConfConsentFixture.withConsent(context, true) { runBlocking {
            saveProfile(localSource)
            persist(valid)
            val before = AppEvents.entries.value
            LocalEndpoint(replacement).use { endpoint ->
                val observed = Observed()
                bindOnly {
                    val result = attempt(recovery = false) { source -> fetchSSConf(context, source, localRequest(endpoint, observed)) }
                    assertNull(result.failure)
                    result.session!!.stop()
                    assertEquals(1, endpoint.requests.get())
                    assertEquals(listOf(localSource), observed.sources)
                    assertEquals("The lease network is physical, not a VPN", false, observed.networkVpn)
                    assertFalse("No TUN exists during the fetch", observed.vpnActive)
                    assertTrue(observed.protected)
                    assertTrue("The fetch socket is bound to the leased network", observed.bound)
                    assertTrue("The fetch used a lease", result.maxObservers >= 1)
                    assertEquals("Fetch and lease release precede native work, session and TUN",
                        listOf("native:0", "session", "tun"), result.events)
                }
                assertArrayEquals(replacement, store.readForSession(id.toString()) { true }.snapshot!!.body)
                val fallback = context.getString(R.string.journal_ssconf_fallback, "")
                assertTrue("A successful fetch adds no fallback note",
                    AppEvents.entries.value.filter { it !in before }.none { it.message.startsWith(fallback.substringBefore("(")) })
                assertFalse(journalLeaks())
            }
        } }

    @Test fun recoveryStartUsesTheStoredSnapshotWithoutFetchOrLease() =
        SSConfConsentFixture.withConsent(context, true) { runBlocking {
            saveProfile(localSource)
            persist(valid)
            LocalEndpoint(replacement).use { endpoint ->
                val observed = Observed()
                bindOnly {
                    val result = attempt(recovery = true) { source -> fetchSSConf(context, source, localRequest(endpoint, observed)) }
                    assertNull(result.failure)
                    result.session!!.stop()
                    assertEquals(0, endpoint.requests.get())
                    assertTrue(observed.sources.isEmpty())
                    assertEquals("Recovery never acquires the ssconf network", 0, result.maxObservers)
                    assertEquals(listOf("native:0", "session", "tun"), result.events)
                }
                assertArrayEquals(valid, store.readForSession(id.toString()) { true }.snapshot!!.body)
            }
        } }

    @Test fun failedFetchWithStoredSnapshotFallsBackAndCreatesTheSession() =
        SSConfConsentFixture.withConsent(context, true) { runBlocking {
            saveProfile(refusedSource)
            persist(valid)
            val events = AppEvents.entries.value
            bindOnly {
                val result = attempt(recovery = false) { source -> fetchSSConf(context, source) }
                assertNull(result.failure)
                result.session!!.stop()
                assertEquals(listOf("native:0", "session", "tun"), result.events)
            }
            assertArrayEquals(valid, store.readForSession(id.toString()) { true }.snapshot!!.body)
            val notes = AppEvents.entries.value.filter { it !in events }.map { it.message }
            // FETCH_FAILED when a physical network was leased; NETWORK_UNAVAILABLE when none is available.
            assertTrue(notes.toString(), notes.any { note ->
                listOf(VpnErrorCode.SSCONF_FETCH_FAILED, VpnErrorCode.SSCONF_NETWORK_UNAVAILABLE).any {
                    note == context.getString(R.string.journal_ssconf_fallback, it.name)
                }
            })
            assertFalse(journalLeaks())
        } }

    @Test fun failedFetchWithoutStoredSnapshotFailsWithTheFetchCode() =
        SSConfConsentFixture.withConsent(context, true) { runBlocking {
            saveProfile(refusedSource)
            bindOnly {
                val result = attempt(recovery = false) { source -> fetchSSConf(context, source) }
                assertNull(result.session)
                val code = result.failure!!.code
                assertTrue(code.name, code in setOf(VpnErrorCode.SSCONF_FETCH_FAILED, VpnErrorCode.SSCONF_NETWORK_UNAVAILABLE))
                assertEquals(code, sessionFailureCode(result.failure))
                assertFalse(result.failure.toString().contains("bimeron-connect-fetch-secret"))
                assertTrue("No native work after a failed fetch", result.events.isEmpty())
            }
            assertNull(store.readForSession(id.toString()) { true }.snapshot)
        } }

    @Test fun rejectedFetchedBodyWithoutStoredSnapshotIsSnapshotInvalid() =
        SSConfConsentFixture.withConsent(context, true) { runBlocking {
            saveProfile(localSource)
            LocalEndpoint("not a shadowsocks body".toByteArray()).use { endpoint ->
                val observed = Observed()
                bindOnly {
                    val result = attempt(recovery = false) { source -> fetchSSConf(context, source, localRequest(endpoint, observed)) }
                    assertEquals(VpnErrorCode.SSCONF_SNAPSHOT_INVALID, result.failure?.code)
                    assertEquals(1, endpoint.requests.get())
                    assertTrue(result.events.isEmpty())
                }
                assertNull(store.readForSession(id.toString()) { true }.snapshot)
            }
        } }

    @Test fun localEndpointBodiesAreAcceptedByCore() {
        Androidcore.newSSConfSnapshot(localSource, valid)
        Androidcore.newSSConfSnapshot(localSource, replacement)
        try {
            Androidcore.newSSConfSnapshot(localSource, "not a shadowsocks body".toByteArray())
            fail("Core accepted an invalid body")
        } catch (_: Exception) {}
    }
}
