package org.bimeron.app

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ServiceSessionInputTest {
    @get:Rule val temp = TemporaryFolder()
    @After fun reset() { VpnLive.resetAfterProcessStart() }

    @Test fun serviceReadRetainsExactOwnedBytesAndRealTicket() = runBlocking {
        val directory = temp.newFolder()
        val store = ProfileStore(null, directory, SshProfileResources(temp.newFolder())) {}
        val id = UUID.randomUUID()
        val text = Conf.fromKey("A", "ssconf://example.com/config")
        store.compareAndSave(UUID.randomUUID(), id, null, text, null)
        val file = File(directory, "$id.conf")
        val expected = file.readBytes()
        val token = VpnLive.gate.next()
        val input = readSessionInput(store, id.toString(), token)
        assertEquals(id, input.id)
        assertEquals(store.load().first.single().revision, input.revision)
        assertEquals(Sha256.of(expected), input.digest)
        assertEquals("ssconf://example.com/config", input.ssconf!!.source)
        assertEquals(id, input.ticket!!.profileId)
        assertTrue(input.ticket.stillValid())
        assertNull(input.snapshot)
        file.writeText("changed after read")
        val bytesForNative = input.bytes
        assertArrayEquals(expected, bytesForNative)
        bytesForNative.fill(0)
        assertArrayEquals(expected, input.bytes)
        VpnLive.gate.next()
        assertFalse(input.ticket.stillValid())
        try { ensureSessionAttempt(token); fail("stale accepted") } catch (_: CancellationException) {}
    }

    @Test fun invalidReadFailsClosedAndStaleAttemptIsCancellation() = runBlocking {
        val store = ProfileStore(null, temp.newFolder(), SshProfileResources(temp.newFolder())) {}
        val token = VpnLive.gate.next()
        try { readSessionInput(store, UUID.randomUUID().toString(), token); fail() }
        catch (e: ProfileError) { assertEquals("INVALID_PROFILE", e.message) }
        VpnLive.gate.next()
        try { readSessionInput(store, "invalid", token); fail() }
        catch (_: CancellationException) {}
    }
}
