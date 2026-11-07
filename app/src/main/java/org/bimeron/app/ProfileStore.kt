package org.bimeron.app

import android.content.Context
import androidx.annotation.StringRes
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.bimeron.core.androidcore.Androidcore
import org.bimeron.core.androidcore.SocketProtector

data class Profile(val id: String, val name: String, val protocol: String, val mode: String, val text: String)
/**
 * Profile validation or storage failure. Built-in messages carry a string resource
 * ([resId], [args]) resolved for display via [text]; [message] is kept for raw codes
 * (conflict reasons) and already-localized text.
 */
class ProfileError private constructor(message: String?, @StringRes val resId: Int, val args: List<Any>) : Exception(message) {
    constructor(message: String) : this(message, 0, emptyList())
    constructor(@StringRes resId: Int, vararg args: Any) : this(null, resId, args.toList())

    fun text(resolve: (Int, Array<out Any>) -> String): String =
        if (resId != 0) resolve(resId, args.toTypedArray()) else message.orEmpty()
    fun text(context: Context): String = text { id, values -> context.string(id, values) }
}

/** getString that skips formatting when there are no arguments. */
internal fun Context.string(@StringRes id: Int, args: Array<out Any>): String =
    if (args.isEmpty()) getString(id) else getString(id, *args)
class KeyFileNeeded(val fileName: String) : Exception()

object Conf {
    val sources = setOf("SS", "SSConf", "SOCKS5", "HTTP", "MASQUE", "VLESS", "VMess", "Trojan", "Naive", "Hysteria2", "AnyTLS", "SSH", "TUIC", "Key", "File")
    private val fields = mapOf("ss" to "SS", "ssconf" to "SSConf", "socks5" to "SOCKS5", "vless" to "VLESS", "trojan" to "Trojan", "http" to "HTTP", "https" to "HTTP", "masque" to "MASQUE", "vmess" to "VMess", "naive+https" to "Naive", "naive+quic" to "Naive", "hysteria2" to "Hysteria2", "hy2" to "Hysteria2", "anytls" to "AnyTLS", "ssh" to "SSH", "tuic" to "TUIC")
    fun read(bytes: ByteArray): String {
        if (bytes.size > 65536) throw ProfileError(R.string.profile_error_file_too_large)
        val decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return try { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
        catch (_: Exception) { throw ProfileError(R.string.profile_error_not_utf8) }
    }
    const val NAME_LIMIT = 128
    fun validName(name: String): String {
        val value = name.trim()
        if (value.isEmpty() || value.length > NAME_LIMIT || value.any { it.isISOControl() }) throw ProfileError(R.string.profile_error_invalid_name)
        return value
    }
    fun keyField(key: String): String {
        val value = key.trim()
        val scheme = value.substringBefore("://")
        val field = fields[scheme] ?: throw ProfileError(R.string.profile_error_unsupported_key_scheme)
        if (!value.startsWith("$scheme://") || value.substringAfter("://").isEmpty() || value.any { it.isWhitespace() || it.isISOControl() }) throw ProfileError(R.string.profile_error_invalid_key)
        return field
    }
    fun fromKey(name: String, key: String): String {
        val safeName = validName(name)
        val value = key.trim()
        val field = keyField(value)
        return "[Profile]\nVersion = 1\nName = ${quote(safeName)}\n\n[AccessKey]\n$field = $value\n\n[Interface]\nMode = vpn\nName = Bimeron\nMTU = 1420\nDNS = 1.1.1.1\nIPv6 = disable\nKillSwitch = false\n\n[Routing]\nDefault = proxy\n"
    }
    fun quote(value: String): String {
        if (value.none { it == '"' || it == '\\' || it.isISOControl() || it == '=' } && value == value.trim()) return value
        return "\"" + buildString {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    else -> if (char.isISOControl()) append("\\u%04x".format(char.code)) else append(char)
                }
            }
        } + "\""
    }
    fun unquote(value: String): String {
        if (!value.startsWith('"')) return value
        if (value.length < 2 || !value.endsWith('"')) throw ProfileError(R.string.profile_error_invalid_quoted_value)
        val result = StringBuilder()
        var i = 1
        while (i < value.lastIndex) {
            val char = value[i++]
            if (char == '"' || char == '\n') throw ProfileError(R.string.profile_error_invalid_quoted_value)
            if (char != '\\') { result.append(char); continue }
            if (i >= value.lastIndex) throw ProfileError(R.string.profile_error_invalid_escape)
            when (val escaped = value[i++]) {
                'n' -> result.append('\n'); 'r' -> result.append('\r'); 't' -> result.append('\t')
                'b' -> result.append('\b'); 'f' -> result.append('\u000C'); 'v' -> result.append('\u000B'); 'a' -> result.append('\u0007')
                '\\', '"' -> result.append(escaped)
                'x', 'u', 'U' -> {
                    val digits = when (escaped) { 'x' -> 2; 'u' -> 4; else -> 8 }
                    if (i + digits > value.lastIndex) throw ProfileError(R.string.profile_error_invalid_escape)
                    val hex = value.substring(i, i + digits)
                    if (hex.any { it !in "0123456789abcdefABCDEF" }) throw ProfileError(R.string.profile_error_invalid_escape)
                    val code = hex.toIntOrNull(16) ?: throw ProfileError(R.string.profile_error_invalid_escape)
                    if (code > 0x10FFFF || code in 0xD800..0xDFFF) throw ProfileError(R.string.profile_error_invalid_escape)
                    result.append(String(Character.toChars(code))); i += digits
                }
                in '0'..'7' -> {
                    if (i + 2 > value.lastIndex || value.substring(i, i + 2).any { it !in '0'..'7' }) throw ProfileError(R.string.profile_error_invalid_escape)
                    val code = ("$escaped${value.substring(i, i + 2)}").toInt(8)
                    if (code > 255) throw ProfileError(R.string.profile_error_invalid_escape)
                    result.append(code.toChar()); i += 2
                }
                else -> throw ProfileError(R.string.profile_error_invalid_escape)
            }
        }
        return result.toString()
    }
    fun parse(text: String, fallbackName: String? = null): Profile {
        if (text.toByteArray(StandardCharsets.UTF_8).size > 65536) throw ProfileError(R.string.profile_error_file_too_large)
        var section = ""
        val seenSections = mutableSetOf<String>()
        var fieldsSeen = mutableSetOf<String>()
        val values = mutableMapOf<String, String>()
        val rules = mutableListOf<MutableMap<String, String>>()
        text.lineSequence().forEachIndexed { index, raw ->
            val line = (if (index == 0) raw.removePrefix("\uFEFF") else raw).trim()
            if (line.isEmpty() || line.startsWith('#') || line.startsWith(';')) return@forEachIndexed
            if (line.startsWith('[') && line.endsWith(']')) {
                section = line.substring(1, line.length - 1).trim()
                if (section !in setOf("Profile", "AccessKey", "Interface", "Routing", "Rule", "ShadowTLS", "IKEv2")) throw ProfileError(R.string.profile_error_unknown_section_line, index + 1)
                if (section != "Rule" && !seenSections.add(section)) throw ProfileError(R.string.profile_error_duplicate_section_line, index + 1)
                if (section == "Rule") rules += mutableMapOf()
                fieldsSeen = mutableSetOf(); return@forEachIndexed
            }
            val equal = line.indexOf('=')
            if (section.isEmpty() || equal <= 0) throw ProfileError(R.string.profile_error_syntax_line, index + 1)
            val key = line.substring(0, equal).trim()
            if (!fieldsSeen.add(key)) throw ProfileError(R.string.profile_error_duplicate_field_line, index + 1)
            val value = unquote(line.substring(equal + 1).trim())
            if (section == "Rule") rules.last()[key] = value else values["$section.$key"] = value
        }
        if (values["Profile.Version"] != "1") throw ProfileError(R.string.profile_error_version_required)
        val name = validName(values["Profile.Name"]?.takeIf { it.isNotBlank() } ?: fallbackName ?: throw ProfileError(R.string.profile_error_missing_name))
        val access = sources.filter { values.containsKey("AccessKey.$it") }
        val explicit = values.containsKey("AccessKey.Type")
        if (access.size + (if (explicit) 1 else 0) != 1) throw ProfileError(R.string.profile_error_single_key_source)
        val mode = values["Interface.Mode"] ?: "vpn"
        if (mode !in setOf("vpn", "proxy")) throw ProfileError(R.string.profile_error_invalid_mode)
        val mtu = values["Interface.MTU"]?.let { it.toIntOrNull() ?: throw ProfileError(R.string.profile_error_mtu_not_integer) } ?: 1420
        if (!Regex("[A-Za-z][A-Za-z0-9_-]{0,31}").matches(values["Interface.Name"] ?: "Bimeron")) throw ProfileError(R.string.profile_error_invalid_interface_name)
        if (mtu !in 1280..1500) throw ProfileError(R.string.profile_error_mtu_range)
        if (values["Interface.KillSwitch"]?.let { it !in setOf("true", "false") } == true || values["Interface.IPv6"]?.let { it !in setOf("enable", "disable") } == true) throw ProfileError(R.string.profile_error_invalid_interface)
        if (values["Routing.Default"]?.let { it !in setOf("proxy", "direct", "block") } == true) throw ProfileError(R.string.profile_error_invalid_default_route)
        val dns = (values["Interface.DNS"] ?: "1.1.1.1").split(',').map { it.trim() }
        if (dns.size !in 1..4 || dns.toSet().size != dns.size || dns.any { !validIPv4(it) || it == "0.0.0.0" || it == "255.255.255.255" || it.startsWith("127.") || it.startsWith("169.254.") || it.substringBefore('.').toIntOrNull() in 224..239 }) throw ProfileError(R.string.profile_error_invalid_dns)
        rules.forEachIndexed { index, rule ->
            val cidr = rule["CIDR"]
            val domain = rule["Domain"]
            if ((cidr == null) == (domain == null) || rule["Action"] !in setOf("proxy", "direct", "block")) throw ProfileError(R.string.profile_error_invalid_rule, index + 1)
            if (domain != null && (domain.trim().isEmpty() || domain.any { it.isWhitespace() || it in "/*:" })) throw ProfileError(R.string.profile_error_invalid_rule_domain, index + 1)
            if (cidr != null && cidr.split(',').any { !validCidr(it.trim()) }) throw ProfileError(R.string.profile_error_invalid_rule_cidr, index + 1)
        }
        val protocol = when { explicit -> values["AccessKey.Type"] ?: ""; else -> access.single() }
        return Profile("", name, protocol, mode, text)
    }
    private fun validIPv4(value: String): Boolean = value.split('.').let { parts -> parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) && part.toIntOrNull()?.let { it in 0..255 } == true && (part == "0" || !part.startsWith('0')) } }
    private fun validCidr(value: String): Boolean {
        val parts = value.split('/')
        if (parts.size != 2) return false
        val address = parts[0]
        if (!validIPv4(address) && !address.contains(':')) return false
        return try {
            val parsed = java.net.InetAddress.getByName(address)
            val max = if (parsed.address.size == 4) 32 else 128
            val prefix = parts[1].toIntOrNull() ?: return false
            prefix in 0..max
        } catch (_: Exception) { false }
    }
    fun rename(text: String, name: String): String {
        val safe = quote(validName(name))
        var section = ""
        var found = false
        val lines = text.split('\n').toMutableList()
        for (i in lines.indices) {
            val line = if (i == 0) lines[i].removePrefix("\uFEFF") else lines[i]
            if (line.trim().startsWith("[") && line.trim().endsWith("]")) section = line.trim().removePrefix("[").removeSuffix("]")
            else if (section == "Profile" && line.substringBefore('=', "").trim() == "Name") {
                val prefix = line.substringBefore('=')
                lines[i] = "$prefix= $safe" + if (line.endsWith("\r")) "\r" else ""; found = true; break
            }
        }
        if (!found) throw ProfileError(R.string.profile_error_profile_name_field_missing)
        return lines.joinToString("\n")
    }
    fun inlineFile(text: String, key: String): String {
        val value = read(key.toByteArray(StandardCharsets.UTF_8)).trim().removePrefix("\uFEFF").trim()
        val normalized = read(text.toByteArray(StandardCharsets.UTF_8))
        ConfigDocument.parse(normalized)
        keyField(value)
        var section = ""
        val lines = normalized.split('\n').toMutableList()
        for (i in lines.indices) {
            val line = if (i == 0) lines[i].removePrefix("\uFEFF") else lines[i]
            if (line.trim().startsWith("[") && line.trim().endsWith("]")) section = line.trim().removePrefix("[").removeSuffix("]").trim()
            else if (section == "AccessKey" && line.substringBefore('=', "").trim() == "File") {
                lines[i] = ("${line.substringBefore('=')}= $value" + if (line.endsWith("\r")) "\r" else "").replace(Regex("^\\s*File\\s*="), "Key =")
                return read(lines.joinToString("\n").toByteArray(StandardCharsets.UTF_8))
            }
        }
        throw ProfileError(R.string.profile_error_access_key_file_missing)
    }
    fun ensureName(text: String, fallback: String): String {
        val document = ConfigDocument.parse(text)
        if (document.value("Profile", "Name").isNotBlank()) return text
        if ("Profile" !in document.sections) throw ProfileError(R.string.profile_error_profile_section_missing)
        return document.set("Profile", "Name", validName(fallback)).render()
    }
}

/** In-process only. */
data class ProfileRevision(val epoch: Long, val counter: Long)
class Sha256 private constructor(private val value: ByteArray) {
    override fun equals(other: Any?) = other is Sha256 && value.contentEquals(other.value)
    override fun hashCode() = value.contentHashCode()
    companion object { internal fun of(bytes: ByteArray) = Sha256(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)) }
}
data class StoredProfile(val profile: Profile, val revision: ProfileRevision, val digest: Sha256) {
    val id get() = profile.id
    val name get() = profile.name
    val protocol get() = profile.protocol
    val mode get() = profile.mode
    val text get() = profile.text
}
data class SSConfBinding(val source: String)
class SnapshotTicket internal constructor(val profileId: UUID, val source: String, val seq: Long, internal val stillValid: () -> Boolean)
class StoredSnapshot(body: ByteArray, val digest: Sha256, val savedAt: Long) {
    private val owned = body.copyOf()
    val body get() = owned.copyOf()
}
class SessionInput internal constructor(val id: UUID, bytes: ByteArray, val revision: ProfileRevision,
    val digest: Sha256, val ssconf: SSConfBinding?, val ticket: SnapshotTicket?, val snapshot: StoredSnapshot?) {
    private val owned = bytes.copyOf()
    val bytes get() = owned.copyOf()
}
data class CommitReceipt(val requestId: UUID, val profileId: UUID, val kind: Kind,
    val revision: ProfileRevision?, val digest: Sha256?) {
    enum class Kind { SAVED, DELETED }
}
sealed interface CommitResult {
    data class Committed(val receipt: CommitReceipt) : CommitResult
    data class Conflict(val reason: ConflictReason) : CommitResult
}
enum class ConflictReason { CHANGED, DELETED, EXISTS, UNREADABLE, STALE_TICKET, SOURCE_CHANGED, SUPERSEDED }
/** One profile as stored now, read with its revision in one step under the store lock. */
sealed interface LatestProfile {
    data class Present(val stored: StoredProfile) : LatestProfile
    data object Deleted : LatestProfile
    data object Unreadable : LatestProfile
}

class ProfileStore internal constructor(
    private val context: Context?, private val directory: File,
    resources: SshProfileResources, private val trustedRoot: File? = null,
    private val coreValidation: ((String) -> Unit)? = null,
) {
    constructor(context: Context) : this(context, context.filesDir.absoluteFile)
    private constructor(context: Context, filesDir: File) : this(context, filesDir, trustedFilesRoot(context, filesDir))
    // An untrusted root keeps lexical paths only for identity; rootValid gates every filesystem effect.
    private constructor(context: Context, filesDir: File, root: File?) : this(context, profileDirectory(root, filesDir),
        SshProfileResources(File(root ?: filesDir, "ssh-resources")), root)
    private val rootValid = context == null || trustedRoot?.let { root -> runCatching { checkedBelow(root, directory) }.isSuccess } == true
    private val ownedResources = resources
    val resources: SshProfileResources get() { check(rootValid); return ownedResources }
    companion object {
        /**
         * Resolves filesDir once to the trusted real root. Platform aliases above the package data
         * directory (e.g. /data/user/0 -> /data/data inside the app mount namespace) are resolved;
         * any alias at or below it, or anywhere in a wrapped root outside it, rejects the root.
         * Returns null when untrusted; a missing data directory or a non-directory root throws,
         * so store initialization fails closed.
         */
        internal fun trustedFilesRoot(context: Context, filesDir: File): File? {
            val dataDir = File(requireNotNull(context.applicationInfo?.dataDir)).absoluteFile.toPath()
            val root = runCatching {
                val lexical = filesDir.absoluteFile.toPath()
                require(lexical == lexical.normalize() && dataDir == dataDir.normalize())
                require(!java.nio.file.Files.isSymbolicLink(dataDir))
                val realData = dataDir.toRealPath()
                val anchor = listOf(dataDir, realData).firstOrNull { lexical.startsWith(it) }
                val root = if (anchor != null) {
                    var current = realData
                    anchor.relativize(lexical).forEach { name ->
                        if (name.toString().isEmpty()) return@forEach
                        current = current.resolve(name)
                        require(!java.nio.file.Files.isSymbolicLink(current))
                    }
                    current
                } else {
                    // Wrapped roots outside the package data directory must be alias-free from "/".
                    generateSequence(lexical) { it.parent }.forEach { require(!java.nio.file.Files.isSymbolicLink(it)) }
                    lexical
                }
                require(root == root.toRealPath())
                // The resolved root must belong to this package's uid, the owner of its data directory.
                require(java.nio.file.Files.getOwner(root, java.nio.file.LinkOption.NOFOLLOW_LINKS) ==
                    java.nio.file.Files.getOwner(realData, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                root
            }.getOrNull() ?: return null
            require(java.nio.file.Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            return root.toFile()
        }
        /** Rejects traversal outside [root] and any symlink in the components below it. */
        private fun checkedBelow(root: File, file: File) {
            val base = root.toPath()
            val path = file.absoluteFile.toPath()
            require(path == path.normalize() && path.startsWith(base))
            var current = base
            base.relativize(path).forEach { name ->
                if (name.toString().isEmpty()) return@forEach
                current = current.resolve(name)
                require(!java.nio.file.Files.isSymbolicLink(current))
            }
        }
        private fun profileDirectory(root: File?, filesDir: File): File {
            val directory = File(root ?: filesDir, "profiles")
            if (root != null && runCatching { checkedBelow(root, directory) }.isSuccess) {
                require(!directory.exists() || directory.isDirectory)
                directory.mkdirs()
                java.nio.file.Files.setPosixFilePermissions(directory.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
            }
            return directory
        }
        private val PRIVATE_DIRECTORY = java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        private val PRIVATE_FILE = java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        @Volatile private var instance: ProfileStore? = null
        fun get(context: Context): ProfileStore {
            instance?.let { return it }
            check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            return synchronized(this) {
                instance ?: ProfileStore(context).also { store ->
                    synchronized(store.guard) { store.sweepBeforeDrafts() }
                    instance = store
                }
            }
        }
    }
    private fun sweepBeforeDrafts() {
        try {
            require(rootValid)
            val filesRoot = requireNotNull(trustedRoot)
            require(directory.absoluteFile.parentFile == filesRoot)
            val candidates = mutableListOf<File>()
            fun entries(file: File): List<File> {
                checkedBelow(filesRoot, file)
                if (!file.exists()) return emptyList()
                require(file.isDirectory)
                return (file.listFiles() ?: error("inventory")).toList()
            }
            fun regular(file: File) = java.nio.file.Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
            fun uuid(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
            val references = mutableMapOf<UUID, Set<String>>()
            entries(directory).forEach { file ->
                require(regular(file))
                when {
                    file.name.endsWith(".conf") && uuid(file.name.removeSuffix(".conf")) -> {
                        val id = UUID.fromString(file.name.removeSuffix(".conf"))
                        references[id] = SshDraftResources.paths(read(id).first.profile.text)
                    }
                    file.name.matches(Regex("profile-.+\\.tmp")) -> candidates.add(file)
                    else -> error("inventory")
                }
            }
            entries(File(filesRoot, "ssconf")).forEach { file ->
                require(regular(file))
                val name = file.name
                when {
                    name.endsWith(".snap") && uuid(name.removeSuffix(".snap")) ->
                        if (UUID.fromString(name.removeSuffix(".snap")) !in references) candidates.add(file)
                    name.contains(".snap.tmp-") && uuid(name.substringBefore(".snap.tmp-")) && name.substringAfter(".snap.tmp-").isNotEmpty() -> candidates.add(file)
                    else -> error("inventory")
                }
            }
            // Accept-new host keys: one file per profile plus write leftovers; no write runs during the sweep.
            entries(File(filesRoot, TrustedHostKeys.DIRECTORY)).forEach { file ->
                require(regular(file))
                val name = file.name
                when {
                    name.endsWith(".known_hosts") && uuid(name.removeSuffix(".known_hosts")) ->
                        if (UUID.fromString(name.removeSuffix(".known_hosts")) !in references) candidates.add(file)
                    name.contains(".known_hosts.tmp-") && uuid(name.substringBefore(".known_hosts.tmp-")) &&
                        name.substringAfter(".known_hosts.tmp-").isNotEmpty() -> candidates.add(file)
                    else -> error("inventory")
                }
            }
            // Validate the full resource namespace before either subsystem deletes anything.
            val base = File(filesRoot, "ssh-resources")
            entries(base).forEach { area ->
                require(area.name in setOf("profiles", "staging"))
                entries(area).forEach { entry ->
                    require(uuid(entry.name))
                    val generations = if (area.name == "staging") listOf(entry) else entries(entry)
                    generations.forEach { generation ->
                        require(uuid(generation.name))
                        val children = entries(generation)
                        require(children.size == 1 && children.all { regular(it) && it.name in setOf("identity", "known_hosts") })
                    }
                }
            }
            references.forEach { (id, paths) -> paths.forEach { path ->
                val file = File(path)
                require(file.isAbsolute && file.toPath().normalize().toString() == path)
                val generation = requireNotNull(file.parentFile)
                require(generation.parentFile == File(base, "profiles/$id"))
                require(uuid(generation.name) && file.name in setOf("identity", "known_hosts"))
                require(!java.nio.file.Files.isSymbolicLink(file.toPath()))
            } }
            resources.sweepBeforeDrafts(references)
            candidates.forEach { require(it.delete()) }
        } catch (_: Exception) { AppEvents.add("STORE_SWEEP_SKIPPED") }
    }
    private val guard = Any()
    private val epoch = java.security.SecureRandom().nextLong()
    private var counter = 0L
    private var ticketSeq = 0L
    private val lastCommittedSeq = mutableMapOf<UUID, Long>()
    private val known = mutableMapOf<UUID, Pair<ProfileRevision, Sha256>>()
    private data class Request(val id: UUID, val kind: CommitReceipt.Kind, val expected: ProfileRevision?, val payload: String?, val draft: SshDraftResources?)
    private val receipts = mutableMapOf<UUID, Pair<Request, CommitReceipt>>()
    internal var renameProfile: (File, File) -> Boolean = { source, target ->
        java.nio.file.Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        true
    }
    /** Test hook for snapshot publication failures; must be atomic. */
    internal var moveSnapshot: (File, File) -> Unit = { source, target ->
        java.nio.file.Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
    private fun target(id: UUID) = File(directory, "$id.conf")
    private fun read(id: UUID): Pair<StoredProfile, ByteArray> {
        require(rootValid)
        val file = target(id)
        require(java.nio.file.Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        val bytes = file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= 65536) {
                val n = input.read(buffer, 0, minOf(buffer.size, 65537 - output.size()))
                if (n < 0) break
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
        val text = Conf.read(bytes)
        val profile = ConfigDocument.parse(text).validate().copy(id = id.toString())
        val digest = Sha256.of(bytes)
        val revision = known[id]?.takeIf { it.second == digest }?.first ?: ProfileRevision(epoch, ++counter)
        known[id] = revision to digest
        return StoredProfile(profile, revision, digest) to bytes
    }
    suspend fun load(): Pair<List<StoredProfile>, Boolean> = withContext(Dispatchers.IO) { synchronized(guard) {
        if (!rootValid) return@synchronized emptyList<StoredProfile>() to true
        var warning = false
        val files = directory.listFiles() ?: return@synchronized emptyList<StoredProfile>() to true
        val list = files.filter { it.name.endsWith(".conf") }.mapNotNull { file ->
            try { read(UUID.fromString(file.name.removeSuffix(".conf"))).first }
            catch (_: Exception) { warning = true; null }
        }.sortedWith(compareBy(java.text.Collator.getInstance()) { it.profile.name })
        list to warning
    } }
    /**
     * The current text, revision and digest of [id] from one read under [guard], classified like the
     * compare-and-save precondition: a missing file is [LatestProfile.Deleted], anything that cannot be
     * read or parsed is [LatestProfile.Unreadable].
     */
    suspend fun readLatest(id: UUID): LatestProfile = withContext(Dispatchers.IO) { synchronized(guard) {
        if (!rootValid) return@synchronized LatestProfile.Unreadable
        if (!java.nio.file.Files.exists(target(id).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            known.remove(id)
            return@synchronized LatestProfile.Deleted
        }
        try { LatestProfile.Present(read(id).first) } catch (_: Exception) { LatestProfile.Unreadable }
    } }
    suspend fun readForSession(id: String, stillValid: () -> Boolean): SessionInput = withContext(Dispatchers.IO) { synchronized(guard) {
        try {
            val uuid = UUID.fromString(id)
            require(uuid.toString() == id)
            val (stored, bytes) = read(uuid)
            val source = SSConfSnapshots.sourceOf(ConfigDocument.parse(stored.profile.text))
            val binding = source?.let(::SSConfBinding)
            SessionInput(uuid, bytes, stored.revision, stored.digest, binding,
                source?.let { SnapshotTicket(uuid, it, ++ticketSeq, stillValid) }, boundSnapshot(uuid, source))
        } catch (_: Exception) { throw ProfileError("INVALID_PROFILE") }
    } }
    /**
     * Persists [body] for the ticket's profile and source. Under [guard]: the profile must still exist
     * with the ticket's source (source ABA is accepted), no newer ticket may have committed, and
     * [SnapshotTicket.stillValid] must hold. Byte equality is never authority.
     * Precondition: [body] is 1..[SSConfSnapshots.MAX_BODY] bytes and was already accepted by Core.
     */
    suspend fun commitSnapshot(ticket: SnapshotTicket, body: ByteArray): SnapshotCommitResult {
        require(body.size in 1..SSConfSnapshots.MAX_BODY)
        val owned = body.copyOf()
        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            synchronized(guard) {
                val id = ticket.profileId
                if (!rootValid || !java.nio.file.Files.exists(target(id).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    return@synchronized SnapshotCommitResult.Conflict(ConflictReason.DELETED)
                }
                // An unreadable profile has no provable source.
                val source = runCatching { SSConfSnapshots.sourceOf(ConfigDocument.parse(read(id).first.profile.text)) }.getOrNull()
                if (source != ticket.source) return@synchronized SnapshotCommitResult.Conflict(ConflictReason.SOURCE_CHANGED)
                if (ticket.seq <= (lastCommittedSeq[id] ?: 0L)) return@synchronized SnapshotCommitResult.Conflict(ConflictReason.SUPERSEDED)
                if (!ticket.stillValid()) return@synchronized SnapshotCommitResult.Conflict(ConflictReason.STALE_TICKET)
                val savedAt = System.currentTimeMillis()
                try { writeSnapshot(id, SSConfSnapshots.encode(id, ticket.source, owned, savedAt)) }
                catch (_: Exception) { return@synchronized SnapshotCommitResult.NotPersisted }
                lastCommittedSeq[id] = ticket.seq
                SnapshotCommitResult.Committed(StoredSnapshot(owned, Sha256.of(owned), savedAt))
            }
        }
    }
    /**
     * Best effort: deletes the snapshot only if it decodes, is bound to the ticket's source and its body
     * digest equals [digest]. The ticket stays usable for a following [commitSnapshot].
     */
    suspend fun discardSnapshot(ticket: SnapshotTicket, digest: Sha256) {
        try {
            withContext(Dispatchers.IO) { synchronized(guard) {
                val id = ticket.profileId
                val file = snapshotFile(id)
                val decoded = readSnapshot(file)?.let { SSConfSnapshots.decodeBound(id, it) } ?: return@synchronized
                if (decoded.source == ticket.source && decoded.snapshot.digest == digest) deleteSnapshotFile(file)
            } }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) {}
    }
    private fun snapshotDirectory(): File {
        require(rootValid)
        val snapshots = File(directory.absoluteFile.parentFile, "ssconf")
        trustedRoot?.let { checkedBelow(it, snapshots) }
        require(!java.nio.file.Files.isSymbolicLink(snapshots.toPath()))
        return snapshots
    }
    private fun snapshotFile(id: UUID) = File(snapshotDirectory(), "$id.snap")
    /** Bytes of a regular snapshot file, or null when absent. Symlinks and other types throw. */
    private fun readSnapshot(file: File): ByteArray? {
        val path = file.toPath()
        if (!java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null
        require(java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        return file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= SSConfSnapshots.MAX_FILE) {
                val n = input.read(buffer, 0, minOf(buffer.size, SSConfSnapshots.MAX_FILE + 1 - output.size()))
                if (n < 0) break
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
    }
    private fun deleteSnapshotFile(file: File) {
        if (java.nio.file.Files.deleteIfExists(file.toPath())) syncDirectory(file.parentFile!!)
    }
    /** The decoded snapshot bound to [source]; a corrupt, foreign or stale file is deleted. Never throws. */
    private fun boundSnapshot(id: UUID, source: String?): StoredSnapshot? = runCatching {
        val file = snapshotFile(id)
        val bytes = readSnapshot(file) ?: return@runCatching null
        val decoded = SSConfSnapshots.decodeBound(id, bytes)
        if (decoded != null && source != null && decoded.source == source) return@runCatching decoded.snapshot
        deleteSnapshotFile(file)
        null
    }.getOrNull()
    /** Called under [guard] after a profile commit: keeps the snapshot only if it is still bound to [source]. */
    private fun retainSnapshotFor(id: UUID, source: String?) { boundSnapshot(id, source) }
    private fun writeSnapshot(id: UUID, bytes: ByteArray) {
        val snapshots = snapshotDirectory()
        if (!java.nio.file.Files.exists(snapshots.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            java.nio.file.Files.createDirectory(snapshots.toPath(), PRIVATE_DIRECTORY)
            syncDirectory(snapshots.parentFile)
        }
        require(java.nio.file.Files.isDirectory(snapshots.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        java.nio.file.Files.setPosixFilePermissions(snapshots.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        val suffix = java.security.SecureRandom().nextLong().toULong().toString(16)
        val temp = File(snapshots, "$id.snap.tmp-$suffix")
        try {
            java.nio.file.Files.createFile(temp.toPath(), PRIVATE_FILE)
            java.nio.file.Files.setPosixFilePermissions(temp.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
            java.io.FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            moveSnapshot(temp, File(snapshots, "$id.snap"))
        } finally { java.nio.file.Files.deleteIfExists(temp.toPath()) }
        syncDirectory(snapshots)
    }
    suspend fun committedReceipt(requestId: UUID): CommitReceipt? = withContext(Dispatchers.IO) {
        synchronized(guard) { receipts[requestId]?.second }
    }
    private fun replay(requestId: UUID, request: Request): CommitResult? {
        val previous = receipts[requestId] ?: return null
        require(previous.first == request) { "REQUEST_ID_REUSED" }
        return CommitResult.Committed(previous.second)
    }
    private fun conflict(id: UUID, expected: ProfileRevision?): ConflictReason? {
        if (!rootValid) return ConflictReason.UNREADABLE
        if (!java.nio.file.Files.exists(target(id).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            known.remove(id)
            return if (expected == null) null else ConflictReason.DELETED
        }
        val current = try { read(id).first } catch (_: Exception) { return ConflictReason.UNREADABLE }
        return when { expected == null -> ConflictReason.EXISTS; current.revision != expected -> ConflictReason.CHANGED; else -> null }
    }
    private fun syncDirectory(target: File = directory) { runCatching {
        java.nio.channels.FileChannel.open(target.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
    } }
    /**
     * Save-time validation of [text] as profile [id]; [compareAndSave] runs it for every save, before [guard].
     * Import placeholders of [imports] (a draft of [id]) are resolved first, so they are checked as the
     * published generations they become. Decision 8: every SSH file reference must be one of [id]'s own
     * imported files under the trusted root ([SshFileGuard.insideApp], the check the service repeats at
     * connect time). Pure, apart from the resolve and Core validation.
     */
    suspend fun validate(text: String, id: UUID, imports: SshDraftResources? = null): Unit = withContext(Dispatchers.IO) {
        require(imports == null || imports.profileId == id)
        val text = imports?.resolve(text, resources) ?: text
        val normalized = Conf.read(text.toByteArray(StandardCharsets.UTF_8))
        val document = ConfigDocument.parse(normalized)
        document.validate()
        if (document.source()?.first == "File") throw ProfileError(R.string.profile_error_embed_key_file)
        if (!SshFileGuard.insideApp(normalized, id, sshFilesRoot)) throw ProfileError(R.string.ssh_files_not_imported)
        validateCore(normalized)
        document.rules.forEachIndexed { index, rule ->
            if (rule.disabled) validateCore(document.enableRule(index, true).render())
        }
    }
    /**
     * The root SSH imports live under (`<root>/ssh-resources`): the trusted files root; a test store
     * without a Context uses the parent of its profile directory, like [trustedHostStore]. Null (refuse
     * every reference) for an untrusted root.
     */
    private val sshFilesRoot: File? = trustedRoot ?: if (context == null) directory.absoluteFile.parentFile else null
    /**
     * Outside text (import, link, added key) prepared for creating profile [id] before its [compareAndSave]:
     * SSH file fields that are not [id]'s imports are cleared ([SshFileGuard.forNewProfile]). Pure.
     */
    internal fun prepareNewProfile(text: String, id: UUID): SshFileGuard.NewProfileText = SshFileGuard.forNewProfile(text, id, sshFilesRoot)
    private fun validateCore(text: String) {
        coreValidation?.let { it(text); return }
        try {
            val session = Androidcore.newSession(text.toByteArray(StandardCharsets.UTF_8), object : SocketProtector { override fun protect(fd: Long) = false })
            session.stop()
        } catch (error: Exception) {
            val line = Regex("at line (\\d+)").find(error.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            throw if (line != null) ProfileError(R.string.profile_error_core_rejected_line, line)
            else ProfileError(R.string.profile_error_core_rejected)
        }
    }
    suspend fun compareAndSave(requestId: UUID, id: UUID, expected: ProfileRevision?, text: String,
        draft: SshDraftResources? = null): CommitResult = withContext(Dispatchers.IO) {
        if (!rootValid) return@withContext CommitResult.Conflict(ConflictReason.UNREADABLE)
        val request = Request(id, CommitReceipt.Kind.SAVED, expected, text, draft)
        synchronized(guard) { replay(requestId, request) }?.let { return@withContext it }
        require(draft == null || draft.profileId == id)
        val normalized = draft?.resolve(text, resources) ?: text
        validate(normalized, id)
        val bytes = normalized.toByteArray(StandardCharsets.UTF_8)
        val digest = Sha256.of(bytes)
        val temp = File.createTempFile("profile-", ".tmp", directory)
        try {
            java.nio.file.Files.setPosixFilePermissions(temp.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
            java.io.FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            currentCoroutineContext().ensureActive()
            synchronized(guard) {
                replay(requestId, request)?.let { return@synchronized it }
                conflict(id, expected)?.let { return@synchronized CommitResult.Conflict(it) }
                val commit = {
                    if (!renameProfile(temp, target(id))) throw ProfileError(R.string.profile_save_failed)
                    val revision = ProfileRevision(epoch, ++counter)
                    val receipt = CommitReceipt(requestId, id, CommitReceipt.Kind.SAVED, revision, digest)
                    receipts[requestId] = request to receipt
                    known[id] = revision to digest
                    draft?.saved(normalized)
                    syncDirectory()
                    retainSnapshotFor(id, runCatching { SSConfSnapshots.sourceOf(ConfigDocument.parse(normalized)) }.getOrNull())
                    runCatching { resources.afterSaved(id, SshDraftResources.paths(normalized)) }
                    CommitResult.Committed(receipt)
                }
                if (draft == null) commit() else draft.commit(commit)
            }
        } finally { temp.delete() }
    }
    suspend fun compareAndDelete(requestId: UUID, id: UUID, expected: ProfileRevision): CommitResult = withContext(Dispatchers.IO) {
        val request = Request(id, CommitReceipt.Kind.DELETED, expected, null, null)
        currentCoroutineContext().ensureActive()
        synchronized(guard) {
            replay(requestId, request)?.let { return@synchronized it }
            conflict(id, expected)?.let { return@synchronized CommitResult.Conflict(it) }
            if (!target(id).delete()) throw ProfileError(R.string.profile_delete_failed)
            val receipt = CommitReceipt(requestId, id, CommitReceipt.Kind.DELETED, null, null)
            receipts[requestId] = request to receipt
            known.remove(id)
            syncDirectory()
            runCatching { deleteSnapshotFile(snapshotFile(id)) }
            runCatching { resources.afterDeleted(id) }
            runCatching { trustedHosts().forget(id) }
            CommitResult.Committed(receipt)
        }
    }
    /** App-owned accept-new SSH host keys (CONTRACT.md, SSH host keys); every call holds [guard]. */
    private val trustedHostStore = TrustedHostKeys(File(directory.absoluteFile.parentFile, TrustedHostKeys.DIRECTORY))
    private fun trustedHosts(): TrustedHostKeys {
        require(rootValid)
        trustedRoot?.let { checkedBelow(it, trustedHostStore.directory) }
        return trustedHostStore
    }
    /** Stored host-key lines of [id] for `Session.acceptNewHostKeys`; empty when none. Throws on failure. */
    suspend fun trustedHostKeys(id: UUID): ByteArray = withContext(Dispatchers.IO) { synchronized(guard) { trustedHosts().read(id) } }
    /** 0 for a profile that no longer exists (for example a draft whose profile was deleted elsewhere). */
    suspend fun trustedHostKeyCount(id: UUID): Int = withContext(Dispatchers.IO) { synchronized(guard) {
        if (!java.nio.file.Files.exists(target(id).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) 0 else trustedHosts().count(id)
    } }
    suspend fun forgetTrustedHostKeys(id: UUID): Unit = withContext(Dispatchers.IO) { synchronized(guard) { trustedHosts().forget(id) } }
    /**
     * Blocking, off Main: persists a host-key line the user trusted. Null when [id] no longer exists, so a
     * profile deleted during its session gets no file back. Throws on an I/O failure.
     *
     * Takes [guard], so lock order (guard → draft → resources → gate) forbids calling it with the
     * `VpnLive.gate` monitor held: never under `synchronized(VpnLive.gate)` or `gate.commit {}`, never from a
     * `VpnLive.publish*` or the endpoint's gate check. The only caller is `AuthAttempt.deliver` on IO, after
     * Core accepted the reply, with no lock held. A violation fails closed here instead of deadlocking.
     */
    internal fun rememberTrustedHostKey(id: UUID, line: String): TrustedHostKeys.Append? {
        check(!Thread.holdsLock(VpnLive.gate)) { "Lock order" }
        return synchronized(guard) {
            if (!rootValid || !java.nio.file.Files.exists(target(id).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) null
            else trustedHosts().append(id, line)
        }
    }
    // Transitional callers capture the current revision at their operation boundary, not editor-open time.
    suspend fun save(text: String, id: String? = null, imports: SshDraftResources? = null): Profile {
        val uuid = id?.let(UUID::fromString) ?: imports?.profileId ?: UUID.randomUUID()
        val expected = withContext(Dispatchers.IO) { synchronized(guard) {
            runCatching { read(uuid).first.revision }.getOrNull()
        } }
        return when (val result = compareAndSave(UUID.randomUUID(), uuid, expected, text, imports)) {
            is CommitResult.Committed -> withContext(NonCancellable + Dispatchers.IO) { synchronized(guard) { read(uuid).first.profile } }
            is CommitResult.Conflict -> throw ProfileError(result.reason.name)
        }
    }
    suspend fun delete(id: String) {
        val uuid = UUID.fromString(id)
        val expected = withContext(Dispatchers.IO) { synchronized(guard) {
            try { read(uuid).first.revision } catch (_: Exception) { throw ProfileError("UNREADABLE") }
        } }
        when (val result = compareAndDelete(UUID.randomUUID(), uuid, expected)) {
            is CommitResult.Committed -> Unit
            is CommitResult.Conflict -> throw ProfileError(result.reason.name)
        }
    }
    suspend fun readDocument(uri: Uri): String = withContext(Dispatchers.IO) {
        val bytes = requireNotNull(context).contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= 65536) { val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
            output.toByteArray()
        } ?: throw ProfileError(R.string.profile_error_open_document)
        Conf.read(bytes)
    }
    fun documentName(uri: Uri): String = requireNotNull(context).contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: requireNotNull(context).getString(R.string.profile_default_name)
}
