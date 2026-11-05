package org.bimeron.app

import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GeoDataStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val validator = GeoDataValidator { _, file ->
        val text = file.readText()
        if (text == "invalid") throw IOException("format")
        if (text == "catalog") emptyList() else listOf(text)
    }
    private fun expectFailure(block: () -> Unit) {
        try { block(); fail("Expected failure") } catch (_: IOException) { }
    }
    @Test fun installReplaceIndependentAndRestart() {
        val store = GeoDataStore(folder.root, validator)
        assertEquals(listOf("old"), store.install(GeoDataKind.GEOIP, "old".byteInputStream()))
        store.install(GeoDataKind.GEOSITE, "site".byteInputStream())
        store.install(GeoDataKind.GEOIP, "new".byteInputStream())
        assertEquals(listOf("new"), GeoDataStore(folder.root, validator).groups(GeoDataKind.GEOIP))
        assertEquals(listOf("site"), store.groups(GeoDataKind.GEOSITE))
    }
    @Test fun failuresPreserveBytesCatalogAndCleanTemporaryFiles() {
        val store = GeoDataStore(folder.root, validator, 8)
        store.install(GeoDataKind.GEOIP, "old".byteInputStream())
        val badInputs = listOf("", "123456789", "invalid", "catalog").map { it.byteInputStream() } +
            object : InputStream() { override fun read(): Int = throw IOException("read") }
        for (input in badInputs) {
            expectFailure { store.install(GeoDataKind.GEOIP, input) }
            assertEquals("old", folder.root.resolve("geodata/geoip.dat").readText())
            assertEquals(listOf("old"), store.groups(GeoDataKind.GEOIP))
            assertEquals(listOf("geoip.dat"), folder.root.resolve("geodata").list()!!.toList())
        }
        store.publish = { _, _ -> throw IOException("atomic publish") }
        expectFailure { store.install(GeoDataKind.GEOIP, "new".byteInputStream()) }
        assertEquals(listOf("old"), store.groups(GeoDataKind.GEOIP))
        assertEquals("old", folder.root.resolve("geodata/geoip.dat").readText())
        assertEquals(1, folder.root.resolve("geodata").list()!!.size)
    }
    @Test fun inputRemainsCallerOwned() {
        var closed = false
        val input = object : java.io.ByteArrayInputStream("group".toByteArray()) {
            override fun close() { closed = true; super.close() }
        }
        GeoDataStore(folder.root, validator).install(GeoDataKind.GEOIP, input)
        assertFalse(closed)
    }
    @Test fun unsupportedFailsClosed() {
        val store = GeoDataStore(folder.root, GeoDataCoreAdapter)
        try { store.install(GeoDataKind.GEOIP, "data".byteInputStream()); fail() }
        catch (_: UnsupportedOperationException) { }
        assertNull(store.groups(GeoDataKind.GEOIP))
        assertEquals(0, folder.root.resolve("geodata").list()!!.size)
    }
}
