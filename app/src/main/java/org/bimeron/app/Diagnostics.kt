package org.bimeron.app

import android.app.Application
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class DiagnosticSection(val title: String, val rows: List<Pair<String, String>>)

internal object DiagnosticReport {
    const val MAX_BYTES = 1024 * 1024
    private val entryTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private fun line(value: String) = value.replace(Regex("[\\r\\n\\t]+"), " ").trim()

    fun entryLine(entry: LogEntry, zone: ZoneId): String {
        val time = if (entry.epochMillis > 0) Instant.ofEpochMilli(entry.epochMillis).atZone(zone).format(entryTime) else entry.time
        // Entries are redacted when stored; repeat it so injected/legacy entries are also safe.
        return "$time ${entry.level.name.padEnd(7)} ${line(LogRedactor.redact(entry.message))}"
    }

    /** Plain-text report. [entries] are newest first, as stored; output is oldest first. */
    fun format(
        sections: List<DiagnosticSection>,
        entries: List<LogEntry>,
        settings: LogSettings,
        generated: ZonedDateTime,
        maxBytes: Int = MAX_BYTES,
    ): String {
        val header = buildString {
            append("Bimeron Android diagnostics\n")
            append("Generated: ").append(generated.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).append('\n')
            append("Privacy: keys, passwords, tokens, UUIDs, server addresses, IP addresses and file paths are redacted.\n")
            sections.forEach { section ->
                append("\n[").append(line(section.title)).append("]\n")
                section.rows.forEach { (key, value) -> append(line(key)).append(": ").append(line(value)).append('\n') }
            }
            append("\n[Journal]\n")
            append("Level: ").append(settings.level.name.lowercase()).append('\n')
            append("Limit: ").append(settings.maxEntries).append('\n')
            append("Keep previous entries: ").append(if (settings.keepPrevious) "yes" else "no").append('\n')
        }
        val lines = entries.asReversed().map { entryLine(it, generated.zone) }
        var budget = maxBytes - header.toByteArray().size - 128
        val kept = ArrayDeque<String>()
        for (index in lines.indices.reversed()) {
            val size = lines[index].toByteArray().size + 1
            if (size > budget) break
            budget -= size
            kept.addFirst(lines[index])
        }
        return buildString {
            append(header)
            append("Entries: ").append(entries.size)
            val omitted = lines.size - kept.size
            if (omitted > 0) append(" (").append(omitted).append(" oldest omitted to fit ").append(maxBytes / 1024).append(" KiB)")
            append('\n')
            if (entries.isEmpty()) append("(empty)\n")
            kept.forEach { append(it).append('\n') }
        }
    }

    fun fileName(now: ZonedDateTime): String =
        "bimeron-diagnostics-" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt"
}

internal fun yesNo(value: Boolean?): String = when (value) { true -> "yes"; false -> "no"; null -> "unknown" }

internal fun networkTransports(capabilities: NetworkCapabilities?): String {
    if (capabilities == null) return "none"
    val names = listOf(
        NetworkCapabilities.TRANSPORT_WIFI to "wifi", NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
        NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet", NetworkCapabilities.TRANSPORT_VPN to "vpn",
        NetworkCapabilities.TRANSPORT_BLUETOOTH to "bluetooth",
    ).filter { capabilities.hasTransport(it.first) }.map { it.second }
    return names.joinToString("+").ifEmpty { "other" }
}

/** Collects non-identifying environment facts. No addresses, SSIDs or profile names are read. */
internal object DiagnosticsCollector {
    fun sections(context: Context): List<DiagnosticSection> {
        val app = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
        }.getOrDefault("unknown")
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val core = runCatching { CoreCapabilities.read() }.getOrNull()
        val capabilities = core?.let { caps ->
            (CoreCapabilities.flags + "ikev2").joinToString(", ") { "$it=${yesNo(caps.support[it])}" }
        } ?: "unknown"
        val vpn = VpnLive.state.value
        return listOf(
            DiagnosticSection("Application", listOf(
                "Version" to app,
                "Build" to if (debuggable) "debug" else "release",
                "Locale" to Locale.getDefault().toLanguageTag(),
                "Time zone" to ZoneId.systemDefault().id,
            )),
            DiagnosticSection("Core", listOf(
                "Version" to (core?.version ?: "unknown"),
                "Platform" to (core?.platform ?: "unknown"),
                "GeoData schema" to (core?.geoDataSchema ?: "unknown"),
                "Can start VPN" to yesNo(core?.canStartVpn),
                "Capabilities" to capabilities,
            )),
            DiagnosticSection("Android", listOf(
                "Release" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                "Security patch" to Build.VERSION.SECURITY_PATCH,
                "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "ABI" to Build.SUPPORTED_ABIS.joinToString(", "),
            )),
            DiagnosticSection("Network", network(context)),
            DiagnosticSection("Connection", listOf(
                "State" to vpn.state,
                "Last message" to (vpn.message?.let(LogRedactor::redact) ?: "none"),
                "Received bytes" to vpn.received.toString(),
                "Sent bytes" to vpn.sent.toString(),
                "Duration seconds" to VpnLive.durationSeconds().toString(),
            )),
        )
    }

    private fun network(context: Context): List<Pair<String, String>> {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return listOf("Default network" to "unknown")
        return runCatching {
            val active = manager.activeNetwork
            val capabilities = active?.let { manager.getNetworkCapabilities(it) }
            val link: LinkProperties? = active?.let { manager.getLinkProperties(it) }
            @Suppress("DEPRECATION")
            val vpnAny = manager.allNetworks.any {
                manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
            listOf(
                "Default network" to networkTransports(capabilities),
                "Internet" to yesNo(capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)),
                "Validated" to yesNo(capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)),
                "Metered" to yesNo(capabilities?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }),
                "Any VPN network" to yesNo(vpnAny),
                "Private DNS active" to yesNo(link?.let { if (Build.VERSION.SDK_INT >= 28) it.isPrivateDnsActive else null }),
                "DNS servers" to (link?.dnsServers?.size?.toString() ?: "unknown"),
                "IPv6 default route" to yesNo(link?.routes?.any { it.isDefaultRoute && it.destination.address is java.net.Inet6Address }),
            )
        }.getOrElse { listOf("Default network" to "unavailable") }
    }
}

private val Context.diagnosticsStore by preferencesDataStore("diagnostics",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() })
private val levelKey = stringPreferencesKey("log_level")
private val limitKey = intPreferencesKey("log_limit")
private val keepKey = booleanPreferencesKey("log_keep_previous")

/** Process-wide: applies saved journal settings and records DEBUG network changes. */
internal object JournalRuntime {
    // Only the exception type is logged: messages may carry paths or other identifying text.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        Log.w("BimeronJournal", "Journal runtime failure: ${error.javaClass.name}")
    })
    @Volatile private var started = false
    @Volatile private var lastNetwork: String? = null

    fun start(context: Context) {
        val app = context.applicationContext
        AppEvents.resources = app.resources
        synchronized(this) { if (started) return; started = true }
        scope.launch { clearShared(app) }
        scope.launch {
            app.diagnosticsStore.data.catch { error ->
                if (error is IOException) emit(emptyPreferences()) else throw error
            }.map { prefs ->
                LogSettings(
                    LogSettings.sanitizedLevel(LogLevel.parse(prefs[levelKey])),
                    LogSettings.sanitizedLimit(prefs[limitKey]),
                    prefs[keepKey] ?: true,
                )
            }.collect { AppEvents.apply(it) }
        }
        runCatching {
            app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    val summary = app.getString(if (validated) R.string.diag_network_validated else R.string.diag_network_unvalidated,
                        networkTransports(capabilities))
                    if (summary != lastNetwork) { lastNetwork = summary; AppEvents.add(summary, LogLevel.DEBUG) }
                }
                override fun onLost(network: Network) {
                    lastNetwork = null
                    AppEvents.add(app.getString(R.string.diag_network_lost), LogLevel.DEBUG)
                }
            })
        }
    }

    /** Previous reports are removed at process start and on clear; only the latest share stays. */
    fun shareDirectory(context: Context) = File(context.cacheDir, "diagnostics").apply { mkdirs() }
    fun clearShared(context: Context) { shareDirectory(context).listFiles()?.forEach { it.delete() } }

    suspend fun save(context: Context, settings: LogSettings) {
        context.applicationContext.diagnosticsStore.edit {
            it[levelKey] = settings.level.name
            it[limitKey] = settings.maxEntries
            it[keepKey] = settings.keepPrevious
        }
    }
}

class DiagnosticsModel(application: Application) : AndroidViewModel(application) {
    val settings = AppEvents.settings
    val entries = AppEvents.entries

    fun update(next: LogSettings) {
        AppEvents.apply(next)
        viewModelScope.launch {
            try { JournalRuntime.save(getApplication(), next) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
        }
    }

    fun clear() {
        AppEvents.clear()
        viewModelScope.launch(Dispatchers.IO) { JournalRuntime.clearShared(getApplication()) }
    }

    fun fileName(): String = DiagnosticReport.fileName(ZonedDateTime.now())

    private suspend fun report(): String = withContext(Dispatchers.IO) {
        DiagnosticReport.format(DiagnosticsCollector.sections(getApplication()), AppEvents.entries.value,
            AppEvents.settings.value, ZonedDateTime.now())
    }

    /** Writes the report to app cache and returns a chooser intent granting one-time read access. */
    suspend fun shareIntent(): Intent {
        val context = getApplication<Application>()
        val text = report()
        val uri = withContext(Dispatchers.IO) {
            JournalRuntime.clearShared(context)
            val file = File(JournalRuntime.shareDirectory(context), fileName())
            file.writeText(text, Charsets.UTF_8)
            FileProvider.getUriForFile(context, context.packageName + ".diagnostics", file)
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.diag_share_subject))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri("", uri)
        return Intent.createChooser(send, context.getString(R.string.diag_share_title))
    }

    suspend fun save(uri: Uri, resolver: ContentResolver) {
        val text = report()
        withContext(Dispatchers.IO) {
            val stream = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
                ?: resolver.openOutputStream(uri, "w") ?: throw java.io.IOException("no output stream")
            stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }
    }

}
