package org.bimeron.app

import org.junit.Assert.*
import org.junit.Test

class ConfigDocumentTest {
    private val base = Conf.fromKey("Example", "socks5://192.0.2.1:1080")
    private val rules = "\n[Rule]\n# first\nDomain = example.org\nAction = direct\n\n[Rule]\nCIDR = 203.0.113.0/24\nAction = block\n"

    @Test fun editsPreserveUnrepresentedSectionsAndComments() {
        val text = base + "\n# transport\n[ShadowTLS]\nServerName = example.org\nPassword = \"secret=;#\\\\value\"\n"
        val doc = ConfigDocument.parse(text)
        assertEquals(text, doc.render())
        val changed = doc.set("Interface", "MTU", "1400").set("Profile", "Name", "Renamed").render()
        assertTrue(changed.endsWith(text.substringAfter("\n# transport").let { "\n# transport$it" }))
        assertTrue(changed.contains("SOCKS5 = socks5://192.0.2.1:1080"))
        assertEquals("Renamed", Conf.parse(changed).name)
    }
    @Test fun disabledRulesRoundTripAndKeepTheirPosition() {
        val doc = ConfigDocument.parse(base + rules).enableRule(0, false)
        val restored = ConfigDocument.parse(doc.render())
        assertEquals(doc.render(), restored.render())
        assertTrue(restored.rules[0].disabled)
        assertEquals("example.org", restored.rules[0].values()["Domain"])
        assertFalse(restored.render(false).contains("Domain = example.org"))
        assertEquals(restored.render(), restored.validate().text)
        assertEquals(base + rules, restored.enableRule(0, true).render())
        val moved = restored.moveRule(0, 1)
        assertTrue(moved.rules[1].disabled)
        assertEquals("203.0.113.0/24", moved.rules[0].values()["CIDR"])
    }
    @Test fun changingRuleTypeAndKeyDoesNotDuplicateFields() {
        val changed = ConfigDocument.parse(base + rules).editRule(0, "CIDR", "2001:db8::/32").set("AccessKey", "HTTP", "https://example.org:443", "SOCKS5")
        assertNull(changed.rules[0].values()["Domain"])
        assertFalse(changed.render().contains("SOCKS5 ="))
        assertEquals("HTTP", changed.validate().protocol)
    }
    @Test fun syntaxErrorsAndBrokenMetadataAreRejectedWithoutEchoingSecrets() {
        listOf(base + "\nSecret", base + "\n# bimeron:disabled-rule\n#| [Rule]", base + "\n# bimeron:end-rule", base.replace("MTU = 1420", "MTU = 1420\nMTU = 1500")).forEach { text ->
            try { ConfigDocument.parse(text); fail("Expected rejection") } catch (e: ProfileError) { assertFalse(e.message.orEmpty().contains("socks5://")) }
        }
    }
    @Test fun formattingAndRenameDoNotRequireReconnect() {
        val doc = ConfigDocument.parse(base + rules)
        assertEquals(doc.effective(), doc.set("Profile", "Name", "New").formatted().effective())
        assertNotEquals(doc.effective(), doc.editRule(0, "Action", "proxy").effective())
        assertNotEquals(doc.effective(), doc.moveRule(0, 1).effective())
        assertNotEquals(doc.effective(), doc.enableRule(0, false).effective())
    }
    @Test fun invalidNetworkValuesAndDisabledRulesAreRejected() {
        listOf("not-a-number", "1279", "1501").forEach { mtu -> assertInvalid(ConfigDocument.parse(base).set("Interface", "MTU", mtu)) }
        listOf("127.0.0.1", "224.0.0.1", "169.254.1.1", "1.1.1.1,1.1.1.1").forEach { dns -> assertInvalid(ConfigDocument.parse(base).set("Interface", "DNS", dns)) }
        assertInvalid(ConfigDocument.parse(base + rules).editRule(0, "Domain", "*.example.com").enableRule(0, false))
    }
    @Test fun quotedValuesCannotInjectConfigurationLines() {
        val value = "secret\n[Rule]\r\t\"\\"
        assertEquals(value, Conf.unquote(Conf.quote(value)))
        val doc = ConfigDocument.parse(base).set("AccessKey", "SOCKS5", value)
        assertEquals(value, ConfigDocument.parse(doc.render()).source()?.second)
        assertTrue(doc.rules.isEmpty())
    }
    @Test fun matcherReplacementPreservesPositionSpacingAndOpaqueValues() {
        val original = "[Rule]\r\n# before\r\nFuture = \"a#;=b\"\r\n  Domain \t=  old.example \t\r\n; after\r\nAction = direct"
        val expected = original.replace("Domain \t=  old.example", "CIDR \t=  192.0.2.0/24")
        val changed = ConfigDocument.parse(original).editRule(0, "CIDR", "192.0.2.0/24")
        assertEquals(expected, changed.render())
        assertEquals(original, changed.editRule(0, "Domain", "old.example").render())
        val quoted = changed.editRule(0, "Future", "next#;=value")
        assertEquals("next#;=value", ConfigDocument.parse(quoted.render()).rules[0].values()["Future"])
        var restored = changed
        repeat(3) {
            restored = ConfigDocument.parse(restored.enableRule(0, false).render()).enableRule(0, true)
            assertEquals(expected, restored.render())
        }
    }
    @Test fun existingTargetMatcherIsNotDuplicated() {
        val text = "[Rule]\nDomain = old\n# keep\nCIDR = 192.0.2.0/24\nAction = direct"
        assertEquals("[Rule]\n# keep\nCIDR = 198.51.100.0/24\nAction = direct",
            ConfigDocument.parse(text).editRule(0, "CIDR", "198.51.100.0/24").render())
    }
    @Test fun distantMovesAreStableAndKeepNonRuleSlots() {
        val a = "[Rule]\n# a\nDomain = a.example\nFuture = a\n"
        val b = "# bimeron:disabled-rule\n#| [Rule]\n#| ; b\n#| Domain = b.example\n#| Future = b\n# bimeron:end-rule\n"
        val middle = "[Routing]\n# fixed\nDefault = proxy\n"
        val c = "[Rule]\nDomain = c.example\nFuture = c\n"
        val d = "[Rule]\nDomain = d.example"
        val original = a + middle + b + c + d
        val doc = ConfigDocument.parse(original)
        val moved = doc.moveRule(0, 2)
        assertEquals(b + middle + c + a + d, moved.render())
        assertTrue(moved.rules[0].disabled)
        assertEquals(original, moved.moveRule(2, -2).render())
        assertEquals(a + middle + c + d, doc.removeRule(1).render())
    }
    @Test fun rulesAreReplacedInPlaceAndSectionsAddedOnce() {
        val withRules = ConfigDocument.parse(base + rules + "\n[Extra]\nKey = value\n")
        val other = ConfigDocument.parse(base + "\n[Rule]\n# mine\nDomain = example.net\nAction = block\n").enableRule(0, false)
        val replaced = withRules.withRules(other.rules)
        assertEquals(other.rules, replaced.rules)
        assertTrue(replaced.render().indexOf("example.net") < replaced.render().indexOf("[Extra]"))
        assertEquals(replaced.render(), ConfigDocument.parse(replaced.render()).render())
        assertEquals(listOf("# mine"), replaced.comments())
        assertTrue(ConfigDocument.parse(base).withRules(other.rules).render().endsWith("# bimeron:end-rule"))
        assertTrue(withRules.withRules(emptyList()).rules.isEmpty())
        val added = ConfigDocument.parse(base).addSection("Extra")
        assertTrue("Extra" in added.sections)
        assertEquals(added.render(), added.addSection("Extra").render())
        assertEquals(base + "\n[Extra]\n", added.render())
    }
    private fun assertInvalid(doc: ConfigDocument) { try { doc.validate(); fail("Expected rejection") } catch (_: ProfileError) {} }
}
