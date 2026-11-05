package org.bimeron.app

import org.junit.Assert.*
import org.junit.Test

class ConnectProtocolFormsTest {
    private val forms = listOf(HttpConnectForm, NaiveH2Form, MasqueForm)
    private fun doc(type: String, extra: String = "") = ConfigDocument.parse(
        "[AccessKey]\nType = $type\nServer = proxy.example\nPort = 443\n$extra",
    )

    @Test fun facadeDispatchesExplicitConnectFormsAndDefersRawValidation() {
        for ((type, form) in listOf("http" to HttpConnectForm, "https" to HttpConnectForm,
            "naive" to NaiveH2Form, "masque" to MasqueForm)) {
            val valid = doc(type, "Username = USER_SENTINEL\nPassword = PASS_SENTINEL\n")
            assertSame(form, ProtocolForms.descriptor(valid))
            assertTrue(ProtocolForms.supported(valid))
            assertTrue(ProtocolForms.protected(valid))
            assertFalse(ProtocolForms.rawOnly(valid))
            assertFalse(ProtocolForms.invalid(valid))
            val invalid = valid.set("AccessKey", "Port", "65536")
            assertTrue(ProtocolForms.invalid(invalid))
            assertFalse(ProtocolForms.rawOnly(invalid))
            val raw = invalid.set("AccessKey", "Future", "SECRET_SENTINEL")
            assertTrue(ProtocolForms.supported(raw))
            assertTrue(ProtocolForms.protected(raw))
            assertTrue(ProtocolForms.rawOnly(raw))
            assertFalse(ProtocolForms.invalid(raw))
            assertEquals(form.fields.map { it.key }, ProtocolForms.fields(valid))
        }
    }

    @Test fun facadeProtectsConnectUrisAndRetainsRawTransportIncludingQuicExclusion() {
        val uris = listOf("http://USER_SENTINEL:PASS_SENTINEL@host:8080",
            "https://USER_SENTINEL:PASS_SENTINEL@host:443",
            "naive+https://USER_SENTINEL:PASS_SENTINEL@host?sni=tls.example&insecure=0#name",
            "masque://USER_SENTINEL:PASS_SENTINEL@host/?udp_path=SECRET&ca=%2Fca.pem",
            "naive+quic://USER_SENTINEL:PASS_SENTINEL@host#name")
        for (source in listOf("HTTP", "Naive", "MASQUE", "Key", "File")) for (uri in uris) {
            val text = "[AccessKey]\n$source = $uri\n# retain\n"
            val original = ConfigDocument.parse(text)
            assertNull(ProtocolForms.descriptor(original))
            assertFalse(ProtocolForms.supported(original))
            assertTrue(ProtocolForms.rawOnly(original))
            assertFalse(ProtocolForms.invalid(original))
            assertTrue(ProtocolForms.protected(original))
            assertEquals(text, original.render())
            val changed = original.set("Profile", "Name", "Edited")
            assertEquals(uri, changed.source()!!.second)
            assertTrue(changed.render().contains(text))
            assertTrue(ProtocolForms.rawOnly(changed))
            assertEquals(ProtocolForms.protected(original), ProtocolForms.protected(changed))
        }
    }

    @Test fun registrationContractAndSecrets() {
        assertEquals(setOf("http", "https"), HttpConnectForm.types)
        assertEquals(setOf("naive"), NaiveH2Form.types)
        assertEquals(setOf("masque"), MasqueForm.types)
        assertEquals(setOf("http", "https", "naive+https", "masque"), forms.flatMap { it.sourceSchemes }.toSet())
        assertFalse(forms.any { "naive+quic" in it.sourceSchemes })
        for (form in forms) {
            assertEquals(ProtocolKeyboardType.NUMBER, form.fields.single { it.key == "Port" }.keyboardType)
            for (field in form.fields.filter { it.key in setOf("Username", "Password", "CAFile", "MASQUEPath") }) {
                assertTrue(field.secret)
                assertEquals(ProtocolKeyboardType.PASSWORD, field.keyboardType)
                assertTrue(field.labelRes != 0)
                assertNull(field.options)
            }
        }
        assertEquals(listOf("Server", "Port", "Username", "Password"), HttpConnectForm.fields.map { it.key })
    }

    @Test fun pairedCredentialsAndHeaderInjection() {
        for (form in forms) {
            val base = doc(form.types.first(), "Username = USER_SENTINEL\nPassword = PASS_SENTINEL\n")
            assertFalse(form.invalid(base))
            assertTrue(form.invalid(base.set("AccessKey", "Username", "")))
            assertTrue(form.invalid(base.set("AccessKey", "Password", "")))
            for (user in listOf("bad:user", "bad\ruser", "bad\nuser"))
                assertTrue(form.invalid(base.set("AccessKey", "Username", user)))
            assertTrue(form.invalid(base.set("AccessKey", "Password", "bad\npass")))
            assertFalse(form.invalid(base.set("AccessKey", "Password", "pass:with:colon")))
            assertEquals(form === NaiveH2Form, form.invalid(doc(form.types.first())))
        }
    }

    @Test fun tlsDefaultsNamesAndIpv6() {
        for (form in forms) {
            val base = doc(form.types.first(), "Username = u\nPassword = p\n").set("AccessKey", "Server", "::1")
            assertFalse(form.invalid(base))
            if (form !== HttpConnectForm) {
                assertFalse(form.invalid(base.set("AccessKey", "ServerName", "tls.example")))
                assertTrue(form.invalid(base.set("AccessKey", "ServerName", "bad/name")))
            }
            assertTrue(form.invalid(base.set("AccessKey", "Server", "bad/host")))
        }
        for (type in HttpConnectForm.types) assertFalse(HttpConnectForm.invalid(doc(type)))
    }

    @Test fun masqueTemplatesAndCaAreValidatedWithoutRewriting() {
        val base = doc("masque")
        for (path in listOf("", "/.well-known/masque/udp/{target_host}/{target_port}/", "/custom/{target_port}/{target_host}"))
            assertFalse(MasqueForm.invalid(base.set("AccessKey", "MASQUEPath", path)))
        for (path in listOf("//{target_host}/{target_port}", "/{target_host}", "/{target_host}/{target_port}/{target_port}",
            "/{+target_host}/{target_port}", "/{target_host}/{target_port}?token=SECRET", "/{target_host}/{target_port}#x",
            "/{target_host}/{target_port}/ ", "/{target_host}/{target_port}/{other}")) {
            val changed = base.set("AccessKey", "MASQUEPath", path)
            assertTrue(MasqueForm.invalid(changed))
            assertEquals(path, changed.value("AccessKey", "MASQUEPath"))
        }
        assertFalse(MasqueForm.invalid(base.set("AccessKey", "CAFile", "/synthetic/CA_SENTINEL.pem")))
        assertTrue(MasqueForm.invalid(base.set("AccessKey", "CAFile", "relative/CA_SENTINEL.pem")))
    }

    @Test fun unknownOptionsAndWrappersStayRawAndRoundTrip() {
        for (form in forms) {
            for (extra in listOf("Future = \"\"\n", "Method = stale\n", "PluginOpts = SECRET\n", "Network = h2\n",
                "[ShadowTLS]\nPassword = WRAPPER_SENTINEL\n", "[IKEv2]\nPassword = WRAPPER_SENTINEL\n")) {
                val original = doc(form.types.first(), "Username = USER_SENTINEL\nPassword = \"PASS= SENTINEL\"\n# keep\n$extra")
                assertTrue(form.rawOnly(original))
                val changed = original.set("AccessKey", "Port", "8443")
                assertEquals(original.render().replace("Port = 443", "Port = 8443"), changed.render())
                assertEquals("PASS= SENTINEL", changed.value("AccessKey", "Password"))
                assertEquals(original.render(), ConfigDocument.parse(original.render()).render())
            }
            assertFalse(form.rawOnly(doc(form.types.first())))
        }
    }

    @Test fun uriOptionsAndTransportRemainOpaqueAcrossUnrelatedEdits() {
        val uris = listOf("http://u:p@host:8080", "https://u:p@[::1]:443/", "http://host:80?future=x#name",
            "naive+https://USER_SENTINEL:PASS_SENTINEL@host?sni=tls.example&insecure=0#name",
            "naive+quic://u:p@host", "masque://u:p@host/?udp_path=SECRET&ca=%2Fca.pem&future=1")
        for (source in listOf("HTTP", "Naive", "MASQUE", "Key", "File")) for (uri in uris) {
            val original = ConfigDocument.parse("[AccessKey]\n$source = $uri\n")
            for (form in forms) assertTrue(form.rawOnly(original))
            val changed = original.set("Profile", "Name", "Edited")
            assertEquals(uri, changed.source()!!.second)
            assertTrue(changed.render().contains(original.render()))
        }
    }

    @Test fun failedValidationCorrectionAndUndoRetainSecrets() {
        val text = doc("masque", "Username = USER_SENTINEL\nPassword = PASS_SENTINEL\nMASQUEPath = /{target_host}/{target_port}/\n").render()
        val draft = EditorDraft(Profile("fixture", "Fixture", "masque", "vpn", text))
        draft.set("AccessKey", "MASQUEPath", "/invalid")
        assertTrue(MasqueForm.invalid(draft.document))
        assertEquals("PASS_SENTINEL", draft.document.value("AccessKey", "Password"))
        draft.undo()
        assertEquals(text, draft.source.text)
        assertFalse(MasqueForm.invalid(draft.document))
        assertFalse(draft.dirty)
    }
}
