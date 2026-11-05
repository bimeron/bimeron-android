package org.bimeron.app

import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class SharedRawSafetyTest {
    @Test fun utf8ImportRecognizesEverySourceWithoutChangingBytes() {
        Conf.sources.forEach { source ->
            val text = "\uFEFF" + Conf.fromKey("Fixture", "socks5://host:1080")
                .replace("SOCKS5 = socks5://host:1080", "$source = fixture").replace("\n", "\r\n")
            assertArrayEquals(text.toByteArray(), Conf.read(text.toByteArray()).toByteArray())
            assertEquals(source, Conf.parse(text).protocol)
            assertEquals(text, ConfigDocument.parse(text).render())
            assertEquals(text.replace("Name = Fixture", "Name = Renamed"), Conf.rename(text, "Renamed"))
        }
    }

    @Test fun unknownSectionAndFieldsRemainRawAndUndoRestoresExactBytes() {
        val text = "\uFEFF[Profile]\r\nName = Fixture\r\n[AccessKey]\r\nType = socks5\r\nServer = host\r\nPort = 1080\r\nFuture = opaque\r\n[FutureTransport]\r\n# keep\r\nToken = secret\r\n"
        val draft = EditorDraft(Profile("fixture", "Fixture", "socks5", "vpn", text))
        assertEquals(text, draft.document.render())
        assertTrue(ProtocolForms.rawOnly(draft.document))
        draft.set("Profile", "Name", "Renamed")
        assertEquals(text.replace("Name = Fixture", "Name = Renamed"), draft.source.text)
        draft.undo()
        assertEquals(text, draft.source.text)
        assertFalse(draft.dirty)
        draft.update(TextFieldValue(text.replace("FutureTransport", "AnotherTransport")))
        assertFalse(draft.rawInvalid)
        draft.undo()
        assertEquals(text, draft.source.text)
    }

    @Test fun everyRawSchemaUsesCredentialProtectionAndSafeTypeLabels() {
        listOf(
            "[AccessKey]\nType = sentinel/path?token=secret\n",
            "[AccessKey]\nFutureSource = secret://credential\n",
            "[FutureTransport]\nToken = secret\n",
            "[AccessKey]\nTrojan = trojan://secret@host\n",
        ).forEach { text ->
            val doc = ConfigDocument.parse(text)
            assertTrue(ProtocolForms.protected(doc))
            assertTrue(ProtocolForms.rawOnly(doc))
            assertFalse(ProtocolForms.typeLabel(doc).orEmpty().contains("secret"))
            assertEquals(text, doc.render())
        }
    }

    @Test fun vlessTypeLabelIsStaticForIncompleteDrafts() {
        assertEquals("VLESS", ProtocolForms.typeLabel(ConfigDocument.parse("[AccessKey]\nType = vless\nID = secret\n")))
    }

    @Test fun unknownAccessKeyFieldsForceAllCurrentFormsToRaw() {
        listOf("ss", "socks5", "vless").forEach { type ->
            val doc = ConfigDocument.parse("[AccessKey]\nType = $type\nFuture = secret\n")
            assertTrue(ProtocolForms.rawOnly(doc))
        }
        assertFalse(ProtocolForms.rawOnly(ConfigDocument.parse("[AccessKey]\nType = socks5\nServer = host\nPort = 1080\n")))
        assertTrue(ProtocolForms.rawOnly(ConfigDocument.parse("[AccessKey]\nType = future\n")))
        listOf("ss", "socks5", "vless").forEach { type ->
            val text = "[AccessKey]\nType = $type\n[FutureTransport]\nToken = secret\n"
            val doc = ConfigDocument.parse(text)
            assertNotNull(ProtocolForms.descriptor(doc))
            assertTrue(ProtocolForms.rawOnly(doc))
            assertEquals(text, doc.render())
        }
    }

    @Test fun valueReplacementPreservesWhitespaceLineEndingsAndUndo() {
        for (ending in listOf("\n", "\r\n")) {
            for (value in listOf("", "Fixture", "\"Fixture name\"")) {
                val field = "  Name \t= \t$value  \t"
                val text = "\uFEFF" + Conf.fromKey("Fixture", "socks5://host:1080")
                    .replace("Name = Fixture", field).replace("\n", ending)
                val expected = text.replace(field, "  Name \t= \t" +
                    (if (value.isEmpty()) "  \t" else "") + "Renamed" +
                    (if (value.isEmpty()) "" else "  \t"))
                val draft = EditorDraft(Profile("fixture", "Fixture", "socks5", "vpn", text))
                draft.set("Profile", "Name", "Renamed")
                assertEquals(expected, draft.source.text)
                assertEquals("Renamed", ConfigDocument.parse(draft.source.text).value("Profile", "Name"))
                if (value.isEmpty()) assertEquals(expected, Conf.ensureName(text, "Renamed"))
                draft.undo()
                assertEquals(text, draft.source.text)
                assertFalse(draft.dirty)
            }
        }
    }

    @Test fun crlfDisabledMetadataAndBomImportHelpersPreserveSurroundingText() {
        val text = "\uFEFF" + Conf.fromKey("Fixture", "socks5://host:1080").replace("\n", "\r\n")
        val disabled = text + "# bimeron:disabled-rule\r\n#| [Rule]\r\n#| Domain = example.com\r\n#| Action = direct\r\n# bimeron:end-rule\r\n"
        assertEquals(disabled, ConfigDocument.parse(disabled).render())
        assertEquals(disabled.replace("Name = Fixture", "Name = Renamed"), ConfigDocument.parse(disabled).set("Profile", "Name", "Renamed").render())
        assertEquals(text, Conf.ensureName(text, "Unused"))
        assertEquals(text.replace("Name = Fixture", "Name = Fallback"), Conf.ensureName(text.replace("Name = Fixture", "Name = "), "Fallback"))
        val file = text.replace("SOCKS5 = socks5://host:1080", "File = key.txt")
        assertEquals(text.replace("SOCKS5 =", "Key ="), Conf.inlineFile(file, "\uFEFFsocks5://host:1080\r\n"))
    }
}
