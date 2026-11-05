package org.bimeron.app

import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class ProfileExportTest {
    @Test fun exactUtf8AndClose() {
        var closed = false
        val stream = object : ByteArrayOutputStream() {
            override fun close() { closed = true; super.close() }
        }
        val text = "# Grüße\r\nKey = secret\n"
        writeProfileExport(text) { stream }
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), stream.toByteArray())
        assertTrue(closed)
    }

    @Test fun failuresCloseAndPropagate() {
        var closed = false
        val stream = object : java.io.OutputStream() {
            override fun write(value: Int) { throw IOException("secret") }
            override fun close() { closed = true }
        }
        assertThrows(IOException::class.java) { writeProfileExport("key") { stream } }
        assertTrue(closed)
        assertThrows(IOException::class.java) { writeProfileExport("key") { null } }
        assertThrows(IOException::class.java) {
            writeProfileExport("key") { object : ByteArrayOutputStream() {
                override fun close() { throw IOException() }
            } }
        }
    }

    @Test fun duplicateRequestsAndCancellation() {
        val export = ProfileExport()
        export.request("first")
        export.request("second")
        val field = ProfileExport::class.java.getDeclaredField("snapshot").apply { isAccessible = true }
        assertEquals("first", field.get(export))
        assertTrue(export.confirm())
        assertFalse(export.confirm())
        export.cancel()
        assertNull(field.get(export))
        assertFalse(export.busy)
        assertFalse(export.confirm())
        export.fail()
        assertEquals(R.string.export_failure, export.message)
    }
}
