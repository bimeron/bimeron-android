package org.bimeron.app

import org.junit.Assert.*
import org.junit.Test

class VlessProtocolFormTest {
    private fun doc(network: String = "tcp", security: String = "tls"): ConfigDocument {
        var d = ConfigDocument.parse("[Profile]\nVersion = 1\nName = Fixture\n[AccessKey]\nType = vless\nServer = example.invalid\nPort = 443\nID = 123e4567-e89b-12d3-a456-426614174000\nEncryption = none\nNetwork = $network\nSecurity = $security\n# untouched\n[Interface]\nMode = vpn\n")
        if (security == "reality") d = d.set("AccessKey", "ServerName", "example.invalid").set("AccessKey", "Fingerprint", "chrome").set("AccessKey", "PublicKey", "A".repeat(43))
        if (network == "grpc") d = d.set("AccessKey", "GRPCServiceName", "service")
        if (network == "xhttp") d = d.set("AccessKey", "XHTTPMode", "auto")
        return d
    }
    @Test fun coreTransportSecurityMatrix() {
        for (n in listOf("tcp", "ws", "grpc", "xhttp")) for (s in listOf("tls", "reality")) {
            val d = doc(n, s)
            assertFalse("$n/$s raw", ProtocolForms.rawOnly(d))
            assertEquals("$n/$s", n == "ws" && s == "reality", ProtocolForms.invalid(d))
            assertEquals(d.render(), ConfigDocument.parse(d.render()).validate().text)
        }
        assertTrue(ProtocolForms.rawOnly(doc().set("AccessKey", "Security", "none")))
        for (flow in listOf("", "xtls-rprx-vision", "xtls-rprx-vision-udp443")) {
            assertFalse(ProtocolForms.invalid(doc("tcp", "reality").set("AccessKey", "Flow", flow)))
            assertEquals(flow.isNotEmpty(), ProtocolForms.invalid(doc().set("AccessKey", "Flow", flow)))
        }
    }
    @Test fun rawFallbackIsLosslessAndPresenceSensitive() {
        for ((key, value) in listOf("Future" to "secret", "XHTTPExtra" to "{bad", "XHTTPHeaders" to "{}", "GRPCMultiMode" to "unknown", "XHTTPMode" to "unknown")) {
            val d = doc().set("AccessKey", key, value)
            assertTrue(key, ProtocolForms.rawOnly(d))
            assertEquals(d.render(), ConfigDocument.parse(d.render()).render())
            assertFalse(ProtocolForms.invalid(d))
        }
        assertFalse(doc().values("AccessKey").containsKey("WSPath"))
        val empty = doc().set("AccessKey", "WSPath", "")
        assertTrue(empty.values("AccessKey").containsKey("WSPath"))
        assertEquals(doc().render(), empty.remove("AccessKey", "WSPath").render())
        val unknown = empty.set("AccessKey", "Future", "a=b")
        assertEquals(unknown.render().replace("WSPath = \n", ""), unknown.remove("AccessKey", "WSPath").render())
        assertEquals(unknown.render(), unknown.remove("Missing", "Future").render())
    }
    @Test fun editableTransitionsRemainVisibleAndUndoLosslessly() {
        val originals = listOf(
            doc() to ("Network" to "xhttp"),
            doc("ws").set("AccessKey", "WSEarlyData", "123") to ("WSEarlyData" to ""),
            doc("tcp", "reality").set("AccessKey", "ShortID", "abcd").set("AccessKey", "SpiderX", "/secret") to ("Security" to "tls"),
            doc("ws").set("AccessKey", "WSPath", "/secret") to ("Network" to "grpc")
        )
        for ((original, change) in originals) {
            val draft = EditorDraft(Profile("transition", "Fixture", "vless", "vpn", original.render()))
            draft.set("AccessKey", change.first, change.second)
            val intermediate = draft.document
            assertFalse(change.toString(), ProtocolForms.rawOnly(intermediate))
            assertTrue(change.toString(), ProtocolForms.invalid(intermediate))
            val visible = VlessProtocolForm.fields(intermediate).map { it.key }
            for (key in original.values("AccessKey").keys - "Type") assertTrue(key, key in visible)
            if (change.first == "Security") for (key in listOf("PublicKey", "ShortID", "SpiderX")) {
                val field = VlessProtocolForm.fields(intermediate).single { it.key == key }
                assertTrue(field.removable)
                assertTrue(field.secret)
            }
            when (change.first) {
                "WSEarlyData" -> draft.set("AccessKey", "WSEarlyData", "456")
                "Security" -> for (key in listOf("PublicKey", "ShortID", "SpiderX")) draft.edit(draft.document.remove("AccessKey", key))
                else -> if (change.second == "xhttp") draft.set("AccessKey", "XHTTPMode", "auto") else {
                    draft.edit(draft.document.remove("AccessKey", "WSPath"))
                    draft.set("AccessKey", "GRPCServiceName", "service")
                }
            }
            assertFalse(ProtocolForms.rawOnly(draft.document))
            assertFalse(ProtocolForms.invalid(draft.document))
            while (draft.canUndo) draft.undo()
            assertEquals(original.render(), draft.source.text)
        }
        val invalidNumber = doc("ws").set("AccessKey", "WSEarlyData", "12x")
        assertFalse(ProtocolForms.rawOnly(invalidNumber))
        assertTrue(ProtocolForms.invalid(invalidNumber))
    }

    @Test fun staleChoiceFieldsCanBeExplicitlyRemovedAndRestored() {
        for ((network, key, value) in listOf(Triple("ws", "WSEarlyData", "123"),
                Triple("grpc", "GRPCMultiMode", "true"), Triple("xhttp", "XHTTPMode", "auto"))) {
            val original = doc(network).set("AccessKey", key, value)
            val draft = EditorDraft(Profile("stale", "Fixture", "vless", "vpn", original.render()))
            draft.set("AccessKey", "Network", "tcp")
            val stale = draft.source.text
            assertFalse(ProtocolForms.rawOnly(draft.document))
            assertTrue(ProtocolForms.invalid(draft.document))
            for (field in VlessProtocolForm.fields(draft.document).filter { it.key in original.values("AccessKey") && it.removable }) {
                draft.edit(draft.document.remove("AccessKey", field.key))
            }
            assertFalse(ProtocolForms.invalid(draft.document))
            while (draft.canUndo && draft.source.text != stale) draft.undo()
            assertEquals(stale, draft.source.text)
            assertTrue(VlessProtocolForm.fields(draft.document).single { it.key == key }.removable)
            draft.undo()
            assertEquals(original.render(), draft.source.text)
            val unknown = original.set("AccessKey", "Future", "keep-secret")
            val switched = unknown.set("AccessKey", "Network", "tcp")
            assertTrue(ProtocolForms.rawOnly(switched))
            assertEquals("keep-secret", switched.value("AccessKey", "Future"))
        }
    }

    @Test fun validationEdges() {
        for ((key, value) in listOf("ID" to "bad", "Server" to "bad host", "ServerName" to "bad/name", "Port" to "0", "Port" to "65536"))
            assertTrue(key, ProtocolForms.invalid(doc().set("AccessKey", key, value)))
        for ((key, value) in listOf("PublicKey" to "A=", "ShortID" to "f", "ShortID" to "gg", "ALPN" to "h2"))
            assertTrue(key, ProtocolForms.invalid(doc("tcp", "reality").set("AccessKey", key, value)))
        for ((key, value) in listOf("WSPath" to "/?ed=1", "WSPath" to "/?%65d=1", "WSPath" to "/bad%xx", "WSHost" to "user@host", "WSEarlyData" to "8193", "ALPN" to "h2"))
            assertTrue(key, ProtocolForms.invalid(doc("ws").set("AccessKey", key, value)))
        for (service in listOf("", "a/b", "/a/", "/a//b", "a?b"))
            assertTrue(service, ProtocolForms.invalid(doc("grpc").set("AccessKey", "GRPCServiceName", service)))
        assertFalse(ProtocolForms.invalid(doc("grpc").set("AccessKey", "GRPCServiceName", "/a/b")))
        assertTrue(ProtocolForms.invalid(doc("xhttp", "reality").set("AccessKey", "XHTTPMode", "packet-up")))
        assertTrue(ProtocolForms.invalid(doc("xhttp").set("AccessKey", "ALPN", "h3").set("AccessKey", "Fingerprint", "chrome")))
        assertFalse(ProtocolForms.invalid(doc("xhttp").set("AccessKey", "ALPN", "h3")))
        assertFalse(ProtocolForms.invalid(doc("ws").set("AccessKey", "WSPath", "/path?token=a%3Db").set("AccessKey", "WSEarlyData", "8192")))
    }
    @Test fun visibleFieldsMaskSensitiveValuesAndPreserveUndo() {
        val d = doc("ws").set("AccessKey", "WSPath", "/?token=secret")
        val fields = VlessProtocolForm.fields(d)
        for (key in listOf("ID", "ServerName", "ALPN", "WSPath", "WSHost")) assertTrue(fields.single { it.key == key }.secret)
        assertFalse(fields.any { it.key == "GRPCServiceName" || it.key == "PublicKey" })
        val original = d.set("AccessKey", "Future", "keep").render()
        val draft = EditorDraft(Profile("fixture", "Fixture", "vless", "vpn", original))
        draft.edit(draft.document.remove("AccessKey", "Future"))
        draft.set("AccessKey", "WSPath", "/changed?token=other")
        draft.undo(); draft.undo()
        assertEquals(original, draft.source.text)
        assertFalse(draft.dirty)
    }
    @Test fun uriStaysProtectedRawWithoutConversion() {
        for (key in listOf("VLESS", "Key")) {
            val text = "[AccessKey]\n$key = vless://secret@example.invalid:443?future=secret\n"
            val d = ConfigDocument.parse(text)
            assertNull(ProtocolForms.descriptor(d)); assertTrue(ProtocolForms.protected(d)); assertTrue(ProtocolForms.rawOnly(d))
            assertEquals(text, d.render())
        }
    }
}
