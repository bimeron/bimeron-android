package org.bimeron.app

import org.junit.Assert.*
import org.junit.Test

class ShadowTlsProtocolFormTest {
    private val inner = "[AccessKey]\nType = ss\nServer = public.invalid\nPort = 443\nMethod = aes-256-gcm\nPassword = INNER_SENTINEL\n"
    private val wrapper = "[ShadowTLS]\nServerName = cover.invalid\nPassword = \"OUTER=SECRET\"\n"
    private fun doc(text: String = inner + wrapper) = ConfigDocument.parse(text)

    @Test fun editPreservesInnerUnknownCommentsAndQuotedSecret() {
        val text = inner + "FutureInner = keep\n; preserve\n" + wrapper
        val before = doc(text)
        assertFalse(ShadowTlsProtocolForm.rawOnly(before))
        assertEquals(text, before.render())
        val after = before.set("ShadowTLS", "ServerName", "new.invalid")
        assertEquals(text.replace("cover.invalid", "new.invalid"), after.render())
        assertEquals("OUTER=SECRET", after.value("ShadowTLS", "Password"))
        assertEquals("INNER_SENTINEL", after.value("AccessKey", "Password"))
    }
    @Test fun unknownWrapperFieldsRemainRawUnchanged() {
        for (field in listOf("Version = 3", "Strict = true", "Future = SECRET")) {
            val text = inner + wrapper + field + "\n"
            assertTrue(ShadowTlsProtocolForm.rawOnly(doc(text)))
            assertEquals(text, doc(text).render())
        }
    }
    @Test fun duplicateWrapperAndKeyAreRejectedWithoutLeakingSecrets() {
        for (text in listOf(inner + wrapper + wrapper, inner + wrapper + "Password = SENTINEL\n")) {
            try { doc(text); fail("duplicate accepted") } catch (error: ProfileError) {
                assertFalse(error.message.orEmpty().contains("SENTINEL"))
                assertFalse(error.message.orEmpty().contains("OUTER"))
            }
        }
    }
    @Test fun incompatibleDraftsArePreserved() {
        val base = doc()
        for (next in listOf(base.set("AccessKey", "Type", "socks5"), base.set("AccessKey", "Method", "aes-192-gcm"), base.set("AccessKey", "Plugin", "v2ray-plugin"), base.set("AccessKey", "ALPN", "h2,h3"))) {
            assertTrue(ShadowTlsProtocolForm.rawOnly(next))
            assertTrue(ShadowTlsProtocolForm.protected(next))
            assertEquals("OUTER=SECRET", next.value("ShadowTLS", "Password"))
        }
        assertTrue(ShadowTlsProtocolForm.rawOnly(doc("[AccessKey]\nSS = ss://secret\n" + wrapper)))
    }
    @Test fun validatesDraftWithoutReturningCredentialDiagnostics() {
        assertFalse(ShadowTlsProtocolForm.invalid(doc()))
        for (name in listOf("", "bad name", "-bad.invalid", "a".repeat(64) + ".invalid", "https://cover.invalid"))
            assertTrue(ShadowTlsProtocolForm.invalid(doc().set("ShadowTLS", "ServerName", name)))
        for (name in listOf("cover.invalid.", "127.0.0.1", "::1", "::ffff:192.0.2.1"))
            assertFalse(ShadowTlsProtocolForm.invalid(doc().set("ShadowTLS", "ServerName", name)))
        assertTrue(ShadowTlsProtocolForm.invalid(doc().set("ShadowTLS", "Password", "")))
        assertTrue(ShadowTlsProtocolForm.rawOnly(doc().set("ShadowTLS", "Password", "a\nb")))
        assertFalse(ShadowTlsProtocolForm.present(doc(inner + "# [ShadowTLS]\n")))
    }
    @Test fun encryptedInnerRestrictionsAndExtendedXhttpStayRaw() {
        val vless = doc().set("AccessKey", "Type", "vless").set("AccessKey", "Security", "tls")
        assertTrue(ShadowTlsProtocolForm.supported(vless))
        assertFalse(ShadowTlsProtocolForm.supported(vless.set("AccessKey", "Security", "none")))
        assertFalse(ShadowTlsProtocolForm.supported(vless.set("AccessKey", "Network", "future")))
        assertTrue(ShadowTlsProtocolForm.rawOnly(vless.set("AccessKey", "XHTTPExtra", "{}")))
        for (type in listOf("http", "https", "masque", "hysteria2", "tuic", "vmess", "anytls"))
            assertFalse(ShadowTlsProtocolForm.supported(doc().set("AccessKey", "Type", type)))
    }
    @Test fun naiveExplicitIsH2AndUnknownTransportIsRetainedRaw() {
        val text = "[AccessKey]\nType = naive\nServer = public.invalid\nPort = 443\nUsername = user\nPassword = inner\n" + wrapper
        assertTrue(ShadowTlsProtocolForm.supported(doc(text)))
        for (option in listOf("QUIC = true", "Network = quic", "Future = keep")) {
            val next = text.replace("[ShadowTLS]", "$option\n[ShadowTLS]")
            assertTrue(ShadowTlsProtocolForm.rawOnly(doc(next)))
            assertEquals(next, doc(next).render())
        }
        assertTrue(ShadowTlsProtocolForm.rawOnly(doc("[AccessKey]\nNaive = naive+quic://user:secret@public.invalid\n" + wrapper)))
    }
    @Test fun sectionWhitespaceUsesParserSemantics() {
        val spaced = doc((inner + wrapper).replace("[ShadowTLS]", "[ ShadowTLS ]").replace("[AccessKey]", "[ AccessKey ]"))
        assertTrue(ShadowTlsProtocolForm.present(spaced))
        assertTrue(ShadowTlsProtocolForm.protected(spaced))
        assertFalse(ShadowTlsProtocolForm.rawOnly(spaced))
        assertTrue(ShadowTlsProtocolForm.rawOnly(doc(spaced.render() + "Future = keep\n")))
        val naive = doc("[ AccessKey ]\nType = naive\nQUIC = true\n[ ShadowTLS ]\nServerName = cover.invalid\nPassword = secret\n")
        assertTrue(ShadowTlsProtocolForm.rawOnly(naive))
    }
    @Test fun firstLineBomWrapperUsesParsedPresenceAndValidation() {
        val text = "\uFEFF" + (wrapper + inner).replace("\n", "\r\n")
        val parsed = doc(text)
        assertTrue(ShadowTlsProtocolForm.present(parsed))
        assertTrue(ShadowTlsProtocolForm.protected(parsed))
        assertFalse(ShadowTlsProtocolForm.rawOnly(parsed))
        assertFalse(ShadowTlsProtocolForm.invalid(parsed))
        assertTrue(ShadowTlsProtocolForm.invalid(parsed.set("ShadowTLS", "ServerName", "")))
        assertTrue(ShadowTlsProtocolForm.present(doc("\uFEFF[ShadowTLS]\r\n" + inner.replace("\n", "\r\n"))))
        assertTrue(ShadowTlsProtocolForm.invalid(doc("\uFEFF[ShadowTLS]\r\n" + inner.replace("\n", "\r\n"))))
        assertTrue(ShadowTlsProtocolForm.rawOnly(parsed.set("ShadowTLS", "Future", "keep")))
        assertTrue(ShadowTlsProtocolForm.rawOnly(parsed.set("ShadowTLS", "Password", "a\nb")))
        assertEquals(text, parsed.render())
        val draft = EditorDraft(Profile("bom-wrapper", "Fixture", "ss", "vpn", text))
        draft.set("ShadowTLS", "ServerName", "new.invalid")
        assertEquals(text.replace("cover.invalid", "new.invalid"), draft.source.text)
        draft.undo()
        assertEquals(text, draft.source.text)
        assertFalse(draft.dirty)
    }
    @Test fun firstLineBomNaiveUnknownOptionsStayRawWithExactCrLf() {
        val base = "\uFEFF[AccessKey]\nType = naive\nServer = public.invalid\nPort = 443\nUsername = user\nPassword = inner\n"
        assertTrue(ShadowTlsProtocolForm.supported(doc(base + wrapper)))
        for (option in listOf("QUIC = true", "Network = quic", "Future = keep")) {
            val text = (base + option + "\n" + wrapper).replace("\n", "\r\n")
            val parsed = doc(text)
            assertFalse(ShadowTlsProtocolForm.supported(parsed))
            assertTrue(ShadowTlsProtocolForm.rawOnly(parsed))
            assertTrue(ShadowTlsProtocolForm.protected(parsed))
            assertEquals(text, parsed.render())
            val draft = EditorDraft(Profile("bom-naive", "Fixture", "naive", "vpn", text))
            draft.set("ShadowTLS", "ServerName", "new.invalid")
            assertEquals(text.replace("cover.invalid", "new.invalid"), draft.source.text)
            draft.undo()
            assertEquals(text, draft.source.text)
            assertFalse(draft.dirty)
        }
    }
    @Test fun malformedUtf16PasswordIsInvalid() {
        for (password in listOf("\uD800", "\uDC00", "ok\uD800bad"))
            assertTrue(ShadowTlsProtocolForm.invalid(doc().set("ShadowTLS", "Password", password)))
        assertFalse(ShadowTlsProtocolForm.invalid(doc().set("ShadowTLS", "Password", "valid \uD83D\uDD12")))
    }
    @Test fun draftUndoRestoresExactText() {
        val text = inner + wrapper
        val draft = EditorDraft(Profile("fixture", "Fixture", "ss", "vpn", text))
        draft.set("ShadowTLS", "Password", "new=secret")
        draft.undo()
        assertEquals(text, draft.source.text)
        assertFalse(draft.dirty)
    }
}
