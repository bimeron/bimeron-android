package org.bimeron.app

import org.junit.Assert.*
import org.junit.Test

class TrojanProtocolFormTest {
    private fun doc(extra: String = "") = ConfigDocument.parse("[AccessKey]\nType = trojan\nServer = example.org\nPort = 443\nPassword = sentinel-password\n$extra")
    private fun valid(doc: ConfigDocument) {
        assertFalse("Expected form representation", TrojanProtocolForm.rawOnly(doc))
        assertFalse("Expected valid transport fields", TrojanProtocolForm.invalid(doc))
    }

    @Test fun supportedTransportsAndDefaults() {
        for (extra in listOf("", "Network = tcp\n", "Network = ws\n", "Network = ws\nALPN = http/1.1,http/1.1\nWSPath = /a%2Fb?token=a%26b\nWSHost = [2001:db8::1]:443\n",
            "Network = grpc\nGRPCServiceName = tunnel\n", "Network = grpc\nGRPCServiceName = tunnel\nGRPCMultiMode = false\n",
            "Network = grpc\nGRPCServiceName = tunnel\nGRPCMultiMode = true\nGRPCAuthority = other.example:443\nALPN = h2,h2\n",
            "Security = tls\nServerName = independent.example\nALPN = custom,h2\n")) valid(doc(extra))
        for (host in listOf("192.0.2.1", "2001:db8::1", "example.org")) valid(doc().set("AccessKey", "Server", host))
        valid(doc().set("AccessKey", "Password", "  untrimmed  "))
    }

    @Test fun explicitServerNameDoesNotOverrideInvalidEndpoint() {
        val explicitSni = doc("ServerName = valid.example\n")
        valid(explicitSni)
        for (server in listOf("bad/host", "bad host", "bad\thost", "bad\rhost", "bad\nhost", "")) {
            val invalid = explicitSni.set("AccessKey", "Server", server)
            assertTrue("Invalid endpoint must block saving despite valid SNI", TrojanProtocolForm.invalid(invalid))
            assertEquals(server, invalid.value("AccessKey", "Server"))
        }
        assertTrue("Empty endpoint must also be invalid without explicit SNI", TrojanProtocolForm.invalid(doc().set("AccessKey", "Server", "")))
    }

    @Test fun unsupportedRepresentationsStayRawAndUnchanged() {
        val options = listOf("Network = xhttp", "Security = none", "Security = reality", "Fingerprint =", "WSEarlyData = 0", "WSEarlyData =", "Encryption =", "WSHeaders = token", "GRPCMethod = custom", "XHTTPExtra = {}", "Username = user", "Method = cipher", "Prefix = data", "Plugin = plugin")
        for (option in options) {
            val text = doc("$option\n").render()
            val parsed = ConfigDocument.parse(text)
            assertTrue("Unsupported option must remain Raw", TrojanProtocolForm.rawOnly(parsed))
            assertTrue("Inspection must preserve source bytes", text == parsed.render())
        }
        assertTrue(TrojanProtocolForm.rawOnly(doc().set("AccessKey", "Password", "sentinel\r\nsecret")))
    }

    @Test fun arbitraryUnknownKeysIncludingEmptyRemainRawAndUnchanged() {
        for (key in listOf("Future", "UnrecognizedCredential", "UnknownPath", "UnknownQuery", "password")) {
            for (value in listOf("", "\"\"", "\"sentinel-credential/path?token=sentinel-query%26secret\"")) {
                val text = doc("  $key = $value\n").render()
                val parsed = ConfigDocument.parse(text)
                assertTrue("Unknown key must be present", key in parsed.values("AccessKey"))
                assertTrue("Unknown key presence requires Raw", TrojanProtocolForm.rawOnly(parsed))
                assertTrue("Raw inspection preserves exact bytes", text == parsed.render())
            }
        }
        valid(doc())
    }

    @Test fun sharedSourceKeysIncludingEmptyRequireRaw() {
        for (key in Conf.sources + setOf("Username", "Method", "Prefix", "Plugin", "PluginOpts")) {
            for (value in listOf("", "sentinel-source/path?token=sentinel-query")) {
                val text = doc("$key = $value\n").render()
                val parsed = ConfigDocument.parse(text)
                assertTrue("Non-schema key presence requires Raw", TrojanProtocolForm.rawOnly(parsed))
                assertTrue("Source inspection preserves exact bytes", text == parsed.render())
            }
        }
    }

    @Test fun wrapperAndUnrelatedKeysDoNotRestrictTrojanSchema() {
        val text = doc("Network = ws\nWSPath = //gateway/tunnel?token=sentinel-query\n" +
            "[ShadowTLS]\nPassword = sentinel-wrapper\nServerName = wrapper.example\n" +
            "[Profile]\nName = sentinel-metadata/path?token=sentinel-query\n").render()
        val parsed = ConfigDocument.parse(text)
        valid(parsed)
        assertTrue("Wrapper and metadata bytes remain unchanged", text == parsed.render())
    }

    @Test fun invalidTransportFieldsAreRetainedAndBlocked() {
        val cases = listOf("Password" to "", "ServerName" to "bad/name", "ServerName" to "bad name", "GRPCMultiMode" to "", "GRPCMultiMode" to "TRUE", "WSPath" to "/retained", "WSHost" to "retained.example", "GRPCServiceName" to "retained", "GRPCAuthority" to "retained", "GRPCMultiMode" to "true", "ALPN" to "h2,", "ALPN" to "h2, h2")
        for ((key, value) in cases) assertTrue("Invalid field must block saving", TrojanProtocolForm.invalid(doc().set("AccessKey", key, value)))
        val ws = doc("Network = ws\nWSPath = /retained?token=sentinel\n")
        val tcp = ws.set("AccessKey", "Network", "tcp")
        assertFalse(TrojanProtocolForm.rawOnly(tcp))
        assertTrue(TrojanProtocolForm.invalid(tcp))
        assertTrue(tcp.value("AccessKey", "WSPath") == ws.value("AccessKey", "WSPath"))
        valid(tcp.set("AccessKey", "WSPath", ""))
        valid(doc("GRPCMultiMode = false\n"))
    }

    @Test fun webSocketRejectsEarlyDataAndMalformedPathsAndAuthorities() {
        val ws = doc("Network = ws\n")
        for (path in listOf("relative", "https://host/path", "/a#fragment", "/a\\b", "/%xx", "/?ed=0", "/?%65d=0", "/?ed"))
            assertTrue("Invalid WS path", TrojanProtocolForm.invalid(ws.set("AccessKey", "WSPath", path)))
        for (host in listOf("user@host", "host/path", "host?query", "host#fragment", "host name", ":443", "[broken"))
            assertTrue("Invalid WS host", TrojanProtocolForm.invalid(ws.set("AccessKey", "WSHost", host)))
        assertTrue(TrojanProtocolForm.invalid(ws.set("AccessKey", "ALPN", "h2")))
    }

    @Test fun webSocketDoubleSlashPathsPreserveSourceAndDetectEncodedEarlyData() {
        for (path in listOf("//gateway/tunnel", "//authority/path", "///gateway/tunnel?token=a%26b", "//gateway/tunnel?%74oken=a%26b")) {
            val text = doc("Network = ws\nWSPath = $path\n").render()
            val parsed = ConfigDocument.parse(text)
            valid(parsed)
            assertTrue("Inspection preserves exact source bytes", text == parsed.render())
            assertTrue("Path remains encoded and unchanged", path == parsed.value("AccessKey", "WSPath"))
        }
        for (query in listOf("ed=0", "%65d=0", "e%64=0", "%65%64", "token=a%26b&%65%64=0")) {
            val text = doc("Network = ws\nWSPath = //gateway/tunnel?$query\n").render()
            val parsed = ConfigDocument.parse(text)
            assertTrue("Encoded early data must block saving", TrojanProtocolForm.invalid(parsed))
            assertTrue("Rejected paths preserve exact source bytes", text == parsed.render())
        }
    }

    @Test fun grpcRequiresServiceAndStrictAuthorityAndAlpn() {
        val grpc = doc("Network = grpc\n")
        assertTrue(TrojanProtocolForm.invalid(grpc))
        for (service in listOf("a/b", "a?b", "a\\b", "a#b", "a|b", "a b"))
            assertTrue("Invalid gRPC service", TrojanProtocolForm.invalid(grpc.set("AccessKey", "GRPCServiceName", service)))
        val complete = grpc.set("AccessKey", "GRPCServiceName", "tunnel")
        for (authority in listOf(":443", "a/b", "a\\b", "a@b", "a?b", "a#b", "a b"))
            assertTrue("Invalid gRPC authority", TrojanProtocolForm.invalid(complete.set("AccessKey", "GRPCAuthority", authority)))
        assertTrue(TrojanProtocolForm.invalid(complete.set("AccessKey", "ALPN", "http/1.1")))
    }

    @Test fun uriAndMixedSourcesNeverConvertOrExposeCredentials() {
        for (source in listOf("Trojan", "Key", "File")) {
            val text = "[AccessKey]\n$source = trojan://sentinel%40password@[2001:db8::1]:443?type=grpc&mode=multi#name\n"
            val parsed = ConfigDocument.parse(text)
            assertTrue(TrojanProtocolForm.rawOnly(parsed))
            assertTrue(text == parsed.render())
        }
        assertTrue(TrojanProtocolForm.rawOnly(doc("Trojan = trojan://sentinel@host:443\n")))
        assertEquals(setOf("trojan"), TrojanProtocolForm.sourceSchemes)
        assertEquals(setOf("trojan"), TrojanProtocolForm.types)
        for (key in listOf("Password", "WSPath", "WSHost", "GRPCServiceName", "GRPCAuthority")) {
            val field = TrojanProtocolForm.fields.single { it.key == key }
            assertTrue(field.secret)
            assertEquals(ProtocolKeyboardType.PASSWORD, field.keyboardType)
        }
    }

    @Test fun editsReopenUndoAndInspectionPreserveOpaqueBytes() {
        for (newline in listOf("\n", "\r\n")) for (ending in listOf("", newline)) {
            val original = ("# before\n[AccessKey]\nType = trojan\nServer = example.org\n  Port = 443\nPassword = \"sentinel=#;value\"\nFuture = \"sentinel-unknown\"\n; after\n[ShadowTLS]\nPassword = sentinel-outer\nServerName = wrapper.example").replace("\n", newline) + ending
            val parsed = ConfigDocument.parse(original)
            assertTrue("Unknown Future key requires Raw", TrojanProtocolForm.rawOnly(parsed))
            assertTrue("No-op preserves exact bytes", original == parsed.render())
            val changed = parsed.set("AccessKey", "Port", "8443")
            assertTrue("Only edited field changes", original.replace("Port = 443", "Port = 8443") == changed.render())
            assertTrue("Reopen preserves exact bytes", changed.render() == ConfigDocument.parse(changed.render()).render())
            val draft = EditorDraft(Profile("trojan-fixture", "Fixture", "trojan", "vpn", original))
            draft.set("AccessKey", "Port", "8443")
            draft.undo()
            assertTrue("Undo restores exact bytes", original == draft.source.text)
            assertFalse(draft.dirty)
        }
    }
}
