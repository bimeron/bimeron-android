package org.bimeron.app

import android.content.res.Resources
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Severity of a journal entry; ordinal order is used for minimum-level filtering. */
enum class LogLevel {
    DEBUG, INFO, WARNING, ERROR;

    fun records(level: LogLevel): Boolean = level.ordinal >= ordinal

    companion object {
        fun parse(value: String?): LogLevel? = entries.firstOrNull { it.name == value }
    }
}

data class LogEntry(
    val time: String,
    val message: String,
    val level: LogLevel = LogLevel.INFO,
    val epochMillis: Long = 0L,
)

data class LogSettings(
    val level: LogLevel = LogLevel.INFO,
    val maxEntries: Int = DEFAULT_LIMIT,
    val keepPrevious: Boolean = true,
) {
    companion object {
        const val DEFAULT_LIMIT = 5000
        val limits = listOf(500, 1000, DEFAULT_LIMIT)
        val levels = listOf(LogLevel.WARNING, LogLevel.INFO, LogLevel.DEBUG)
        fun sanitizedLimit(value: Int?): Int = value?.takeIf { it in limits } ?: DEFAULT_LIMIT
        fun sanitizedLevel(value: LogLevel?): LogLevel = value?.takeIf { it in levels } ?: LogLevel.INFO
    }
}

/** Process-wide journal: newest entry first, redacted before it is stored. */
object AppEvents {
    const val MAX_MESSAGE = 2000
    val entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val settings = MutableStateFlow(LogSettings())
    private val format = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss")
    /** Application resources for entries raised without a Context; set by [JournalRuntime.start]. */
    @Volatile var resources: Resources? = null

    /** Records a localized entry; without [resources] (e.g. plain JVM tests) the id is recorded instead. */
    fun add(@StringRes id: Int, level: LogLevel, vararg args: Any) {
        val text = resources?.let { if (args.isEmpty()) it.getString(id) else it.getString(id, *args) } ?: "#$id"
        add(text, level)
    }

    fun add(message: String, level: LogLevel = LogLevel.INFO, now: Long = System.currentTimeMillis()) {
        val config = settings.value
        if (!config.level.records(level)) return
        val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault()).format(format)
        val entry = LogEntry(time, LogRedactor.redact(message).take(MAX_MESSAGE), level, now)
        entries.update { (listOf(entry) + it).take(config.maxEntries) }
    }

    fun clear() { entries.value = emptyList() }

    /** Desktop parity: "Keep previous entries" off clears the journal when a new connection starts. */
    fun connectionStarting() { if (!settings.value.keepPrevious) clear() }

    fun apply(next: LogSettings) {
        settings.value = next
        entries.update { it.take(next.maxEntries) }
    }
}

/**
 * Masks secrets and addresses in free text. Placeholders are not matched again, so
 * redaction is idempotent. Ordinary localized status messages are unchanged.
 * Input is bounded before matching and every pattern uses bounded repetition; any
 * matcher failure yields [FAILED], never the original text.
 */
object LogRedactor {
    const val SECRET = "***"
    const val IP = "<ip>"
    const val HOST = "<host>"
    const val PATH = "<path>"
    const val FAILED = "<redaction failed>"
    const val MAX_INPUT = 8192
    private val placeholders = setOf(SECRET, IP, HOST, PATH)

    private const val SECRET_KEYS = """obfs[_-]?password|auth[_-]?str|pre-?shared[ _-]?key|private[ _-]?key|""" +
        """password|passphrase|passwd|pass|pwd|psk|secret"""
    private const val TOKEN_KEYS = """access[ _-]?token|api[ _-]?key|public[ _-]?key|short[ _-]?id|server[ _-]?name|""" +
        """authorization|auth|token|answers|uuid|username|user|login|key|id|server|address|endpoint|host|sni|peer|obfs"""
    private const val KEY_START = """(?<![\p{L}\p{N}_\-])(["']?)"""
    private const val QUOTED = """"[^"\r\n]{0,8192}"|'[^'\r\n]{0,8192}'"""
    // A quoted secret counts as the whole value only if nothing but a separator follows it;
    // otherwise the value runs to the end of the line, so text after the quotes is never kept.
    private const val SECRET_QUOTED = """(?:$QUOTED)(?=\s{0,8}(?:[,;&}\]\r\n]|$))"""
    // Bracketed values (Endpoint = [2001:db8::1]:51820) are only single-token values.
    private const val TOKEN_QUOTED = """$QUOTED|\[[^\]\s]{1,64}][^\s,;&"'}\]]{0,8192}"""

    private val uri = Regex("""(?<![A-Za-z0-9+.\-])([A-Za-z][A-Za-z0-9+.\-]{1,15})://[^\s"'<>]{1,8192}""")
    private val bearer = Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=\-]{1,8192}""")
    // Password-like values run to the end of the line or to , ; & so multi-word secrets are covered.
    private val secretValue = Regex("""(?iu)$KEY_START($SECRET_KEYS)\1(\s{0,8}[=:]\s{0,8})($SECRET_QUOTED|[^\r\n,;&]{1,8192})""")
    private val tokenValue = Regex("""(?iu)$KEY_START($TOKEN_KEYS)\1(\s{0,8}[=:]\s{0,8})($TOKEN_QUOTED|[^\s,;&"'}\]]{1,8192})""")
    private val path = Regex("""(?<![\w.])/(?:data|storage|sdcard|mnt)/\S{1,8192}""")
    private val userHost = Regex("""(?<![A-Za-z0-9._%+\-])[A-Za-z0-9._%+\-]{1,64}@(?:\[[0-9A-Fa-f:.%]{2,45}]|[\p{L}\p{N}\-]{1,63}(?:\.[\p{L}\p{N}\-]{1,63}){0,32})(?::\d{1,5})?""")
    private val uuid = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
    private val ipv4 = Regex("""(?<!\d|\d\.)(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?:\.(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}(?:/\d{1,2})?(?::\d{1,5})?(?!\d|\.\d)""")
    private val bracketedIpv6 = Regex("""\[[0-9A-Fa-f:.]{2,45}(?:%[0-9A-Za-z]{1,16})?](?::\d{1,5})?""")
    private val ipv6 = Regex("""(?<![0-9A-Za-z:])(?:[0-9A-Fa-f]{0,4}:){2,8}[0-9A-Fa-f]{0,4}(?:%[0-9A-Za-z]{1,16})?(?:/\d{1,3})?(?![0-9A-Za-z])""")
    // Labels and TLDs may be internationalized (Unicode letters) or punycode (xn--...).
    private val fqdn = Regex("""(?<![\p{L}\p{N}_\-.@<])((?:[\p{L}\p{N}](?:[\p{L}\p{N}\-]{0,61}[\p{L}\p{N}])?\.){1,32}(?:\p{L}{2,63}|xn--[A-Za-z0-9\-]{1,59}))(?::\d{1,5})?(?!\.?[\p{L}\p{N}_\-])""")
    private val token = Regex("""(?<![A-Za-z0-9+/_\-])[A-Za-z0-9+/_\-]{24,8192}={0,2}""")
    private val paddedToken = Regex("""(?<![A-Za-z0-9+/_\-])[A-Za-z0-9+/_\-]{16,8192}={1,2}(?![A-Za-z0-9+/=_\-])""")

    // Reverse-domain code identifiers (org.bimeron.app.MainActivity) are not hosts.
    private val packagePrefixes = setOf("org", "com", "net", "io", "java", "javax", "kotlin", "kotlinx",
        "android", "androidx", "dalvik", "libcore", "sun", "golang")
    // File extensions that are not delegated TLDs.
    private val fileExtensions = setOf("conf", "txt", "log", "json", "kt", "kts", "java", "aar", "apk", "dat",
        "xml", "html", "htm", "png", "jpg", "jpeg", "gif", "svg", "webp", "proto", "yaml", "yml", "toml", "ini",
        "pem", "crt", "csv", "gz", "tar", "dex", "jar", "class", "properties", "gradle", "geoip", "geosite")
    // Mixed-case candidates (Session.Status, Build.VERSION) are code unless they end in a common TLD.
    private val commonTlds = setOf("com", "net", "org", "io", "ru", "su", "info", "dev", "app", "cloud", "me",
        "co", "uk", "de", "nl", "fr", "us", "xyz", "top", "online", "site", "pro", "biz", "by", "kz", "ua", "eu",
        "tech", "vip", "ai", "cc", "tv", "jp", "cn", "hk", "sg", "fi", "se", "ch", "pl", "tr", "ir", "am", "ge")
    // This app's own package id ends in a real TLD but is not a server name.
    private val ownIds = setOf("org.bimeron.app")

    fun redact(text: String): String = try {
        redactBounded(text.take(MAX_INPUT))
    } catch (_: StackOverflowError) {
        FAILED
    } catch (_: RuntimeException) {
        FAILED
    }

    private fun keyValue(match: MatchResult): String {
        val raw = match.groupValues[4]
        val quote = raw.firstOrNull()?.takeIf { (it == '"' || it == '\'') && raw.length >= 2 && raw.last() == it }
        val value = (if (quote != null) raw.substring(1, raw.length - 1) else raw).trim()
        if (value in placeholders || value.lowercase() in setOf("bearer", "basic")) return match.value
        val masked = if (quote != null) "$quote$SECRET$quote" else SECRET
        return match.groupValues[1] + match.groupValues[2] + match.groupValues[1] + match.groupValues[3] + masked
    }

    private fun host(match: MatchResult): String {
        val name = match.groupValues[1]
        val labels = name.split('.')
        val tld = labels.last().lowercase()
        val upper = name.any { it.isUpperCase() }
        // Reverse-domain prefixes (android., io., net.) are code only when they look like code;
        // android.clients.google.com stays a host. Ambiguous cases prefer masking.
        val reverseDomain = labels.size >= 3 && labels.first().lowercase() in packagePrefixes &&
            (upper || tld !in commonTlds)
        val code = name.lowercase() in ownIds || reverseDomain || tld in fileExtensions || (upper && tld !in commonTlds)
        return if (code) match.value else HOST
    }

    private fun redactBounded(text: String): String {
        var result = text
        result = uri.replace(result) { "${it.groupValues[1]}://$SECRET" }
        result = bearer.replace(result) { "${it.groupValues[1]} $SECRET" }
        result = secretValue.replace(result, ::keyValue)
        result = tokenValue.replace(result, ::keyValue)
        result = path.replace(result, PATH)
        result = userHost.replace(result, "$SECRET@$HOST")
        result = uuid.replace(result, SECRET)
        result = ipv4.replace(result, IP)
        result = bracketedIpv6.replace(result) { if (':' in it.value) IP else it.value }
        result = ipv6.replace(result) { match ->
            val core = match.value.substringBefore('%').substringBefore('/')
            val address = if (core.endsWith("::")) core else core.trimEnd(':')
            val trailing = match.value.removePrefix(address).takeIf { core != address && it.all { c -> c == ':' } } ?: ""
            val groups = address.split(':')
            // Times such as 12:30:45 have three groups and no "::"; keep them.
            if (address.any { it.isLetterOrDigit() } && ("::" in address || groups.size >= 4)) IP + trailing else match.value
        }
        result = fqdn.replace(result, ::host)
        result = paddedToken.replace(result, SECRET)
        result = token.replace(result) { match ->
            val value = match.value
            if (value.any { it.isDigit() } && value.any { it.isLetter() }) SECRET else value
        }
        return result
    }
}
