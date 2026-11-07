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

    @Test fun manifestDeclaresNoStoragePermission() {
        val manifest = File(module, "src/main/AndroidManifest.xml").readText()
        for (forbidden in listOf("EXTERNAL_STORAGE", "READ_MEDIA_", "MANAGE_DOCUMENTS", "requestLegacyExternalStorage", "preserveLegacyExternalStorage")) {
            assertFalse(forbidden, manifest.contains(forbidden))
        }
    }

    @Test fun appSourcesUseNoExternalStorageApiOrSshDefaults() {
        val sources = File(module, "src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.any { it.name == "SshKeyImport.kt" } && sources.any { it.name == "BimeronVpnService.kt" })
        val forbidden = listOf("getExternalStorage", "getExternalFilesDir", "getExternalCacheDir", "getExternalMediaDirs",
            "Environment.DIRECTORY", "EXTERNAL_STORAGE", "\"/sdcard", "\"/storage", ".ssh/", "/.ssh", "\"~", "user.home")
        for (file in sources) for (token in forbidden) assertFalse("${file.name}: $token", file.readText().contains(token))
        // Imports reach the app only through the Storage Access Framework picker.
        val import = sources.single { it.name == "SshKeyImport.kt" }.readText()
        assertTrue(import.contains("ActivityResultContracts.OpenDocument()") && import.contains("openInputStream(uri)"))
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
        // A profile without imports carries no path at all; TOFU supplies trust instead of a default file.
        val plain = store.save(Conf.fromKey("Plain", "ssh://user@ssh.example.com:22"))
        val plainBytes = String(store.readForSession(plain.id) { true }.bytes)
        assertTrue(SshDraftResources.paths(plainBytes).isEmpty())
        for (token in forbiddenInPayload) assertFalse(token, plainBytes.contains(token))
    }
}
