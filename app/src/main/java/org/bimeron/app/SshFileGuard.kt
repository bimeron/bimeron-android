package org.bimeron.app

import java.io.File
import java.net.URLDecoder
import java.util.UUID

/**
 * Decision 8 (core ADR 0036 on the app side): an Android SSH session may only use files imported into this
 * profile, `<filesRoot>/ssh-resources/profiles/<profile uuid>/<generation uuid>/{identity|known_hosts}`.
 * The service checks every session input before any native session; a typed path such as `/sdcard/...`,
 * a `~` path, another profile's file or the wrong kind of file is refused. Form and save validation belong
 * to the editor and the profile store; this guard is the enforcement point that every start passes.
 */
internal object SshFileGuard {
    /**
     * Every SSH file reference Core would open for [text], with the kind it must be: the `[AccessKey]`
     * `IdentityFile`/`KnownHostsFile` fields, and the `identity`/`known_hosts` query parameters of any
     * `ssh://` value (`core: internal/accesskey/accesskey.go:159`). Keys match case-insensitively and every
     * query value is collected, a superset of what Core reads. Null when [text] cannot be inspected.
     */
    fun references(text: String): List<Pair<SshProfileResources.Kind, String>>? = runCatching {
        val access = ConfigDocument.parse(text).values("AccessKey")
        val found = mutableListOf<Pair<SshProfileResources.Kind, String>>()
        access.forEach { (key, raw) ->
            val value = coreTrim(raw)
            FIELDS.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.let { field ->
                if (value.isNotEmpty()) found += field.value to value
            }
            if (value.startsWith("ssh://", ignoreCase = true)) found += queryReferences(value) ?: return null
        }
        found
    }.getOrNull()

    /** True when every reference in [input] is one of its own profile's imported files under [filesRoot]. */
    fun insideApp(input: SessionInput, filesRoot: File?): Boolean {
        val text = runCatching { Conf.read(input.bytes) }.getOrNull() ?: return false
        return insideApp(text, input.id, filesRoot)
    }

    /**
     * True when every reference in [text] (already normalized like stored bytes read by `Conf.read`) is one
     * of profile [id]'s imported files under [filesRoot]. The service check above delegates here; the
     * profile store runs the same check at save time.
     */
    fun insideApp(text: String, id: UUID, filesRoot: File?): Boolean {
        val references = references(text) ?: return false
        if (references.isEmpty()) return true
        val profile = File(filesRoot ?: return false, "ssh-resources/profiles/$id")
        return references.all { (kind, path) -> owned(profile, kind, path) }
    }

    private fun owned(profile: File, kind: SshProfileResources.Kind, path: String): Boolean {
        val file = File(path)
        if (!file.isAbsolute || file.toPath().normalize().toString() != path || file.name != kind.filename) return false
        val generation = file.parentFile ?: return false
        return generation.parentFile == profile &&
            runCatching { UUID.fromString(generation.name).toString() == generation.name }.getOrDefault(false)
    }

    private fun queryReferences(uri: String): List<Pair<SshProfileResources.Kind, String>>? {
        val query = uri.substringBefore('#').substringAfter('?', "")
        if (query.isEmpty()) return emptyList()
        return query.split('&', ';').mapNotNull { pair ->
            val key = runCatching { URLDecoder.decode(pair.substringBefore('='), "UTF-8") }.getOrElse { return null }
            val kind = QUERY.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value ?: return@mapNotNull null
            val value = runCatching { URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }.getOrElse { return null }
            value.takeIf { it.isNotEmpty() }?.let { kind to it }
        }
    }

    private val FIELDS = mapOf("IdentityFile" to SshProfileResources.Kind.IDENTITY, "KnownHostsFile" to SshProfileResources.Kind.KNOWN_HOSTS)
    private val QUERY = mapOf("identity" to SshProfileResources.Kind.IDENTITY, "known_hosts" to SshProfileResources.Kind.KNOWN_HOSTS)
}

/** Service guard: throws [VpnErrorCode.SSH_FILE_OUTSIDE_APP] unless [SshFileGuard.insideApp] holds. */
internal fun requireSshFilesInsideApp(input: SessionInput, filesRoot: File?) {
    if (!SshFileGuard.insideApp(input, filesRoot)) throw BimeronVpnService.SessionFailure(VpnErrorCode.SSH_FILE_OUTSIDE_APP)
}
