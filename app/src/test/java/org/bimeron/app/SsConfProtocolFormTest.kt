package org.bimeron.app

import org.junit.Assert.*
import org.junit.Test

class SsConfProtocolFormTest {
    private val uri = "ssconf://config.example/private%2Fpath/config.json?token=a=b&next=%2Fkey#display-name"

    private fun text(key: String, source: String = uri) =
        "# keep preamble\n[Profile]\nName = Original\n[AccessKey]\n; keep source comment\n  $key \t=  \"$source\"  \nFuture = \"opaque=;#value\"\n[Routing]\nFutureRoute = untouched\n"

    @Test fun directAndGenericSourcesKeepExactUriAndUnknownLines() {
        for (key in listOf("SSConf", "Key")) {
            val original = text(key)
            val doc = ConfigDocument.parse(original)
            assertEquals(key to uri, doc.source())
            assertEquals(original, doc.render())
            val renamed = doc.set("Profile", "Name", "Renamed")
            assertEquals(original.replace("Name = Original", "Name = Renamed"), renamed.render())
            assertEquals(key to uri, ConfigDocument.parse(renamed.render()).source())
        }
    }

    @Test fun sourceReplacementPreservesSpacingUnknownLinesAndOpaqueUri() {
        val replacement = "ssconf://other.example/new%2Fpath?token=x=y&mode=%23raw#new-name"
        for (key in listOf("SSConf", "Key")) {
            val changed = ConfigDocument.parse(text(key)).set("AccessKey", key, replacement)
            assertEquals(text(key, replacement), changed.render())
            assertEquals(key to replacement, ConfigDocument.parse(changed.render()).source())
        }
    }

    @Test fun standaloneHooksRecognizeOnlyActualSources() {
        for (key in listOf("SSConf", "Key")) {
            val doc = ConfigDocument.parse(text(key))
            assertTrue(SsConfForms.supported(doc))
            assertTrue(SsConfForms.protected(doc))
            assertFalse(SsConfForms.rawOnly(doc))
            assertFalse(SsConfForms.invalid(doc))
        }
        for (source in listOf("Type = ssconf", "Key = ss://example", "ssconf = ssconf://example")) {
            val doc = ConfigDocument.parse("[AccessKey]\n$source\n")
            assertFalse(SsConfForms.supported(doc))
            assertTrue(SsConfForms.rawOnly(doc))
        }
    }

    @Test fun malformedSourcesAreProtectedWithoutLeakingParserErrors() {
        val invalid = listOf("ssconf://", "ssconf://user:secret@host/path", "ssconf://host/a b",
            "ssconf://host/%zz", "https://host/path", "SSCONF://host/path", "ssconf:///path")
        for (value in invalid) {
            val doc = ConfigDocument.parse(text("SSConf", value))
            assertTrue(value, SsConfForms.protected(doc))
            assertTrue(value, SsConfForms.invalid(doc))
        }
        val uppercase = ConfigDocument.parse(text("Key", "SSCONF://host/path"))
        assertTrue(SsConfForms.protected(uppercase))
        assertTrue(SsConfForms.rawOnly(uppercase))
    }

    @Test fun unrepresentableAndAmbiguousSourcesStayRawAndUnchanged() {
        for (original in listOf(text("SSConf", "ssconf://host/\u0001"),
            text("Key").replace("Future =", "SSConf = ssconf://other\nFuture ="))) {
            val doc = ConfigDocument.parse(original)
            assertTrue(SsConfForms.rawOnly(doc))
            assertEquals(original, doc.render())
        }
    }

    @Test fun draftEditKeepsSelectedSourceKeyAndCanUndo() {
        for (key in listOf("SSConf", "Key")) {
            val original = text(key)
            val draft = EditorDraft(Profile("ssconf-test", "Original", "SSConf", "vpn", original))
            val replacement = "ssconf://other.example/opaque%2Fpath?future=x=y#name"
            draft.set("AccessKey", draft.document.source()!!.first, replacement)
            assertEquals(text(key, replacement), draft.source.text)
            assertEquals(key to replacement, draft.document.source())
            draft.undo()
            assertEquals(original, draft.source.text)
        }
    }

    @Test fun displayTypeIsLowercaseSsconfWithoutChangingStoredKeys() {
        for (key in listOf("SSConf", "Key")) {
            val doc = ConfigDocument.parse(text(key))
            assertEquals("ssconf", SsConfForms.displayType(doc))
            assertEquals(key, doc.source()!!.first)
        }
        assertEquals("ssconf", SsConfForms.displayType(ConfigDocument.parse(text("SSConf", "ssconf://host/\u0001"))))
        for (source in listOf("Key = ss://example", "Type = ss\nServer = host", "SS = ss://example")) {
            assertNull(source, SsConfForms.displayType(ConfigDocument.parse("[AccessKey]\n$source\n")))
        }
    }

    @Test fun linkInvalidAlsoCoversALinkEditedOutOfTheForm() {
        val stripped = ConfigDocument.parse(text("Key", "config.example/path"))
        assertTrue(SsConfForms.rawOnly(stripped))
        assertFalse(SsConfForms.invalid(stripped))
        assertTrue(SsConfForms.linkInvalid(stripped, editingLink = true))
        assertFalse("Outside the link field another source is not an ssconf error", SsConfForms.linkInvalid(stripped, editingLink = false))
        val valid = ConfigDocument.parse(text("Key"))
        assertFalse(SsConfForms.linkInvalid(valid, editingLink = true))
        assertTrue(SsConfForms.linkInvalid(ConfigDocument.parse(text("SSConf", "ssconf://host/a b")), editingLink = false))
    }

    @Test fun profileListShowsSsconfInLowercase() {
        for (key in listOf("SSConf", "Key")) {
            val profile = Conf.parse(Conf.fromKey("List", uri).replace("\nSSConf = ", "\n$key = "))
            assertEquals(key, profile.protocol)
            assertEquals("ssconf", profileProtocol(profile))
        }
        assertEquals("Shadowsocks", profileProtocol(Conf.parse(Conf.fromKey("List", "ss://YWVzLTEyOC1nY206cGFzcw@example.com:443"))))
        assertEquals("VLESS", profileProtocol(Profile("id", "List", "VLESS", "vpn", "")))
    }
}
