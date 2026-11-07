package org.bimeron.app

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

/** Synthetic SSH host-key offers. Key blobs are well-formed wire encodings, not real server keys. */
internal object HostKeyFixtures {
    const val SESSION = AuthTestSupport.SESSION
    const val REQUEST_A = AuthTestSupport.REQUEST_A
    const val REQUEST_B = AuthTestSupport.REQUEST_B

    /** `string type || string key` with [seed]-derived key bytes. */
    fun blob(type: String = "ssh-ed25519", seed: Int = 1, keyBytes: Int = 32): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(8 + typeBytes.size + keyBytes).putInt(typeBytes.size).put(typeBytes)
            .putInt(keyBytes).put(ByteArray(keyBytes) { (it * 31 + seed).toByte() }).array()
    }

    fun base64(blob: ByteArray): String = Base64.getEncoder().encodeToString(blob)
    fun fingerprint(blob: ByteArray): String =
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

    /** OpenSSH known_hosts host for a dialed `host:port` (bare for port 22). */
    fun lineHost(host: String): String {
        val name: String; val port: String
        if (host.startsWith("[")) { name = host.substring(1, host.indexOf("]:")); port = host.substringAfter("]:") }
        else { name = host.substringBeforeLast(':'); port = host.substringAfterLast(':') }
        return if (port == "22") name else "[$name]:$port"
    }

    fun offer(host: String = "ssh.example.com:2222", type: String = "ssh-ed25519", seed: Int = 1): SshAuthenticationController.HostKeyOffer {
        val blob = blob(type, seed)
        return SshAuthenticationController.HostKeyOffer(host, type, fingerprint(blob), "${lineHost(host)} $type ${base64(blob)}")
    }

    fun line(host: String = "ssh.example.com:2222", type: String = "ssh-ed25519", seed: Int = 1) = offer(host, type, seed).knownHostsLine

    fun fields(offer: SshAuthenticationController.HostKeyOffer, requestId: String = REQUEST_A, session: String = SESSION): LinkedHashMap<String, Any?> =
        linkedMapOf("sessionId" to session, "requestId" to requestId, "kind" to "host_key", "host" to offer.host,
            "keyType" to offer.keyType, "fingerprint" to offer.fingerprint, "knownHostsLine" to offer.knownHostsLine)

    fun wire(offer: SshAuthenticationController.HostKeyOffer = offer(), requestId: String = REQUEST_A, session: String = SESSION) =
        json(fields(offer, requestId, session))

    /** Minimal JSON writer for flat objects of strings, booleans, null and string lists. */
    fun json(values: Map<String, Any?>): String = values.entries.joinToString(",", "{", "}") { (k, v) -> "${quote(k)}:${value(v)}" }
    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is String -> quote(v)
        is Boolean -> v.toString()
        is List<*> -> v.joinToString(",", "[", "]") { value(it) }
        else -> error("unsupported")
    }
    private fun quote(s: String) = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\""); c == '\\' -> append("\\\\")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}
