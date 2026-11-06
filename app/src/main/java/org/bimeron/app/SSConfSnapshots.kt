package org.bimeron.app

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** Result of [ProfileStore.commitSnapshot]. */
sealed interface SnapshotCommitResult {
    data class Committed(val snapshot: StoredSnapshot) : SnapshotCommitResult
    /** I/O failure; the caller continues with the in-memory body and logs SNAPSHOT_NOT_PERSISTED. */
    data object NotPersisted : SnapshotCommitResult
    /** SOURCE_CHANGED, DELETED, SUPERSEDED or STALE_TICKET. */
    data class Conflict(val reason: ConflictReason) : SnapshotCommitResult
}

/**
 * Source identity and the binary v1 snapshot codec (`ssconf/<uuid>.snap`):
 * magic "BSN1", version 1, UUID (msb, lsb), u32 sourceLen 1..8192, UTF-8 source,
 * u32 bodyLen 1..65536, body, i64 savedAt, SHA-256 over all preceding bytes. All integers big-endian.
 */
internal object SSConfSnapshots {
    private val MAGIC = byteArrayOf(0x42, 0x53, 0x4E, 0x31)
    private const val VERSION: Byte = 1
    const val MAX_SOURCE = 8192
    const val MAX_BODY = 65536
    private const val HEADER = 4 + 1 + 16 + 4
    private const val HASH = 32
    const val MAX_FILE = HEADER + MAX_SOURCE + 4 + MAX_BODY + 8 + HASH

    /** A decoded file together with the source it is bound to. */
    class Decoded(val source: String, val snapshot: StoredSnapshot)

    /** Exact source identity used by Core: TrimSpace of AccessKey.SSConf, else AccessKey.Key; case-sensitive "ssconf://" prefix. */
    fun sourceOf(document: ConfigDocument): String? {
        val raw = document.value("AccessKey", "SSConf").ifEmpty { document.value("AccessKey", "Key") }
        // Go strings.TrimSpace uses Unicode White_Space, excluding Java's extra control separators.
        return raw.trim { it in '\u0009'..'\u000d' || it == ' ' || it == '\u0085' || it == '\u00a0' || it == '\u1680' || it in '\u2000'..'\u200a' || it == '\u2028' || it == '\u2029' || it == '\u202f' || it == '\u205f' || it == '\u3000' }
            .takeIf { it.startsWith("ssconf://") }
    }

    fun encode(id: UUID, source: String, body: ByteArray, savedAt: Long): ByteArray {
        val sourceBytes = strictUtf8(source)
        require(sourceBytes.size in 1..MAX_SOURCE)
        require(body.size in 1..MAX_BODY)
        val buffer = ByteBuffer.allocate(HEADER + sourceBytes.size + 4 + body.size + 8 + HASH)
        buffer.put(MAGIC).put(VERSION).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits)
            .putInt(sourceBytes.size).put(sourceBytes).putInt(body.size).put(body).putLong(savedAt)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(buffer.array(), 0, buffer.position())
        buffer.put(digest.digest())
        check(!buffer.hasRemaining())
        return buffer.array()
    }

    fun decode(id: UUID, bytes: ByteArray): StoredSnapshot? = decodeBound(id, bytes)?.snapshot

    /** Null for anything but an exact, hash-verified v1 file for [id]. */
    fun decodeBound(id: UUID, bytes: ByteArray): Decoded? {
        if (bytes.size < HEADER + 1 + 4 + 1 + 8 + HASH || bytes.size > MAX_FILE) return null
        val content = bytes.size - HASH
        val digest = MessageDigest.getInstance("SHA-256").apply { update(bytes, 0, content) }.digest()
        if (!MessageDigest.isEqual(digest, bytes.copyOfRange(content, bytes.size))) return null
        val buffer = ByteBuffer.wrap(bytes, 0, content)
        val magic = ByteArray(4).also { buffer.get(it) }
        if (!magic.contentEquals(MAGIC) || buffer.get() != VERSION) return null
        if (UUID(buffer.long, buffer.long) != id) return null
        val sourceLen = buffer.int
        if (sourceLen !in 1..MAX_SOURCE || sourceLen > buffer.remaining() - 4 - 1 - 8) return null
        val sourceBytes = ByteArray(sourceLen).also { buffer.get(it) }
        val source = try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(sourceBytes)).toString()
        } catch (_: Exception) { return null }
        val bodyLen = buffer.int
        if (bodyLen !in 1..MAX_BODY || bodyLen.toLong() + 8 != buffer.remaining().toLong()) return null
        val body = ByteArray(bodyLen).also { buffer.get(it) }
        val savedAt = buffer.long
        if (buffer.hasRemaining()) return null
        return Decoded(source, StoredSnapshot(body, Sha256.of(body), savedAt))
    }

    private fun strictUtf8(value: String): ByteArray {
        val encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        return ByteArray(encoded.remaining()).also { encoded.get(it) }
    }
}
