package org.bimeron.app
import org.junit.Assert.*
import org.junit.Test
class ProtocolFormsTest {
    private fun doc(type: String = "ss", unknown: Boolean = false): ConfigDocument {
        val method = if (type == "socks5") "" else "Method = aes-128-gcm\n"
        return ConfigDocument.parse("[AccessKey]\nType = $type\nServer = example.com\nPort = 443\n${method}Password = secret\n# keep\n" +
            if (unknown) "Future = \"literal\"\n" else "")
    }
    @Test fun targetedEditPreservesUnknownAndSecrets() {
        val original = doc(unknown = true)
        assertEquals(original.render().replace("Port = 443", "Port = 8443"), original.set("AccessKey", "Port", "8443").render())
    }
    @Test fun unknownMethodAndPluginRequireRaw() {
        assertTrue(ProtocolForms.rawOnly(doc().set("AccessKey", "Method", "future")))
        assertTrue(ProtocolForms.rawOnly(doc().set("AccessKey", "Plugin", "future")))
    }
    @Test fun ss2022PrefixRequiresRaw() {
        assertTrue(ProtocolForms.rawOnly(doc().set("AccessKey", "Method", "2022-blake3-aes-128-gcm").set("AccessKey", "Prefix", "hello")))
    }
    @Test fun multilineRequiresRaw() { assertTrue(ProtocolForms.rawOnly(doc().set("AccessKey", "Password", "a\nb"))) }
    @Test fun portAllowsIntermediateInvalidDocument() {
        val next = doc().set("AccessKey", "Port", "")
        assertTrue(ProtocolForms.invalid(next))
        assertFalse(ProtocolForms.rawOnly(next))
        assertFalse(ProtocolForms.invalid(next.set("AccessKey", "Port", "65535")))
        assertTrue(ProtocolForms.invalid(next.set("AccessKey", "Port", "65536")))
    }
    @Test fun socksAuthenticationUsesByteLimit() {
        val next = doc("socks5").set("AccessKey", "Username", "user")
        assertFalse(ProtocolForms.invalid(next))
        assertTrue(ProtocolForms.invalid(next.set("AccessKey", "Username", "")))
        assertTrue(ProtocolForms.invalid(next.set("AccessKey", "Username", "é".repeat(128))))
        assertFalse(ProtocolForms.invalid(next.set("AccessKey", "Username", "").set("AccessKey", "Password", "")))
    }
    @Test fun socksEditPreservesQuotedPassword() {
        val original = ConfigDocument.parse("[AccessKey]\nType = socks5\nServer = host\nPort = 1080\nUsername = user\nPassword = \"a=b\"\n; untouched\nExtra = value\n")
        val changed = original.set("AccessKey", "Server", "new.example")
        assertEquals(original.render().replace("Server = host", "Server = new.example"), changed.render())
        assertEquals("a=b", changed.value("AccessKey", "Password"))
    }
    @Test fun uriNeverConverted() {
        val next = ConfigDocument.parse("[AccessKey]\nSS = ss://secret\n")
        assertFalse(ProtocolForms.supported(next))
        assertTrue(ProtocolForms.rawOnly(next))
        assertEquals("[AccessKey]\nSS = ss://secret\n", next.render())
    }

    @Test fun rawOnlyDefersValidationToCore() {
        for (key in listOf("Method", "Plugin")) {
            val raw = doc().set("AccessKey", key, "future").set("AccessKey", "Port", "")
            assertTrue(ProtocolForms.rawOnly(raw))
            assertFalse(ProtocolForms.invalid(raw))
        }
        assertTrue(ProtocolForms.invalid(doc().set("AccessKey", "Password", "")))
    }
    @Test fun protectionCoversEveryProtocolAndRawSchema() {
        for (type in listOf("ss", "shadowsocks", "socks5", "ssh", "vless", "hysteria2")) assertTrue(ProtocolForms.protected(doc(type)))
        for ((key, uri) in listOf("SS" to "ss://secret", "SOCKS5" to "socks5://user:pass@host:1080", "SSH" to "ssh://user@host:22"))
            assertTrue(ProtocolForms.protected(ConfigDocument.parse("[AccessKey]\n$key = $uri\n")))
        assertTrue(ProtocolForms.protected(ConfigDocument.parse("[AccessKey]\nKey = https://host\n")))
    }
    @Test fun draftEditsAndUndoPreserveUnknownText() {
        val original = doc(unknown = true).render()
        val draft = EditorDraft(Profile("fixture", "Fixture", "ss", "vpn", original))
        draft.set("AccessKey", "Server", "new.example")
        assertEquals(original.replace("Server = example.com", "Server = new.example"), draft.source.text)
        draft.set("AccessKey", "PluginOpts", "tls=false;host=example.com")
        draft.undo()
        assertEquals(original.replace("Server = example.com", "Server = new.example"), draft.source.text)
        draft.undo()
        assertEquals(original, draft.source.text)
        assertFalse(draft.dirty)
    }
    @Test fun descriptorsExposeExistingFieldsAndCredentialMetadata() {
        assertSame(ShadowsocksForm, ProtocolForms.descriptor(doc("ss")))
        assertSame(ShadowsocksForm, ProtocolForms.descriptor(doc("shadowsocks")))
        assertSame(Socks5Form, ProtocolForms.descriptor(doc("socks5")))
        assertEquals(listOf("Server", "Port", "Method", "Password", "Prefix", "Plugin", "PluginOpts"), ProtocolForms.fields(doc()))
        assertEquals(listOf("Server", "Port", "Username", "Password"), ProtocolForms.fields(doc("socks5")))
        for (descriptor in listOf(ShadowsocksForm, Socks5Form)) {
            assertTrue(descriptor.fields.all { it.labelRes != 0 })
            assertEquals(ProtocolKeyboardType.NUMBER, descriptor.fields.single { it.key == "Port" }.keyboardType)
            for (field in descriptor.fields.filter { it.key in listOf("Username", "Password", "PluginOpts") }) {
                assertTrue(field.secret)
                assertEquals(ProtocolKeyboardType.PASSWORD, field.keyboardType)
            }
        }
        assertEquals(ProtocolForms.methods, ShadowsocksForm.fields.single { it.key == "Method" }.options)
        assertEquals(ProtocolForms.plugins, ShadowsocksForm.fields.single { it.key == "Plugin" }.options)
    }
    @Test fun unknownProtocolInspectionAndUndoPreserveRawDocument() {
        val original = doc("future").render()
        val draft = EditorDraft(Profile("fixture", "Fixture", "future", "vpn", original))
        assertNull(ProtocolForms.descriptor(draft.document))
        assertFalse(ProtocolForms.supported(draft.document))
        assertTrue(ProtocolForms.rawOnly(draft.document))
        assertFalse(ProtocolForms.invalid(draft.document))
        assertEquals(original, draft.source.text)
        draft.set("AccessKey", "Port", "8443")
        draft.undo()
        assertEquals(original, draft.source.text)
        assertFalse(draft.dirty)
    }
}
