package org.bimeron.app

import org.junit.Assert.*
import org.junit.Test

class ConfigDocumentPresenceTest {
    private val text = "# header\n[AccessKey]\nType = vless\nEmpty =\nFuture = \"a=b\"\n; keep\n[ShadowTLS]\nPassword = secret\n[Interface]\nMode = vpn\n# footer\n"
    @Test fun presenceAndTargetedRemoval() {
        val d = ConfigDocument.parse(text)
        assertEquals("", d.values("AccessKey")["Empty"])
        assertNull(d.values("AccessKey")["Absent"])
        assertTrue(d.values("Absent").isEmpty())
        assertEquals(text, d.remove("AccessKey", "Absent").render())
        assertEquals(text, d.remove("Absent", "Future").render())
        assertEquals(text.replace("Empty =\n", ""), d.remove("AccessKey", "Empty").render())
        assertEquals("a=b", d.remove("AccessKey", "Empty").values("AccessKey")["Future"])
    }
    @Test fun removesOnlyRequestedSectionAndAbsentIsNoOp() {
        val d = ConfigDocument.parse(text)
        assertEquals(text, d.removeSection("Absent").render())
        assertEquals(text.replace("[ShadowTLS]\nPassword = secret\n", ""), d.removeSection("ShadowTLS").render())
        assertEquals(text.replace("[AccessKey]\nType = vless\nEmpty =\nFuture = \"a=b\"\n; keep\n", ""), d.removeSection("AccessKey").render())
    }
}
