package org.bimeron.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
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
        internal const val RECOVERY = "vpn_recovery"
        internal const val ACTIVE_ID = "active_id"
        private const val VPN_NOTIFICATION_ID = 1
        private const val RECOVERY_CHANNEL = "vpn_recovery"
        internal const val RECOVERY_NOTIFICATION_ID = 2

        /**
         * Notification tap target. SINGLE_TOP + CLEAR_TOP bring the running MainActivity forward (onNewIntent)
         * instead of stacking a second instance with its own auth dialog; nothing reads extras from it.
         */
        internal fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private sealed interface Command {
        /** [recovery] marks any start other than the explicit user [START]; it is classified before it may start (§6). */
        data class Start(val id: String, val startId: Int, val force: Boolean, val recovery: Boolean) : Command
        data class Stop(val startId: Int) : Command
        /** An always-on start from Android; its profile is resolved off [onStartCommand] (see [alwaysOnTarget]). */
        data class AlwaysOn(val startId: Int) : Command
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
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel("vpn", getString(R.string.vpn_notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        notifications.createNotificationChannel(
            NotificationChannel(RECOVERY_CHANNEL, getString(R.string.vpn_recovery_notification_channel), NotificationManager.IMPORTANCE_DEFAULT)
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
        if (intent?.action == SERVICE_INTERFACE) {
            // Android starts the always-on app's service with this action on boot, user unlock, package update
            // and when always-on is turned on. The target needs a DataStore read, so it is resolved in order by
            // the consumer and then classified like any other recovery start.
            ensureForeground()
            commands.trySend(Command.AlwaysOn(startId))
            return START_STICKY
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
        // Fail-safe: anything but the explicit user START is a recovery and is classified before it may start.
        // Here that is an action-less start (a sticky restart's null intent); always-on starts take the same path
        // through Command.AlwaysOn. A skipped recovery clears the recovery id and stops the service, so it is not
        // restarted; START_STICKY is kept for starts that proceed.
        val recovery = intent?.action != START
        commands.trySend(Command.Start(id, startId, intent?.getBooleanExtra(FORCE_RESTART, false) == true, recovery))
        return START_STICKY
    }

    private fun ensureForeground() {
        if (!foreground) {
            startForeground(VPN_NOTIFICATION_ID, notification())
            foreground = true
        }
    }

    private fun notification(): Notification {
        val open = openAppIntent(this)
        return NotificationCompat.Builder(this, "vpn")
            .setSmallIcon(R.drawable.ic_stat_bimeron)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.vpn_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    /** Fixed text only: no profile name, id or server. Tapping opens the app; nothing starts by itself. */
    private fun postRecoveryNotification() {
        val open = openAppIntent(this)
        val notification = NotificationCompat.Builder(this, RECOVERY_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bimeron)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.vpn_recovery_notification_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // Without notification permission the post is dropped; the published state still carries the code.
        runCatching { getSystemService(NotificationManager::class.java).notify(RECOVERY_NOTIFICATION_ID, notification) }
    }

    private fun cancelRecoveryNotification() {
        runCatching { getSystemService(NotificationManager::class.java).cancel(RECOVERY_NOTIFICATION_ID) }
    }

    private val recoveryPort = object : RecoveryStartPort {
        /** Store-backed read for classification; any failure is an absent input, so the start is skipped. */
        override suspend fun read(id: String, token: Long): SessionInput? = try {
            val store = withContext(Dispatchers.IO) { ProfileStore.get(applicationContext) }
            readSessionInput(store, id, token)
        } catch (e: CancellationException) {
            // A real cancellation propagates; a superseded token is checked by planStart.
            currentCoroutineContext().ensureActive()
            null
        } catch (_: Exception) {
            null
        }
        override suspend fun clearRecovery(): Boolean = persistRecovery(null)
        override val destroyed: Boolean get() = this@BimeronVpnService.destroyed
        override fun postNotification() { postRecoveryNotification() }
        override fun cancelNotification() { cancelRecoveryNotification() }
        override fun recordSkip(message: Int) { AppEvents.add(message, LogLevel.WARNING) }
        override fun publishSkipped(token: Long) { VpnLive.publish(recoverySkippedState { getString(it) }, token) }
    }

    /**
     * An always-on start becomes a recovery [Command.Start] for [alwaysOnTarget]. A running session is kept as is
     * (Android also sends this when always-on is turned on while connected); without a target the start ends quietly.
     */
    private suspend fun resolveAlwaysOn(command: Command.AlwaysOn): Command {
        if (runJob?.isActive == true) return Command.Invalid(command.startId, reportError = false)
        val recoveryId = withContext(Dispatchers.IO) {
            getSharedPreferences(RECOVERY, MODE_PRIVATE).getString(ACTIVE_ID, null)
        }
        val selectedId = try {
            readSelectedProfileId(applicationContext)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val id = alwaysOnTarget(recoveryId, selectedId)
        if (id == null) {
            AppEvents.add(R.string.journal_always_on_no_profile, LogLevel.WARNING)
            return Command.Invalid(command.startId, reportError = false)
        }
        return Command.Start(id, command.startId, force = false, recovery = true)
    }

    private suspend fun consumeCommands() {
        var acceptedStartId = 0
        for (received in commands) {
            currentCoroutineContext().ensureActive()
            if (destroyed) return
            val command = if (received is Command.AlwaysOn) resolveAlwaysOn(received) else received
            currentCoroutineContext().ensureActive()
            if (destroyed) return
            when (command) {
                is Command.Invalid -> {
                    acceptedStartId = command.startId
                    if (runJob == null) {
                        if (command.reportError) VpnLive.publish(failureState(VpnErrorCode.INVALID_PROFILE), VpnLive.nextAttempt())
                        leaveForeground(command.startId)
                    }
                }
                is Command.Start -> {
                    acceptedStartId = command.startId
                    noteStartCommand(command.recovery, recoveryPort)
                    val current = VpnLive.state.value
                    if (!command.force && runJob?.isActive == true && command.id == (current.activeProfileId ?: current.attemptProfileId)) continue
                    // A new token cannot clear the old offer, so it is cleared with the gate advance.
                    val token = VpnLive.nextAttempt()
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
                    // A recovery start is read once and classified off Main. A possibly interactive profile is
                    // skipped (recovery never shows UI, §6, §11.14); a superseded read leaves silently.
                    val classified = when (val plan = planStart(command.recovery, command.id, token, recoveryPort)) {
                        StartPlan.Destroyed -> return
                        StartPlan.Skipped, StartPlan.Superseded -> {
                            leaveForeground(command.startId)
                            continue
                        }
                        is StartPlan.Proceed -> plan.input
                    }
                    if (!persistRecovery(command.id)) {
                        VpnLive.publish(failureState(VpnErrorCode.RECOVERY_STATE_FAILED, command.id), token)
                        leaveForeground(command.startId)
                        continue
                    }
                    currentCoroutineContext().ensureActive()
                    if (destroyed) return
                    VpnLive.publish(VpnState("connecting", attemptProfileId = command.id), token)
                    runJob = scope.launch { runSession(command.id, token, classified) }
                }
                is Command.Stop -> {
                    val token = VpnLive.nextAttempt()
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
                is Command.AlwaysOn -> error("resolved above")
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
    internal class SessionFailure(val code: VpnErrorCode) : Exception()

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

    /** [classified] is the recovery start's classified read; a user start passes null and reads here. */
    private suspend fun runSession(id: String, token: Long, classified: SessionInput?) {
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
            // Single store-backed read per start; the input owns its bytes, revision, digest and ticket.
            // A recovery start reuses the read it classified and never reads again.
            val sessionInput = try {
                sessionInputFor(classified, id, token) {
                    readSessionInput(withContext(Dispatchers.IO) { ProfileStore.get(applicationContext) }, id, token)
                }
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
            val session = createCoreSession(
                snapshot = sessionInput.ssconf?.let {
                    suspend { sessionSnapshot(sessionInput, token, classified) }
                },
                ensureAttempt = { ensureSessionAttempt(token) },
                acquireNative = { NativeCleanupGate.acquire().also { ownsNative = it } },
                canStartVpn = { CoreCapabilities.read().canStartVpn },
                newSession = { Androidcore.newSession(sessionInput.bytes, protector) },
                newSessionWithSnapshot = { snapshot ->
                    Androidcore.newSessionWithSSConfSnapshot(sessionInput.bytes, snapshot, null, null, protector)
                },
            )
            current = session
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
            val auth = AuthAttempt(token, SessionAuthPort(session),
                isOpen = { !closing.get() && VpnLive.gate.accepts(token) },
                onCancelled = {
                    stopAfterUserCancel(id, token, getString(VpnErrorCode.AUTH_CANCELLED.messageRes), ownerJob)
                })
            VpnAuthEndpoint.register(token, auth)
            while (currentCoroutineContext().isActive) {
                auth.poll()
                val state = publishStatus(session, id, token, auth)
                if (state == "connected") break
                if (state == "failed" || state == "disconnected") throw SessionFailure(VpnErrorCode.CORE_CONNECT_FAILED)
                delay(statusTickMillis(connected = false, token))
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
                auth.poll()
                val state = publishStatus(session, id, token, auth)
                if (state == "failed" || state == "disconnected") throw SessionFailure(VpnErrorCode.CORE_CONNECT_FAILED)
                delay(statusTickMillis(connected = true, token))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            publishFailure(sessionFailureCode(e), id, token)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                synchronized(networkLock) { closing.set(true); physical = null }
                // Terminal auth cleanup precedes native stop and Command.Finished.
                VpnAuthEndpoint.unregister(token)
                VpnLive.publishAuth(token, null)
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
     * A user start performs one bounded bind-only fetch and commits it, falling back to the persisted
     * snapshot only if the fetch fails. A recovery start ([classified] read, see [isRecoveryStart]) uses the
     * persisted snapshot without network access; if Core rejects it, the recovery ends like a skipped one:
     * "Tap to reconnect" notification, journal entry, RECOVERY_NEEDS_USER (the recovery id is cleared on finish).
     */
    private suspend fun sessionSnapshot(input: SessionInput, token: Long, classified: SessionInput?): SSConfSnapshot {
        val store = withContext(Dispatchers.IO) { ProfileStore.get(applicationContext) }
        return try {
            loadSessionSnapshot(store, input, token, isRecoveryStart(classified)) { source -> fetchSSConf(applicationContext, source) }
        } catch (failure: SSConfFailure) {
            if (failure.code == VpnErrorCode.RECOVERY_NEEDS_USER) {
                postRecoveryNotification()
                AppEvents.add(R.string.journal_recovery_ssconf_unavailable, LogLevel.WARNING)
            }
            throw failure
        }
    }

    private fun updateProtection() {
        VpnLive.protection.value = if (android.os.Build.VERSION.SDK_INT >= 29) isAlwaysOn to isLockdownEnabled else null
    }

    private fun publishStatus(session: Session, id: String, token: Long, auth: AuthAttempt): String {
        if (!VpnLive.gate.accepts(token)) return "disconnected"
        if (VpnLive.state.value.state == "failed" && VpnLive.state.value.attemptProfileId == id) return "failed"
        updateProtection()
        val json = JSONObject(session.status())
        val status = json.getJSONObject("status")
        val state = normalizeCoreState(status.optString("State"))
        val stats = json.optJSONObject("stats")?.takeUnless { it.optBoolean("unavailable") }
        val lastError = status.optString("LastError").takeIf { it.isNotBlank() }
        val code = coreFailureCode(state, lastError, auth.history)
        val safeError = code?.let { getString(it.messageRes) }
        val published = VpnLive.publishCoreStatus(token, id, state,
            stats?.optLong("bytes_down") ?: 0, stats?.optLong("bytes_up") ?: 0,
            SystemClock.elapsedRealtime(), safeError, code)
        if (published == "connected") auth.connected()
        return published
    }

    private class SessionAuthPort(private val session: Session) : CoreSessionPort {
        override fun pendingAuth(): String? = session.pendingAuth()
        override fun replyAuth(sessionId: String, requestId: String, answers: String) = session.replyAuth(sessionId, requestId, answers)
        override fun cancelAuth(sessionId: String, requestId: String) = session.cancelAuth(sessionId, requestId)
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
        VpnLive.clearAuth()
        if (started) {
            VpnLive.serviceDestroyed(getString(VpnErrorCode.STOP_FAILED.messageRes))
            VpnLive.protection.value = null
        }
        super.onDestroy()
    }
}
