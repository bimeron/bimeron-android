package org.bimeron.app

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class SnapshotCodecTest {
    private val id = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0")
    private val source = "ssconf://example.com/path?token=é"
    private val body = "{\"server\":\"example\"}".toByteArray()
    private val encoded = SSConfSnapshots.encode(id, source, body, 1_700_000_000_123L)

    /** Rewrites the trailer so only the targeted field is wrong. */
    private fun rehash(bytes: ByteArray): ByteArray {
        val content = bytes.size - 32
        val digest = MessageDigest.getInstance("SHA-256").apply { update(bytes, 0, content) }.digest()
        return bytes.copyOf().also { digest.copyInto(it, content) }
    }

    @Test fun roundTripPreservesExactFieldsAndLayout() {
        val decoded = SSConfSnapshots.decodeBound(id, encoded)!!
        assertEquals(source, decoded.source)
        assertArrayEquals(body, decoded.snapshot.body)
        assertEquals(Sha256.of(body), decoded.snapshot.digest)
        assertEquals(1_700_000_000_123L, decoded.snapshot.savedAt)
        assertArrayEquals(body, SSConfSnapshots.decode(id, encoded)!!.body)
        val sourceBytes = source.toByteArray()
        assertEquals(4 + 1 + 16 + 4 + sourceBytes.size + 4 + body.size + 8 + 32, encoded.size)
        val buffer = ByteBuffer.wrap(encoded)
        assertArrayEquals("BSN1".toByteArray(), ByteArray(4).also { buffer.get(it) })
        assertEquals(1.toByte(), buffer.get())
        assertEquals(id, UUID(buffer.long, buffer.long))
        assertEquals(sourceBytes.size, buffer.int)
    }

    @Test fun boundaryLengthsRoundTrip() {
        val longSource = "ssconf://" + "a".repeat(SSConfSnapshots.MAX_SOURCE - 9)
        val bigBody = ByteArray(SSConfSnapshots.MAX_BODY) { it.toByte() }
        val bytes = SSConfSnapshots.encode(id, longSource, bigBody, -1)
        assertEquals(SSConfSnapshots.MAX_FILE, bytes.size)
        assertEquals(longSource, SSConfSnapshots.decodeBound(id, bytes)!!.source)
        assertArrayEquals(byteArrayOf(7), SSConfSnapshots.decode(id, SSConfSnapshots.encode(id, "s", byteArrayOf(7), 0))!!.body)
    }

    @Test fun encodeRejectsOutOfRangeInput() {
        for (block in listOf<() -> Unit>(
            { SSConfSnapshots.encode(id, "", body, 0) },
            { SSConfSnapshots.encode(id, "s".repeat(SSConfSnapshots.MAX_SOURCE + 1), body, 0) },
            { SSConfSnapshots.encode(id, source, ByteArray(0), 0) },
            { SSConfSnapshots.encode(id, source, ByteArray(SSConfSnapshots.MAX_BODY + 1), 0) },
            { SSConfSnapshots.encode(id, "ssconf://\uD800", body, 0) },
        )) {
            try { block(); fail("accepted") } catch (_: Exception) {}
        }
    }

    @Test fun everySingleByteFlipIsRejected() {
        for (index in encoded.indices) for (mask in listOf(0x01, 0x80, 0xff)) {
            val corrupt = encoded.copyOf().also { it[index] = (it[index].toInt() xor mask).toByte() }
            assertNull("byte $index mask $mask", SSConfSnapshots.decodeBound(id, corrupt))
        }
    }

    @Test fun everyTruncationAndTrailingBytesAreRejected() {
        for (size in 0 until encoded.size) assertNull("size $size", SSConfSnapshots.decode(id, encoded.copyOf(size)))
        assertNull(SSConfSnapshots.decode(id, encoded + 0))
        // Trailing bytes stay rejected even when the trailer hash covers them.
        assertNull(SSConfSnapshots.decode(id, rehash(encoded.copyOfRange(0, encoded.size - 32) + byteArrayOf(0) + ByteArray(32))))
    }

    @Test fun wrongUuidVersionMagicAndLengthsAreRejectedEvenWithValidHash() {
        assertNull(SSConfSnapshots.decode(UUID.randomUUID(), encoded))
        assertNull(SSConfSnapshots.decode(id, rehash(encoded.copyOf().also { it[4] = 2 })))
        assertNull(SSConfSnapshots.decode(id, rehash(encoded.copyOf().also { it[3] = '2'.code.toByte() })))
        val otherId = UUID.randomUUID()
        val foreign = rehash(encoded.copyOf().also {
            ByteBuffer.wrap(it, 5, 16).putLong(otherId.mostSignificantBits).putLong(otherId.leastSignificantBits)
        })
        assertNull(SSConfSnapshots.decode(id, foreign)); assertNotNull(SSConfSnapshots.decode(otherId, foreign))
        assertNull(SSConfSnapshots.decode(id, rehash(encoded.copyOf().also { ByteBuffer.wrap(it).putInt(21, 0) })))
        assertNull(SSConfSnapshots.decode(id, rehash(encoded.copyOf().also { ByteBuffer.wrap(it).putInt(21, SSConfSnapshots.MAX_SOURCE + 1) })))
        val bodyLenAt = 25 + source.toByteArray().size
        assertNull(SSConfSnapshots.decode(id, rehash(encoded.copyOf().also { ByteBuffer.wrap(it).putInt(bodyLenAt, 0) })))
        assertNull(SSConfSnapshots.decode(id, rehash(encoded.copyOf().also { ByteBuffer.wrap(it).putInt(bodyLenAt, body.size - 1) })))
        assertNull(SSConfSnapshots.decode(id, rehash(encoded.copyOf().also { ByteBuffer.wrap(it).putInt(bodyLenAt, -1) })))
    }

    @Test fun invalidUtf8SourceIsRejected() {
        for (invalid in listOf(byteArrayOf(0xc0.toByte(), 0xaf.toByte()), byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()), byteArrayOf(0xff.toByte(), 0x41))) {
            val good = SSConfSnapshots.encode(id, "x".repeat(invalid.size), body, 0)
            val bytes = rehash(good.copyOf().also { invalid.copyInto(it, 25) })
            assertNull(SSConfSnapshots.decode(id, bytes))
        }
    }

    @Test fun sourceOfMatchesCoreIdentity() {
        fun doc(line: String) = ConfigDocument.parse("[Profile]\nVersion = 1\nName = A\n\n[AccessKey]\n$line\n")
        assertEquals("ssconf://h/p", SSConfSnapshots.sourceOf(doc("SSConf = ssconf://h/p")))
        assertEquals("ssconf://h/p", SSConfSnapshots.sourceOf(doc("Key = ssconf://h/p")))
        assertEquals("ssconf://h/p", SSConfSnapshots.sourceOf(doc("SSConf = \"\\u3000ssconf://h/p\\u00a0\"")))
        // Java trims U+001C, Go TrimSpace does not: the prefix check must then fail.
        assertNull(SSConfSnapshots.sourceOf(doc("SSConf = \"\\u001cssconf://h/p\"")))
        assertNull(SSConfSnapshots.sourceOf(doc("SSConf = SSCONF://h/p")))
        assertNull(SSConfSnapshots.sourceOf(doc("SS = ss://YWVzLTEyOC1nY206cGFzcw@example.com:443")))
    }
}
