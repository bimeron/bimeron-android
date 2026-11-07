package org.bimeron.app

import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Decision 8: Android SSH sessions use only keys imported into app storage. No storage permission, no
 * external-storage API, and nothing passed to Core names /sdcard, /storage or a ~/.ssh default.
 * Core's own guard (default identities off, no ~/.ssh fallback) is tested in Go.
 */
class NoExternalStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val module = listOf(File("."), File("app")).first { File(it, "src/main/AndroidManifest.xml").isFile }

    private val forbiddenInPayload = listOf("/sdcard", "/storage/", "/mnt/", "~/", ".ssh/", "/.ssh", "id_rsa", "id_ed25519", "id_ecdsa")

    /** The processed merged manifest (libraries included) that AGP hands to Robolectric for this test variant. */
    private fun mergedManifest(): File {
        val config = java.util.Properties().apply {
            NoExternalStorageTest::class.java.classLoader!!.getResourceAsStream("com/android/tools/test_config.properties")!!.use(::load)
        }
        return File(module, config.getProperty("android_merged_manifest")).also { assertTrue(it.path, it.isFile) }
    }

    @Test fun mergedManifestRequestsNoStoragePermission() {
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(mergedManifest())
        val android = "http://schemas.android.com/apk/res/android"
        val requested = listOf("uses-permission", "uses-permission-sdk-23").flatMap { tag ->
            document.getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { (nodes.item(it) as org.w3c.dom.Element).getAttributeNS(android, "name") } }
        }
        assertTrue(requested.toString(), "android.permission.INTERNET" in requested)
        for (permission in requested) for (forbidden in listOf("EXTERNAL_STORAGE", "READ_MEDIA_", "MANAGE_MEDIA", "ACCESS_MEDIA_LOCATION", "MANAGE_DOCUMENTS")) {
            assertFalse(permission, permission.contains(forbidden))
        }
        val application = document.getElementsByTagName("application").item(0) as org.w3c.dom.Element
        for (attribute in listOf("requestLegacyExternalStorage", "preserveLegacyExternalStorage")) {
            assertFalse(attribute, application.getAttributeNS(android, attribute) == "true")
        }
    }

    @Test fun appSourcesUseNoExternalStorageApiOrSshDefaults() {
        val sources = File(module, "src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.any { it.name == "SshKeyImport.kt" } && sources.any { it.name == "BimeronVpnService.kt" })
        val forbidden = listOf("getExternalStorage", "getExternalFilesDir", "getExternalCacheDir", "getExternalMediaDirs",
            "getExternalFilesDirs", "getExternalCacheDirs", "getObbDir", "externalCacheDir", "externalCacheDirs", "externalMediaDirs",
            "obbDir", "ExternalStorage", "Environment.DIRECTORY", "EXTERNAL_STORAGE", "MediaStore", "\"/sdcard", "\"/storage",
            "\"/mnt", ".ssh/", "/.ssh", "\"~", "user.home", "SSH_AUTH_SOCK")
        for (file in sources) for (token in forbidden) assertFalse("${file.name}: $token", file.readText().contains(token))
        // Imports reach the app only through the Storage Access Framework picker.
        val import = sources.single { it.name == "SshKeyImport.kt" }.readText()
        assertTrue(import.contains("ActivityResultContracts.OpenDocument()") && import.contains("openInputStream(uri)"))
    }

    @Test fun serviceGuardRefusesTypedOutsidePaths() {
        val bytes = "[Profile]\nVersion = 1\nName = S\n\n[AccessKey]\nType = SSH\nServer = h\nUsername = u\nIdentityFile = /sdcard/.ssh/id_ed25519\n".toByteArray()
        val input = SessionInput(UUID.randomUUID(), bytes, ProfileRevision(1, 1), Sha256.of(bytes), null, null, null)
        try { requireSshFilesInsideApp(input, temporary.root.canonicalFile); org.junit.Assert.fail() }
        catch (e: BimeronVpnService.SessionFailure) { assertEquals(VpnErrorCode.SSH_FILE_OUTSIDE_APP, e.code) }
        // The service runs the guard before trusted keys and before any native session.
        val service = File(module, "src/main/java/org/bimeron/app/BimeronVpnService.kt").readText()
        val guard = service.indexOf("requireSshFilesInsideApp(sessionInput")
        assertTrue(guard > 0 && guard < service.indexOf("trustedHostKeysFor(sessionInput") && guard < service.indexOf("createCoreSession("))
    }

    @Test fun sessionBytesReferenceOnlyAppOwnedImports() = runBlocking {
        val base = temporary.root.canonicalFile
        val resources = SshProfileResources(File(base, "ssh-resources"))
        val store = ProfileStore(null, File(base, "profiles").apply { mkdirs() }, resources) {}
        val id = UUID.randomUUID()
        val draft = SshDraftResources(id)
        val identity = resources.stage(SshProfileResources.Kind.IDENTITY, "key".byteInputStream())
        val hosts = resources.stage(SshProfileResources.Kind.KNOWN_HOSTS, "hosts".byteInputStream())
        val text = ConfigDocument.parse(Conf.fromKey("SSH", "ssh://user@ssh.example.com:22"))
            .set("AccessKey", "IdentityFile", draft.accept(identity)!!)
            .set("AccessKey", "KnownHostsFile", draft.accept(hosts)!!).render()
        store.save(text, id.toString(), draft)
        val session = String(store.readForSession(id.toString()) { true }.bytes)
        val paths = SshDraftResources.paths(session)
        assertEquals(2, paths.size)
        val owned = File(base, "ssh-resources/profiles/$id").path + File.separator
        assertTrue(paths.toString(), paths.all { it.startsWith(owned) })
        for (token in forbiddenInPayload) assertFalse(token, session.contains(token))
        requireSshFilesInsideApp(store.readForSession(id.toString()) { true }, base)
        // A profile without imports carries no path at all; TOFU supplies trust instead of a default file.
        val plain = store.save(Conf.fromKey("Plain", "ssh://user@ssh.example.com:22"))
        val plainBytes = String(store.readForSession(plain.id) { true }.bytes)
        assertTrue(SshDraftResources.paths(plainBytes).isEmpty())
        for (token in forbiddenInPayload) assertFalse(token, plainBytes.contains(token))
    }
}
