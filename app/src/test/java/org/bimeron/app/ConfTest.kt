package org.bimeron.app

import org.junit.Assert.*
import org.junit.Test

class ConfTest {
    @Test fun keyAndRenamePreserveComments() {
        val text = Conf.fromKey("Première", "ssconf://example.com/key#Café").replace("[AccessKey]", "# keep\n[AccessKey]")
        assertEquals("SSConf", Conf.parse(text).protocol)
        val renamed = Conf.rename(text, "Deuxième")
        assertTrue(renamed.contains("# keep"))
        assertTrue(renamed.contains("SSConf = ssconf://example.com/key#Café"))
        assertEquals("Deuxième", Conf.parse(renamed).name)
    }
    @Test fun byteLimitAndUtf8() {
        assertEquals("\uFEFF[Profile]\r\n", Conf.read(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "[Profile]\r\n".toByteArray()))
        assertFails { Conf.read(byteArrayOf(0xC3.toByte(), 0x28)) }
        assertFails { Conf.read(ByteArray(65537)) }
    }
    @Test fun duplicateFieldsAndSections() {
        val text = Conf.fromKey("X", "ss://example")
        assertFails { Conf.parse(text.replace("Version = 1", "Version = 1\nVersion = 1")) }
        assertFails { Conf.parse(text + "\n[Profile]\n") }
        assertFails { Conf.parse("Name = X\n" + text) }
    }
    @Test fun inlineFileNeverLeavesExternalDependency() {
        val text = Conf.fromKey("X", "ss://example").replace("SS = ss://example", "File = key.txt")
        val inlined = Conf.inlineFile(text, "ss://example")
        assertTrue(inlined.contains("Key = ss://example"))
        assertFalse(inlined.contains("File ="))
        assertEquals("Key", Conf.parse(inlined).protocol)
        assertFails { Conf.inlineFile(text, "bad://example") }
    }
    @Test fun inlineImportUsesFallbackForMissingOrEmptyName() {
        val base = Conf.fromKey("X", "ss://example")
            .replace("SS = ss://example", "File = key.txt")
        listOf("", "Name = \n").forEach { name ->
            val text = base.replace("Name = X\n", name)
            val result = Conf.ensureName(Conf.inlineFile(text, "ss://example"), "Imported")
            assertEquals("Imported", ConfigDocument.parse(result).validate().name)
            assertTrue(result.contains("Key = ss://example"))
            assertFalse(result.contains("File ="))
        }
    }
    @Test fun inlineAcceptsSectionWhitespaceAndRejectsDuplicates() {
        val text = Conf.fromKey("X", "ss://example")
            .replace("[AccessKey]", "[ AccessKey ]")
            .replace("SS = ss://example", "File = key.txt")
        val result = Conf.inlineFile(text, "ss://example")
        assertEquals("Key", Conf.parse(result).protocol)
        assertFalse(result.contains("File ="))
        assertFails { Conf.inlineFile(text.replace("File = key.txt", "File = key.txt\nFile = other.txt"), "ss://example") }
        assertFails { Conf.inlineFile(text + "\n[AccessKey]\nFile = other.txt\n", "ss://example") }
    }
    @Test fun dnsAndRoutingFollowCoreSyntax() {
        val text = Conf.fromKey("X", "ss://example")
        assertFails { Conf.parse(text.replace("DNS = 1.1.1.1", "DNS = 1.1.1.1, 1.1.1.1")) }
        assertFails { Conf.parse(text + "\n[Rule]\nCIDR = 203.0.113.1\nAction = direct\n") }
        assertEquals("X", Conf.parse(text + "\n[Rule]\nCIDR = 203.0.113.1/32\nAction = direct\n").name)
        assertFails { Conf.parse(text + "\n[Rule]\nDomain = bad domain\nAction = proxy\n") }
    }
    @Test fun quotedNamesAndEscapes() {
        val text = Conf.fromKey("X", "ss://example").replace("Name = X", "Name = \"Zoë \\u2603\"")
        assertEquals("Zoë ☃", Conf.parse(text).name)
        assertFails { Conf.parse(text.replace("\\u2603", "\\q")) }
    }

    @Test fun schemesKeepTheirMappingAndRejectCaseChanges() {
        val mappings = mapOf("ss" to "SS", "ssconf" to "SSConf", "socks5" to "SOCKS5",
            "http" to "HTTP", "https" to "HTTP", "masque" to "MASQUE", "vless" to "VLESS",
            "vmess" to "VMess", "trojan" to "Trojan", "naive+https" to "Naive",
            "naive+quic" to "Naive", "hysteria2" to "Hysteria2", "hy2" to "Hysteria2",
            "anytls" to "AnyTLS", "ssh" to "SSH", "tuic" to "TUIC")
        mappings.forEach { (scheme, field) ->
            assertEquals(field, Conf.keyField("$scheme://Secret"))
            assertFails { Conf.keyField("${scheme.uppercase()}://Secret") }
            assertFails { Conf.keyField("${scheme.replaceFirstChar { it.uppercase() }}://Secret") }
        }
        listOf("ss:/Secret", "ss://", "ss://Sec ret", "ss://Sec\u0000ret", "bad://Secret").forEach {
            try { Conf.keyField(it); fail("Expected ProfileError") }
            catch (error: ProfileError) { assertFalse(error.message.orEmpty().contains("Secret")) }
        }
    }
    @Test fun exactUtf8ByteBoundary() {
        val text = "é".repeat(32768)
        assertEquals(text, Conf.read(text.toByteArray(Charsets.UTF_8)))
        assertFails { Conf.read((text + "a").toByteArray(Charsets.UTF_8)) }
        assertEquals(65536, Conf.read(ByteArray(65536) { 65 }).length)
    }
    @Test fun inlineNormalizesBomAndEnforcesFinalSize() {
        val text = Conf.fromKey("X", "ss://example")
            .replace("SS = ss://example", "File = key.txt") + "# keep\n"
        val result = Conf.inlineFile(text, "\uFEFF  ss://Secret  \r\n")
        assertTrue(result.contains("Key = ss://Secret"))
        assertFalse(result.contains("File ="))
        assertTrue(result.endsWith("# keep\n"))
        listOf("ss://Secret\nName = injected", "bad://Secret").forEach {
            assertFails { Conf.inlineFile(text, it) }
        }
        val base = Conf.inlineFile(text, "ss://x")
        val remaining = 65536 - base.toByteArray(Charsets.UTF_8).size
        assertEquals(65536, Conf.inlineFile(text, "ss://x" + "a".repeat(remaining)).toByteArray(Charsets.UTF_8).size)
        assertFails { Conf.inlineFile(text, "ss://x" + "a".repeat(remaining + 1)) }
    }
    @Test fun quoteEscapeEdges() {
        listOf("\"a\"b\"", "\"\\U-0000001\"", "\"\\u+001\"", "\"\\400\"", "\"\\777\"", "\"\\'\"").forEach {
            assertFails { Conf.unquote(it) }
        }
        assertEquals("\u0000ÿÿ☃😀", Conf.unquote("\"\\000\\377\\xff\\u2603\\U0001f600\""))
        assertEquals("a\"b\\c", Conf.unquote("\"a\\\"b\\\\c\""))
    }
    private fun assertFails(block: () -> Unit) { try { block(); fail("Expected ProfileError") } catch (_: ProfileError) {} }
}
