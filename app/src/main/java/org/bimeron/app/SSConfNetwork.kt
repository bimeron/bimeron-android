package org.bimeron.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.IBinder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong

internal class SSConfNetworkUnavailableException(diagnostics: String? = null) : Exception(
    "SSConf network unavailable" + if (diagnostics != null) " [$diagnostics]" else "",
)

internal class SSConfNetworkLease private constructor(
    private val service: BimeronVpnService,
    private val physical: Network,
    val dns: String,
    private val state: SSConfLeaseState,
) : AutoCloseable {
    companion object {
        internal const val BIND_ACTION = "org.bimeron.app.SSCONF_NETWORK"
        private val generations = AtomicLong()
        private val observers = java.util.concurrent.atomic.AtomicInteger()
        internal val observerCount: Int get() = observers.get()
        internal suspend fun capture(
            service: BimeronVpnService,
            beforeSnapshot: suspend () -> Unit = {},
        ): SSConfNetworkLease {
            val manager = service.getSystemService(ConnectivityManager::class.java)
            val snapshot = SSConfNetworkSnapshot<Network>()
            val watcher = object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) = snapshot.lost(network)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    snapshot.capabilities(network, if (
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    ) (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 2 else 0) +
                        (if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 1 else 0) else null)
                override fun onLinkPropertiesChanged(network: Network, value: LinkProperties) =
                    snapshot.properties(network, value.dnsServers.filter { it.address.size == 4 }
                        .take(4).joinToString(",") { it.hostAddress.orEmpty() })
            }
            // Initial callback delivery establishes the snapshot; later changes invalidate it.
            try {
                manager.registerNetworkCallback(NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), watcher)
            } catch (_: Exception) { throw SSConfNetworkUnavailableException() }
            observers.incrementAndGet()
            val closed = java.util.concurrent.atomic.AtomicBoolean()
            val cleanup = {
                if (closed.compareAndSet(false, true)) {
                    manager.unregisterNetworkCallback(watcher)
                    observers.decrementAndGet()
                }
            }
            var capturedLease: SSConfNetworkLease? = null
            try {
                beforeSnapshot()
                val captured = snapshot.capture()
                val result = SSConfNetworkLease(service, captured.network, captured.dns, captured.state)
                capturedLease = result
                result.cleanup = cleanup
                val registered = service.registerResolver(result)
                result.registration = if (registered) SSConfDiagnostic.REGISTERED else SSConfDiagnostic.REGISTRATION_REJECTED
                if (!registered || !result.isValid()) {
                    result.close()
                    throw SSConfNetworkUnavailableException()
                }
                return result
            } catch (_: TimeoutCancellationException) {
                runCatching(cleanup)
                throw SSConfNetworkUnavailableException()
            } catch (cancelled: CancellationException) {
                runCatching(cleanup)
                throw cancelled
            } catch (_: Exception) {
                runCatching(cleanup)
                throw SSConfNetworkUnavailableException(capturedLease?.diagnostics())
            }
        }
    }

    private var cleanup: (() -> Unit)? = null
    val generation: Long = generations.incrementAndGet()
    val networkID: String = physical.toString()
    @Volatile private var registration = SSConfDiagnostic.NONE
    @Volatile private var serviceObservation = SSConfDiagnostic.NONE
    @Volatile private var protectStep = SSConfDiagnostic.NONE
    internal fun diagnostics(): String =
        "${state.diagnostics()} registration=$registration service=$serviceObservation protectStep=$protectStep"
    private fun resolverAvailable(): Boolean {
        val status = service.resolverStatus()
        serviceObservation = status
        return status == SSConfDiagnostic.AVAILABLE
    }
    fun isValid(): Boolean = state.isValid() && resolverAvailable()
    fun onInvalidated(cancel: () -> Unit): AutoCloseable = state.onInvalidated(cancel)
    internal fun invalidate(reason: SSConfDiagnostic) = state.invalidate(reason)
    fun protect(fd: Long): Boolean {
        protectStep = SSConfDiagnostic.NONE
        return state.fd(fd) {
            protectStep = SSConfDiagnostic.PROTECT_BEFORE_SERVICE
            if (!resolverAvailable()) false else {
                protectStep = SSConfDiagnostic.PROTECT_PLATFORM
                if (!service.protect(it)) false else {
                    protectStep = SSConfDiagnostic.PROTECT_AFTER_SERVICE
                    resolverAvailable()
                }
            }
        }
    }
    fun bind(fd: Long): Boolean = state.fd(fd) {
        if (!resolverAvailable()) false else {
            ParcelFileDescriptor.fromFd(it).use { duplicate -> physical.bindSocket(duplicate.fileDescriptor) }
            resolverAvailable()
        }
    }
    override fun close() {
        state.close()
        service.unregisterResolver(this)
        cleanup?.let { cleanup = null; runCatching(it) }
    }
}

/** Generation correlates this scope only; the host owns network revision and publication guards. */
internal suspend fun <T> withSSConfNetwork(context: Context, block: (SSConfNetworkLease) -> T): T =
    withSSConfNetworkCapture(context, { SSConfNetworkLease.capture(it) }, block)

internal suspend fun <T> withSSConfNetworkCapture(
    context: Context,
    capture: suspend (BimeronVpnService) -> SSConfNetworkLease,
    block: (SSConfNetworkLease) -> T,
): T = coroutineScope {
    val app = context.applicationContext
    if (!runCatching { VpnService.prepare(app) == null }.getOrDefault(false))
        throw SSConfNetworkUnavailableException()
    val connected = CompletableDeferred<BimeronVpnService>()
    val lifetimeLock = Any()
    var lease: SSConfNetworkLease? = null
    var lost = false
    var lostReason = SSConfDiagnostic.NONE
    fun invalidate(reason: SSConfDiagnostic) {
        val current = synchronized(lifetimeLock) {
            if (!lost) lostReason = reason
            lost = true
            lease
        }
        current?.invalidate(reason)
    }
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = runCatching { (binder as BimeronVpnService.ResolverBinder).service() }.getOrNull()
            if (service == null) connected.completeExceptionally(SSConfNetworkUnavailableException())
            else connected.complete(service)
        }
        override fun onServiceDisconnected(name: ComponentName) { invalidate(SSConfDiagnostic.SERVICE_DISCONNECTED) }
        override fun onBindingDied(name: ComponentName) { invalidate(SSConfDiagnostic.BINDING_DIED); connected.completeExceptionally(SSConfNetworkUnavailableException()) }
        override fun onNullBinding(name: ComponentName) { connected.completeExceptionally(SSConfNetworkUnavailableException()) }
    }
    var attempted = false
    try {
        val bound = try {
            attempted = true
            app.bindService(Intent(app, BimeronVpnService::class.java).setAction(SSConfNetworkLease.BIND_ACTION),
                connection, Context.BIND_AUTO_CREATE)
        } catch (_: Exception) { throw SSConfNetworkUnavailableException() }
        if (!bound) throw SSConfNetworkUnavailableException()
        val service = try { withTimeout(5_000) { connected.await() } }
        catch (_: TimeoutCancellationException) { throw SSConfNetworkUnavailableException() }
        val captured = withContext(Dispatchers.IO) {
            capture(service).also { acquired ->
                val invalid = synchronized(lifetimeLock) { lease = acquired; if (lost) lostReason else null }
                if (invalid != null) acquired.invalidate(invalid)
            }
        }
        // A suspended child receives cancellation immediately, even while the IO worker blocks.
        val cancellationSignal = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { captured.invalidate(SSConfDiagnostic.CANCELLATION) }
        }
        try {
            withContext(Dispatchers.IO) {
                ensureActive()
                if (!captured.isValid()) throw SSConfNetworkUnavailableException(captured.diagnostics())
                val result = block(captured)
                ensureActive()
                if (!captured.isValid()) throw SSConfNetworkUnavailableException(captured.diagnostics())
                result
            }
        } finally {
            // Silent normal release must precede cancellation of our signal child.
            captured.close()
            cancellationSignal.cancel()
        }
    } finally {
        synchronized(lifetimeLock) { lease }?.close()
        if (attempted) runCatching { app.unbindService(connection) }
    }
}
