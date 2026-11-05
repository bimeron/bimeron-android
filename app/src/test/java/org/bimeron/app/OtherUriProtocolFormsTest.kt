package org.bimeron.app

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class OtherUriProtocolFormsTest {
    private val id = "00112233-4455-6677-8899-aabbccddeeff"
    private fun doc(uri: String, key: String = "Key") = ConfigDocument.parse("# retained\n[Profile]\nName = Outer\n[AccessKey]\n$key = ${Conf.quote(uri)}\n# keep\nFuture = literal\n")
    private fun parsed(uri: String): OtherUriValue = requireNotNull(OtherUriParser.parse(uri)) { "Fixture did not parse" }
    private fun vmess(json: String, url: Boolean = false, padded: Boolean = true): String {
        var encoder = if (url) Base64.getUrlEncoder() else Base64.getEncoder()
        if (!padded) encoder = encoder.withoutPadding()
        return "vmess://" + encoder.encodeToString(json.toByteArray(Charsets.UTF_8))
    }
    private fun json(extra: String = "") = """{"v":2,"add":"2001:db8::1","port":443,"id":"$id","net":"tcp","tls":"tls"$extra}"""

    @Test fun minimalFormsAndNoOpPreserveEveryByte() {
        for ((key, uri) in listOf(
            "Hysteria2" to "hy2://p%40ss@[2001:db8::1]:443?alpn=&insecure=0#inner%20name",
            "Key" to "hysteria2://example.com:443?auth=p%2Bss&sni=example.com",
            "AnyTLS" to "anytls://p%3Ass@example.com/#inner",
            "TUIC" to "tuic://$id:p%40ss@example.com:443?alpn=custom&udp_relay_mode=quic",
            "Naive" to "naive+quic://user:p%3Ass@[2001:db8::1]",
            "VMess" to vmess(json()),
        )) {
            val original = doc(uri, key)
            assertTrue(OtherUriProtocolForms.supported(original))
            assertFalse(OtherUriProtocolForms.rawOnly(original))
            assertFalse(OtherUriProtocolForms.invalid(original))
            val model = parsed(uri)
            for ((field, value) in model.fields) assertTrue(model.edit(field, value) == uri)
            assertTrue(original.source() == key to uri)
        }
    }
    @Test fun editChangesOnlyOriginalSourceAndPreservesFragmentAndOptions() {
        val uri = "hy2://example.com:00443?auth=p%2Bss&alpn=&sni=example.com#inner%20name"
        val original = doc(uri, "Hysteria2")
        val edited = parsed(uri).edit("host", "2001:db8::2")
        assertTrue(edited == uri.replace("//example.com:", "//[2001:db8::2]:"))
        val draft = EditorDraft(Profile("other-uri-test", "Outer", "hysteria2", "vpn", original.render()))
        draft.set("AccessKey", "Hysteria2", edited)
        assertTrue(draft.document.source() == "Hysteria2" to edited)
        assertTrue(draft.document.value("AccessKey", "Key").isEmpty())
        assertTrue(draft.source.text == original.set("AccessKey", "Hysteria2", edited).render())
        assertTrue(original.source()?.second == uri)
        draft.undo()
        assertTrue(draft.source.text == original.render())
    }
    @Test fun credentialsAreEncodedOnceWithoutFormPlusSemantics() {
        val uri = "naive+quic://u%2Bser:p%40ss@[::1]:443?sni=example.com#keep"
        val edited = parsed(uri).edit("username", "é+@/%")
        assertTrue(parsed(edited).fields["username"] == "é+@/%")
        assertTrue(edited.endsWith(":p%40ss@[::1]:443?sni=example.com#keep"))
        val query = "hy2://host:443?auth=old+password&fp=chrome"
        assertTrue(parsed(query).fields["auth"] == "old password")
        assertTrue(parsed(parsed(query).edit("auth", "a+b c")).fields["auth"] == "a+b c")
    }
    @Test fun unknownDuplicateAndAmbiguousInputsStayProtectedAndRaw() {
        for (uri in listOf(
            "hy2://p@host:443?future=x", "hy2://p@host:443?sni=a&sni=b",
            "hy2://p@host:443?%73ni=a&sni=b", "hy2://p@host:443?auth=second",
            "anytls://u:p@host", "anytls://%ff@host", "tuic://$id:p@host:443/path",
            "naive+quic://u:p@host?sni=x;future=y", "hy2://%Q0@host:443",
        )) {
            val original = doc(uri)
            assertTrue(OtherUriProtocolForms.protected(original))
            assertTrue(OtherUriProtocolForms.rawOnly(original))
            assertFalse(OtherUriProtocolForms.invalid(original))
            assertTrue(original.source()?.second == uri)
        }
    }
    @Test fun validationRejectsInvalidEndpointsCredentialsAndOptions() {
        for (uri in listOf(
            "hy2://p@host", "hy2://p@host:0", "hy2://p@host:65536", "hy2://@host:443",
            "hy2://p%0Aq@host:443", "hy2://p@host:443?sni=", "hy2://p@host:443?fp=",
            "hy2://p@host:443?alpn=h2", "anytls://p@host?insecure=1",
            "tuic://invalid:p@host:443", "tuic://$id:p@host:443?alpn=h3,h2",
            "tuic://$id:p@host:443?udp_relay_mode=native", "naive+quic://u%3Ax:p@host",
        )) assertTrue(OtherUriProtocolForms.invalid(doc(uri)) || OtherUriProtocolForms.rawOnly(doc(uri)))
    }
    @Test fun exactSourceAndNaiveTransportDiscrimination() {
        assertFalse(OtherUriProtocolForms.supported(doc("naive+https://u:p@host", "Naive")))
        assertFalse(OtherUriProtocolForms.supported(ConfigDocument.parse("[AccessKey]\nType = naive\n")))
        val ambiguous = doc("hy2://p@host:443").set("AccessKey", "AnyTLS", "anytls://p@host")
        assertTrue(OtherUriProtocolForms.rawOnly(ambiguous))
        val mismatch = doc("anytls://p@host", "TUIC")
        assertTrue(OtherUriProtocolForms.rawOnly(mismatch))
        assertTrue(OtherUriProtocolForms.protected(doc("broken", "VMess")))
        assertFalse(OtherUriProtocolForms.protected(doc("ssh://u@host")))
    }
    @Test fun vmessVariantsPreserveJsonRepresentationsAndAlpnLists() {
        val input = json(",\"alpn\":\"h2,h2,http/1.1\",\"ps\":\"fixture\",\"aid\":\"0\"")
        for (url in listOf(false, true)) for (padding in listOf(false, true)) {
            val uri = vmess(input, url, padding)
            val model = parsed(uri)
            assertTrue(model.valid)
            assertTrue(model.edit("port", "443") == uri)
            val changed = model.edit("port", "8443")
            val decoded = String((if ('-' in changed || '_' in changed) Base64.getUrlDecoder() else Base64.getDecoder()).decode(changed.removePrefix("vmess://")), Charsets.UTF_8)
            assertTrue(decoded == input.replace("\"port\":443", "\"port\":8443"))
        }
    }
    @Test fun vmessRawFallbackAndValidationAreStrict() {
        for (input in listOf(
            json(",\"future\":\"x\""), json(",\"id\":\"$id\""), json(",\"sni\":null"),
            json().replace("\"tcp\"", "\"ws\""), json().replace("\"tls\"}", "\"reality\"}"),
            json(",\"extra\":\"{}\""), json(",\"scy\":\"\""), json() + "{}",
        )) assertNull(OtherUriParser.parse(vmess(input)))
        for (input in listOf(
            json().replace("2001:db8::1", "host:443"), json().replace("2001:db8::1", "u@host"),
            json(",\"alpn\":\"h2,,h2\""), json(",\"aid\":1"),
        )) assertTrue(OtherUriParser.parse(vmess(input))?.valid != true)
        assertNull(OtherUriParser.parse("vmess://%%%"))
        assertNull(OtherUriParser.parse(vmess(json()) + "#fragment"))
    }
    @Test fun absentOptionalFieldsAreAddedOnlyOnExplicitChange() {
        val uri = "anytls://p@host/#keep"
        val model = parsed(uri)
        assertTrue(model.edit("sni", "host") == uri)
        assertTrue(model.edit("sni", "example.com") == "anytls://p@host/?sni=example.com#keep")
        val raw = vmess(json())
        val vm = parsed(raw)
        assertTrue(vm.edit("scy", "auto") == raw)
        assertTrue(parsed(vm.edit("alpn", "h2,h2")).fields["alpn"] == "h2,h2")
    }
    @Test fun structuralHostEditsAreRejectedWithoutChangingDocument() {
        for (uri in listOf("hy2://p@host:443?sni=host#keep", vmess(json()))) {
            val original = doc(uri)
            val model = parsed(uri)
            val field = if (uri.startsWith("vmess:")) "add" else "host"
            for (host in listOf("other?insecure=1#", "user@other", "other/path", "other\\path",
                "other%3fquery", "other%", "[::1]", "[broken", "::invalid", "host:443")) {
                assertTrue(runCatching { model.edit(field, host) }.isFailure)
                assertNull(otherUriCandidate(uri, model.fields + (field to host)))
                assertTrue(original.source()?.second == uri)
            }
            assertTrue(parsed(model.edit(field, "2001:db8::2")).valid)
        }
    }
    @Test fun hostOnlyCandidatesKeepSniAbsentAcrossAllFiveProtocols() {
        for (uri in listOf(
            "hy2://p@host:443#keep", "anytls://p@host/#keep",
            "tuic://$id:p@host:443#keep", "naive+quic://u:p@host#keep", vmess(json()),
        )) {
            val model = parsed(uri)
            val field = if (uri.startsWith("vmess:")) "add" else "host"
            val candidate = requireNotNull(otherUriCandidate(uri, model.fields + (field to "other")))
            assertTrue(candidate == model.edit(field, "other"))
            assertTrue(parsed(candidate).fields["sni"] == "other")
            // The original default is unchanged in the buffer, even after host reparsing.
            val explicit = requireNotNull(otherUriCandidate(uri, model.fields + (field to "other") + ("sni" to "explicit")))
            assertTrue(parsed(explicit).fields["sni"] == "explicit")
        }
    }
    @Test fun bufferedInvalidEditsRecoverWithoutCommittingRawFallback() {
        for (uri in listOf("anytls://p@host", vmess(json()), vmess(json(",\"sni\":\"host\",\"scy\":\"auto\"")))) {
            val model = parsed(uri)
            for (field in listOf("port", "sni") + if (uri.startsWith("vmess:")) listOf("scy") else emptyList()) {
                val buffers = model.fields + (field to "")
                assertNull(otherUriCandidate(uri, buffers))
                assertTrue(parsed(uri).valid)
                assertTrue(otherUriCandidate(uri, buffers + (field to model.fields.getValue(field))) == uri)
                val replacement = when (field) { "port" -> "8443"; "sni" -> "other"; else -> "aes-128-gcm" }
                val recovered = requireNotNull(otherUriCandidate(uri, buffers + (field to replacement)))
                assertTrue(parsed(recovered).fields[field] == replacement)
            }
        }
        // Imported default representations remain raw-only, even though clear/retype is buffered.
        assertNull(OtherUriParser.parse(vmess(json(",\"sni\":\"\""))))
        assertNull(OtherUriParser.parse(vmess(json(",\"scy\":null"))))
    }
    @Test fun errorsAndDebugRepresentationsDoNotExposeSecrets() {
        val uri = "anytls://synthetic-secret@host"
        assertFalse(parsed(uri).toString().contains("synthetic-secret"))
        assertNull(OtherUriParser.parse("anytls://synthetic-secret%xx@host"))
    }
}
