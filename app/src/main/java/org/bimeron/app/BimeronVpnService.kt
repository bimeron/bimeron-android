package org.bimeron.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bimeron.core.androidcore.Androidcore
import org.bimeron.core.androidcore.SSConfSnapshot
import org.bimeron.core.androidcore.Session
import org.bimeron.core.androidcore.SocketProtector
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class BimeronVpnService : VpnService() {
    companion object {
        const val START = "org.bimeron.app.START"
        const val STOP = "org.bimeron.app.STOP"
        const val PROFILE = "profile"
        const val FORCE_RESTART = "force_restart"
        private const val RECOVERY = "vpn_recovery"
        private const val ACTIVE_ID = "active_id"
    }

    private sealed interface Command {
        data class Start(val id: String, val startId: Int, val force: Boolean) : Command
        data class Stop(val startId: Int) : Command
        data class Invalid(val startId: Int, val reportError: Boolean) : Command
        data class Finished(val token: Long) : Command
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private var latestStartId = 0
    @Volatile private var destroyed = false
    @Volatile private var started = false
    private val resolverLeases = mutableSetOf<SSConfNetworkLease>()
    private var resolverRevoked = false

    internal inner class ResolverBinder : android.os.Binder() {
        fun service(): BimeronVpnService {
            if (getCallingUid() != android.os.Process.myUid()) throw SecurityException("Unavailable")
            return this@BimeronVpnService
        }
    }

    override fun onBind(intent: Intent?): android.os.IBinder? =
        if (intent?.action == SSConfNetworkLease.BIND_ACTION &&
            intent.component == android.content.ComponentName(this, BimeronVpnService::class.java)) ResolverBinder()
        else super.onBind(intent)

    internal val resolverDestroyed: Boolean get() = destroyed
    internal val resolverCount: Int get() = synchronized(resolverLeases) { resolverLeases.size }

    internal fun registerResolver(lease: SSConfNetworkLease): Boolean = synchronized(resolverLeases) {
        if (destroyed || resolverRevoked) false else { resolverLeases.add(lease); true }
    }

    internal fun unregisterResolver(lease: SSConfNetworkLease) {
        synchronized(resolverLeases) { resolverLeases.remove(lease) }
    }

    internal fun resolverAvailable(): Boolean = resolverStatus() == SSConfDiagnostic.AVAILABLE

    internal fun resolverStatus(): SSConfDiagnostic = synchronized(resolverLeases) {
        when {
            destroyed -> SSConfDiagnostic.SERVICE_DESTROYED
            resolverRevoked -> SSConfDiagnostic.SERVICE_REVOKED
            else -> runCatching {
                if (prepare(this) == null) SSConfDiagnostic.AVAILABLE else SSConfDiagnostic.CONSENT_REQUIRED
            }.getOrDefault(SSConfDiagnostic.PREPARE_EXCEPTION)
        }
    }

    private fun invalidateResolvers(reason: SSConfDiagnostic) {
        val leases = synchronized(resolverLeases) {
            resolverRevoked = true
            resolverLeases.toList()
        }
        leases.forEach { it.invalidate(reason) }
    }
    private var runJob: Job? = null
    private val cleanupFailed: Boolean get() = NativeCleanupGate.blocked
    @Volatile private var foreground = false

    override fun onCreate() {
        super.onCreate()
        JournalRuntime.start(applicationContext)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("vpn", getString(R.string.vpn_notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        scope.launch(Dispatchers.Main.immediate) { consumeCommands() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (destroyed) return START_NOT_STICKY
        started = true
        updateProtection()
        latestStartId = startId
        if (intent?.action == STOP) {
            commands.trySend(Command.Stop(startId))
            return START_NOT_STICKY
        }
        val id = when (intent?.action) {
            START -> intent.getStringExtra(PROFILE)
            null -> getSharedPreferences(RECOVERY, MODE_PRIVATE).getString(ACTIVE_ID, null)
            else -> null
        }
        if (id == null || !runCatching { java.util.UUID.fromString(id) }.isSuccess) {
            commands.trySend(Command.Invalid(startId, intent?.action == START))
            return START_NOT_STICKY
        }
        ensureForeground()
        commands.trySend(Command.Start(id, startId, intent?.getBooleanExtra(FORCE_RESTART, false) == true))
        return START_STICKY
    }

    private fun ensureForeground() {
        if (!foreground) {
            startForeground(1, notification())
            foreground = true
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "vpn")
            .setSmallIcon(R.drawable.ic_stat_bimeron)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.vpn_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private suspend fun consumeCommands() {
        var acceptedStartId = 0
        for (command in commands) {
            currentCoroutineContext().ensureActive()
            if (destroyed) return
            when (command) {
                is Command.Invalid -> {
                    acceptedStartId = command.startId
                    if (runJob == null) {
                        if (command.reportError) VpnLive.publish(failureState(VpnErrorCode.INVALID_PROFILE), VpnLive.gate.next())
                        leaveForeground(command.startId)
                    }
                }
                is Command.Start -> {
                    acceptedStartId = command.startId
                    val current = VpnLive.state.value
                    if (!command.force && runJob?.isActive == true && command.id == (current.activeProfileId ?: current.attemptProfileId)) continue
                    val token = VpnLive.gate.next()
                    runJob?.cancelAndJoin()
                    currentCoroutineContext().ensureActive()
                    if (destroyed) return
                    runJob = null
                    if (cleanupFailed) {
                        VpnLive.publish(failureState(VpnErrorCode.CLEANUP_BLOCKED, command.id), token)
                        leaveForeground(command.startId)
                        continue
                    }
                    ensureForeground()
                    if (!persistRecovery(command.id)) {
                        VpnLive.publish(failureState(VpnErrorCode.RECOVERY_STATE_FAILED, command.id), token)
                        leaveForeground(command.startId)
                        continue
                    }
                    currentCoroutineContext().ensureActive()
                    if (destroyed) return
                    VpnLive.publish(VpnState("connecting", attemptProfileId = command.id), token)
                    runJob = scope.launch { runSession(command.id, token) }
                }
                is Command.Stop -> {
                    val token = VpnLive.gate.next()
                    val old = VpnLive.state.value
                    if (runJob != null) VpnLive.publish(old.copy(state = "stopping", message = null, code = null), token)
                    runJob?.cancelAndJoin()
                    currentCoroutineContext().ensureActive()
                    if (destroyed) return
                    runJob = null
                    val recoveryCleared = persistRecovery(null)
                    currentCoroutineContext().ensureActive()
                    if (destroyed) return
                    val failure = teardownFailureCode(cleanupFailed, recoveryCleared, old.code)
                    VpnLive.publish(failure?.let { failureState(it, old.attemptProfileId) } ?: VpnState(), token)
                    leaveForeground(command.startId)
                }
                is Command.Finished -> {
                    if (!VpnLive.gate.accepts(command.token)) continue
                    runJob = null
                    val recoveryCleared = persistRecovery(null)
                    currentCoroutineContext().ensureActive()
                    if (destroyed) return
                    val current = VpnLive.state.value
                    val failure = teardownFailureCode(cleanupFailed, recoveryCleared, current.code)
                    if (failure != null) {
                        VpnLive.publish(failureState(failure, current.attemptProfileId), command.token)
                    } else if (VpnLive.state.value.state != "failed") VpnLive.publish(VpnState(), command.token)
                    leaveForeground(acceptedStartId)
                }
            }
        }
    }

    private fun failureState(code: VpnErrorCode, id: String? = null) = failureState(code, { getString(it) }, id)

    private fun publishFailure(code: VpnErrorCode, id: String, token: Long) =
        VpnLive.publishFailure(code, getString(code.messageRes), id, token)

    /** A session failure with a fixed code; carries no exception or Core text. */
    private class SessionFailure(val code: VpnErrorCode) : Exception()

    private suspend fun persistRecovery(id: String?): Boolean = withContext(Dispatchers.IO) {
        val saved = getSharedPreferences(RECOVERY, MODE_PRIVATE).edit().putString(ACTIVE_ID, id).commit()
        if (!saved) AppEvents.add(getString(R.string.journal_recovery_save_failed), LogLevel.ERROR)
        saved
    }

    private fun leaveForeground(startId: Int) {
        if (startId != 0 && startId != latestStartId) return
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        if (startId == 0) stopSelf() else stopSelfResult(startId)
    }

    private fun chooseNetwork(): Network? = connectivity.allNetworks
        .filter { network ->
            val caps = connectivity.getNetworkCapabilities(network)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        .maxByOrNull { network ->
            val caps = connectivity.getNetworkCapabilities(network)
            (if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) 2 else 0) +
                (if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) 1 else 0)
        }

    private suspend fun runSession(id: String, token: Long) {
        var ownsNative = false
        var current: Session? = null
        var tun: ParcelFileDescriptor? = null
        var callback: ConnectivityManager.NetworkCallback? = null
        var input: Job? = null
        var output: Job? = null
        val closing = AtomicBoolean(false)
        val networkLock = Any()
        var physical: Network? = null
        fun updateNetwork(current: Session, token: Long) = synchronized(networkLock) {
            if (closing.get() || !VpnLive.gate.accepts(token)) return@synchronized
            val network = chooseNetwork()
            physical = network
            val dns = network?.let { connectivity.getLinkProperties(it)?.dnsServers
                ?.filter { address -> address.address.size == 4 }
                ?.take(4)?.joinToString(",") { address -> address.hostAddress ?: "" } }.orEmpty()
            try { current.setNetwork(network?.toString().orEmpty(), dns) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { publishFailure(VpnErrorCode.NETWORK_ERROR, id, token) }
        }
        val packetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ownerJob = currentCoroutineContext()[Job]
        try {
            // Single store-backed read; the input owns its bytes, revision, digest and ticket.
            val sessionInput = try {
                val store = withContext(Dispatchers.IO) { ProfileStore.get(applicationContext) }
                readSessionInput(store, id, token)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                throw SessionFailure(VpnErrorCode.INVALID_PROFILE)
            }
            val protector = object : SocketProtector {
                override fun protect(fd: Long): Boolean {
                    if (fd < 0 || fd > Int.MAX_VALUE) return false
                    return try {
                        if (!this@BimeronVpnService.protect(fd.toInt())) return false
                        val network = synchronized(networkLock) {
                            if (closing.get()) null else physical
                        } ?: return false
                        ParcelFileDescriptor.fromFd(fd.toInt()).use { duplicate -> network.bindSocket(duplicate.fileDescriptor) }
                        true
                    } catch (_: Exception) { false }
                }
            }
            // SSConf: obtain a Core-validated snapshot before any native session, capability or TUN work.
            val snapshot = sessionInput.ssconf?.let { binding -> sessionSnapshot(sessionInput, binding.source, token) }
            ensureSessionAttempt(token)
            if (!NativeCleanupGate.acquire()) throw SessionFailure(VpnErrorCode.CLEANUP_BLOCKED)
            ownsNative = true
            if (!CoreCapabilities.read().canStartVpn) throw SessionFailure(VpnErrorCode.CORE_INCOMPATIBLE)
            ensureSessionAttempt(token)
            current = if (snapshot != null) {
                Androidcore.newSessionWithSSConfSnapshot(sessionInput.bytes, snapshot, null, null, protector)
            } else {
                Androidcore.newSession(sessionInput.bytes, protector)
            }
            val session = current
            val watcher = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = updateNetwork(session, token)
                override fun onLost(network: Network) = updateNetwork(session, token)
                override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) = updateNetwork(session, token)
            }
            callback = watcher
            connectivity.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), watcher)
            updateNetwork(session, token)
            if (synchronized(networkLock) { physical == null }) throw SessionFailure(VpnErrorCode.NO_NETWORK)
            tun = Builder().setSession(getString(R.string.app_name)).setMtu(session.mtu().toInt())
                .addAddress("198.18.0.1", 24).addRoute("0.0.0.0", 0)
                .addAddress("fdfe:524c::1", 128).addRoute("::", 0)
                .addDnsServer("198.18.0.2").establish() ?: throw SessionFailure(VpnErrorCode.TUN_FAILED)
            session.start()
            while (currentCoroutineContext().isActive) {
                val state = publishStatus(session, id, token)
                if (state == "connected") break
                if (state == "failed" || state == "disconnected") throw SessionFailure(VpnErrorCode.CORE_CONNECT_FAILED)
                delay(200)
            }
            currentCoroutineContext().ensureActive()
            val descriptor = tun
            input = packetScope.launch {
                try {
                    val stream = FileInputStream(descriptor.fileDescriptor)
                    val buffer = ByteArray(65535)
                    while (isActive) {
                        val count = stream.read(buffer)
                        if (count < 0) throw java.io.EOFException("TUN closed")
                        if (count > 0) session.writePacket(buffer.copyOf(count))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (!closing.get()) {
                        publishFailure(VpnErrorCode.PACKET_IO, id, token)
                        ownerJob?.cancel()
                    }
                }
            }
            output = packetScope.launch {
                try {
                    val stream = FileOutputStream(descriptor.fileDescriptor)
                    while (isActive) { val packet = session.readPacket(500); if (packet != null) stream.write(packet) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (!closing.get()) {
                        publishFailure(VpnErrorCode.PACKET_IO, id, token)
                        ownerJob?.cancel()
                    }
                }
            }
            while (currentCoroutineContext().isActive) {
                val state = publishStatus(session, id, token)
                if (state == "failed" || state == "disconnected") throw SessionFailure(VpnErrorCode.CORE_CONNECT_FAILED)
                delay(500)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionFailure) {
            publishFailure(e.code, id, token)
        } catch (e: SSConfFailure) {
            publishFailure(e.code, id, token)
        } catch (_: Exception) {
            publishFailure(VpnErrorCode.START_FAILED, id, token)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                synchronized(networkLock) { closing.set(true); physical = null }
                callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
                runCatching { tun?.close() }
                packetScope.cancel()
                val cleaned = finishPacketWorkers(listOfNotNull(input, output)) { current?.stop() }
                if (ownsNative) NativeCleanupGate.completed(cleaned)
                commands.trySend(Command.Finished(token))
            }
        }
    }

    /**
     * Reuses the persisted snapshot without network access, or performs one bounded fetch and commits it.
     * Never refetches a valid snapshot. Runs on IO; no store guard or attempt monitor is held across
     * JNI or the network.
     */
    private suspend fun sessionSnapshot(input: SessionInput, source: String, token: Long): SSConfSnapshot {
        val ticket = requireNotNull(input.ticket)
        val store = withContext(Dispatchers.IO) { ProfileStore.get(applicationContext) }
        return resolveSessionSnapshot(
            ticket = ticket,
            stored = input.snapshot,
            ensureAttempt = { ensureSessionAttempt(token) },
            validate = { body -> withContext(Dispatchers.IO) { Androidcore.newSSConfSnapshot(source, body) } },
            fetch = { withContext(Dispatchers.IO) { fetchSSConf(applicationContext, source) } },
            discard = store::discardSnapshot,
            commit = store::commitSnapshot,
            warn = { code -> AppEvents.add(code, LogLevel.WARNING) },
        )
    }

    private fun updateProtection() {
        VpnLive.protection.value = if (android.os.Build.VERSION.SDK_INT >= 29) isAlwaysOn to isLockdownEnabled else null
    }

    private fun publishStatus(session: Session, id: String, token: Long): String {
        if (!VpnLive.gate.accepts(token)) return "disconnected"
        if (VpnLive.state.value.state == "failed" && VpnLive.state.value.attemptProfileId == id) return "failed"
        updateProtection()
        val json = JSONObject(session.status())
        val status = json.getJSONObject("status")
        val state = normalizeCoreState(status.optString("State"))
        val stats = json.optJSONObject("stats")?.takeUnless { it.optBoolean("unavailable") }
        val lastError = status.optString("LastError").takeIf { it.isNotBlank() }
        val code = coreFailureCode(state, lastError)
        val safeError = code?.let { getString(it.messageRes) }
        return VpnLive.publishCoreStatus(token, id, state,
            stats?.optLong("bytes_down") ?: 0, stats?.optLong("bytes_up") ?: 0,
            SystemClock.elapsedRealtime(), safeError, code)
    }

    override fun onRevoke() {
        invalidateResolvers(SSConfDiagnostic.SERVICE_REVOKED)
        if (started) commands.trySend(Command.Stop(0))
        super.onRevoke()
    }

    override fun onDestroy() {
        destroyed = true
        invalidateResolvers(SSConfDiagnostic.SERVICE_DESTROYED)
        scope.cancel()
        if (started) {
            VpnLive.serviceDestroyed(getString(VpnErrorCode.STOP_FAILED.messageRes))
            VpnLive.protection.value = null
        }
        super.onDestroy()
    }
}
