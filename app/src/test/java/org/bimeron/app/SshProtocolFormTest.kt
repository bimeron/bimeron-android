package org.bimeron.app

import org.junit.Assert.*
import org.junit.Test

class SshProtocolFormTest {
    @get:org.junit.Rule val temp = org.junit.rules.TemporaryFolder()
    private val original = "# fixture\n[AccessKey]\nType = ssh\nServer = host.example\nPort = 22\nUsername = user\nIdentityFile = /data/user/0/org.bimeron.app/files/id_ed25519\nKnownHostsFile = /data/user/0/org.bimeron.app/files/known_hosts\n; keep spacing\n"
    private fun doc() = ConfigDocument.parse(original)
    @Test fun facadeRecognizesExplicitSshAndPreservesInvalidAndRawDocuments() {
        val valid = doc()
        assertSame(SshProtocolForm, ProtocolForms.descriptor(valid))
        assertTrue(ProtocolForms.supported(valid))
        assertFalse(ProtocolForms.rawOnly(valid))
        assertFalse(ProtocolForms.invalid(valid))
        assertTrue(ProtocolForms.protected(valid))
        assertEquals(original, valid.render())

        val invalid = valid.set("AccessKey", "Username", "")
        assertTrue(ProtocolForms.supported(invalid))
        assertFalse(ProtocolForms.rawOnly(invalid))
        assertTrue(ProtocolForms.invalid(invalid))
        assertTrue(ProtocolForms.protected(invalid))
        assertEquals(original.replace("Username = user", "Username = "), invalid.render())

        val text = original + "Future = \"literal=value\"\n"
        val raw = ConfigDocument.parse(text)
        assertTrue(ProtocolForms.supported(raw))
        assertTrue(ProtocolForms.rawOnly(raw))
        assertFalse(ProtocolForms.invalid(raw))
        assertTrue(ProtocolForms.protected(raw))
        assertEquals(text, raw.render())
    }

    @Test fun metadataMasksUsernameAndBothPaths() {
        assertEquals(setOf("ssh"), SshProtocolForm.types)
        assertEquals(setOf("ssh"), SshProtocolForm.sourceSchemes)
        assertEquals(listOf("Server", "Port", "Username", "IdentityFile", "KnownHostsFile"), SshProtocolForm.fields.map { it.key })
        assertEquals(setOf("Username", "IdentityFile", "KnownHostsFile"), SshProtocolForm.fields.filter { it.secret }.map { it.key }.toSet())
        assertTrue(SshProtocolForm.fields.all { it.labelRes != 0 })
        assertNotNull(SshProtocolForm.hintRes)
        assertEquals(ProtocolKeyboardType.NUMBER, SshProtocolForm.fields[1].keyboardType)
        assertTrue(SshProtocolForm.fields.filter { it.secret }.all { it.keyboardType == ProtocolKeyboardType.PASSWORD })
    }

    @Test fun schemaValidationAndOptionalPaths() {
        assertFalse(ProtocolForms.rawOnly(doc()))
        assertFalse(ProtocolForms.invalid(doc()))
        assertTrue(ProtocolForms.invalid(doc().set("AccessKey", "Username", "")))
        // Core requires nonempty username, not a trimmed username or a SOCKS byte limit.
        for (user in listOf(" ", "é".repeat(256))) assertFalse(ProtocolForms.invalid(doc().set("AccessKey", "Username", user)))
        for (port in listOf("", "0", "65536", "abc")) assertTrue(ProtocolForms.invalid(doc().set("AccessKey", "Port", port)))
        for (port in listOf("1", "65535")) assertFalse(ProtocolForms.invalid(doc().set("AccessKey", "Port", port)))
        assertTrue(ProtocolForms.invalid(doc().set("AccessKey", "Server", " ")))
        assertFalse(ProtocolForms.invalid(doc().set("AccessKey", "IdentityFile", "").set("AccessKey", "KnownHostsFile", "")))
    }

    @Test fun unrepresentedOptionsRemainRawAndUntouched() {
        val options = listOf("Password", "Method", "Prefix", "ServerName", "Plugin", "PluginOpts", "MASQUEPath", "CAFile",
            "ID", "Encryption", "Flow", "Network", "Security", "PublicKey", "ShortID", "Fingerprint", "SpiderX", "ALPN",
            "WSPath", "WSHost", "WSEarlyData", "GRPCServiceName", "GRPCAuthority", "GRPCMultiMode", "XHTTPPath", "XHTTPHost", "XHTTPMode", "XHTTPExtra", "XHTTPFuture", "Future")
        for (key in options) for (value in listOf("", "a=b")) {
            val text = original + "  $key = ${Conf.quote(value)}  \n"
            val next = ConfigDocument.parse(text)
            assertTrue("$key=$value", ProtocolForms.rawOnly(next))
            assertEquals(text, next.render())
            assertFalse(ProtocolForms.invalid(next.set("AccessKey", "Port", "")))
        }
    }

    @Test fun unsupportedPathsAndControlsPreserveRawValues() {
        for (key in listOf("IdentityFile", "KnownHostsFile")) {
            for (path in listOf("content://provider/key", "file:///data/key", "~/id_rsa", "keys/id", "C:\\keys\\id", "//host/key")) {
                val next = doc().set("AccessKey", key, path)
                val text = next.render()
                assertTrue(ProtocolForms.rawOnly(next))
                assertEquals(path, ConfigDocument.parse(text).value("AccessKey", key))
                assertEquals(text, ConfigDocument.parse(text).render())
            }
        }
        for (field in SshProtocolForm.fields) {
            val next = doc().set("AccessKey", field.key, "a\nb")
            assertTrue(ProtocolForms.rawOnly(next))
            assertEquals("a\nb", ConfigDocument.parse(next.render()).value("AccessKey", field.key))
        }
    }

    @Test fun editsAndUndoPreserveUnknownLinesAndQuotedPaths() {
        val text = original + "Future = \"literal=value\"\n"
        val draft = EditorDraft(Profile("ssh-fixture", "Fixture", "ssh", "vpn", text))
        draft.set("AccessKey", "IdentityFile", "/data/user/0/app/files/key=one")
        assertEquals(text.replace("IdentityFile = /data/user/0/org.bimeron.app/files/id_ed25519", "IdentityFile = \"/data/user/0/app/files/key=one\""), draft.source.text)
        draft.set("AccessKey", "Username", "another=user")
        assertEquals("another=user", ConfigDocument.parse(draft.source.text).value("AccessKey", "Username"))
        draft.undo()
        assertEquals("user", draft.document.value("AccessKey", "Username"))
        draft.undo()
        assertEquals(text, draft.source.text)
        assertFalse(draft.dirty)
    }

    @Test fun structuredRoundtripKeepsOtherSectionsAndQuotedValues() {
        val text = original + "[Routing]\nDefault = proxy\nFuture = untouched\n"
        val next = ConfigDocument.parse(text).set("AccessKey", "Username", "user=name")
            .set("AccessKey", "KnownHostsFile", "/data/user/0/app/files/known hosts")
        assertFalse(ProtocolForms.rawOnly(next))
        assertFalse(ProtocolForms.invalid(next))
        val restored = ConfigDocument.parse(next.render())
        assertEquals("user=name", restored.value("AccessKey", "Username"))
        assertEquals("/data/user/0/app/files/known hosts", restored.value("AccessKey", "KnownHostsFile"))
        assertEquals("untouched", restored.value("Routing", "Future"))
        assertEquals(next.render(), restored.render())
    }

    /** Decision 8 at the field: only this draft's import placeholders and this profile's own generation files of the right kind. */
    @Test fun typedFilePathsOutsideThisProfileShowAFieldError() {
        val resources = SshProfileResources(java.io.File(temp.root.canonicalFile, "ssh-resources"))
        val id = java.util.UUID.randomUUID()
        val imports = SshDraftResources(id)
        val generation = java.util.UUID.randomUUID()
        val own = "/data/data/org.bimeron.app/files/ssh-resources/profiles/$id/$generation"
        val identity = imports.accept(resources.stage(SshProfileResources.Kind.IDENTITY, "key".byteInputStream()))!!
        val knownHosts = imports.accept(resources.stage(SshProfileResources.Kind.KNOWN_HOSTS, "hosts".byteInputStream()))!!
        fun error(key: String, value: String) = SshProtocolForm.fieldError(doc().set("AccessKey", key, value), key, imports)
        val message = R.string.ssh_form_file_not_imported
        assertNull(error("IdentityFile", ""))
        assertNull(error("IdentityFile", identity))
        assertNull(error("KnownHostsFile", knownHosts))
        assertNull(error("IdentityFile", "$own/identity"))
        assertNull(error("KnownHostsFile", "$own/known_hosts"))
        for (path in listOf("/sdcard/.ssh/id_rsa", "/storage/emulated/0/id_rsa", "/data/user/0/org.bimeron.app/files/id_ed25519",
            "/data/data/org.bimeron.app/files/ssh-resources/profiles/${java.util.UUID.randomUUID()}/$generation/identity",
            "/data/data/org.bimeron.app/files/ssh-resources/profiles/$id/${generation.toString().uppercase()}/identity",
            "/data/data/org.bimeron.app/files/ssh-resources/profiles/$id/$generation/../$generation/identity",
            "/data/data/org.bimeron.app/files/other/profiles/$id/$generation/identity",
            "/bimeron-import/${java.util.UUID.randomUUID()}", "$own/known_hosts", knownHosts)) {
            assertEquals(path, message, error("IdentityFile", path))
        }
        assertEquals(message, error("KnownHostsFile", "$own/identity"))
        assertEquals(message, error("KnownHostsFile", identity))
        // Other fields never get it, and other forms have no field errors.
        assertNull(SshProtocolForm.fieldError(doc(), "Server", imports))
        assertNull(SshProtocolForm.fieldError(doc(), "Username", imports))
        assertNull(ShadowsocksForm.fieldError(doc(), "IdentityFile", imports))
        // A disposed draft's placeholders are no longer its imports.
        imports.dispose(resources)
        assertEquals(message, error("IdentityFile", identity))
    }

    @Test fun uriStaysRawForFacadeAndIsNeverConverted() {
        val text = "[AccessKey]\nSSH = ssh://user@host:22\n# keep\n"
        val next = ConfigDocument.parse(text)
        assertEquals("ssh", next.source()!!.second.substringBefore(':'))
        assertTrue(next.source()!!.second.substringBefore(':') in SshProtocolForm.sourceSchemes)
        assertNull(ProtocolForms.descriptor(next))
        assertFalse(ProtocolForms.supported(next))
        assertTrue(ProtocolForms.rawOnly(next))
        assertFalse(ProtocolForms.invalid(next))
        assertTrue(ProtocolForms.protected(next))
        assertEquals(text, next.render())
    }
}
