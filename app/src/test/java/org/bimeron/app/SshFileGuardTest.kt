package org.bimeron.app

import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Decision 8, enforced by the service before any native session: SSH files must be this profile's imports. */
class SshFileGuardTest {
    private val root = File("/data/data/org.bimeron.app/files")
    private val id = UUID.fromString(AuthTestSupport.PROFILE)
    private val generation = "0f8e7d6c-5b4a-4392-8170-6f5e4d3c2b1a"
    private val identity = "$root/ssh-resources/profiles/$id/$generation/identity"
    private val knownHosts = "$root/ssh-resources/profiles/$id/$generation/known_hosts"

    private fun input(text: String) = text.toByteArray().let { SessionInput(id, it, ProfileRevision(1, 1), Sha256.of(it), null, null, null) }
    private fun typed(identityFile: String = "", knownHostsFile: String = "") = input(
        "[Profile]\nVersion = 1\nName = S\n\n[AccessKey]\nType = SSH\nServer = ssh.example.com\nUsername = u\n" +
            (if (identityFile.isNotEmpty()) "IdentityFile = $identityFile\n" else "") +
            (if (knownHostsFile.isNotEmpty()) "KnownHostsFile = $knownHostsFile\n" else ""))
    private fun key(uri: String) = input(Conf.fromKey("S", uri))
    private fun refused(input: SessionInput, label: String) {
        try { requireSshFilesInsideApp(input, root); fail("accepted: $label") }
        catch (e: BimeronVpnService.SessionFailure) { assertEquals(label, VpnErrorCode.SSH_FILE_OUTSIDE_APP, e.code) }
    }

    @Test fun importedFilesAndNoFilesPass() {
        requireSshFilesInsideApp(typed(identity, knownHosts), root)
        requireSshFilesInsideApp(typed(), root)
        requireSshFilesInsideApp(key("ssh://u@ssh.example.com:22"), root)
        requireSshFilesInsideApp(key("ssh://u@ssh.example.com:22?identity=${identity.replace("/", "%2F")}&known_hosts=$knownHosts"), root)
        requireSshFilesInsideApp(key("ss://YWVzLTEyOC1nY206c2VjcmV0@example.com:8388"), root)
    }

    @Test fun typedOutsidePathsAreRefused() {
        val outside = listOf("/sdcard/.ssh/id_ed25519", "/storage/emulated/0/Download/id_rsa", "/mnt/sdcard/known_hosts",
            "~/.ssh/id_rsa", "~", ".ssh/id_rsa", "id_rsa", "/data/local/tmp/identity",
            "$root/ssh-resources/profiles/${UUID.randomUUID()}/$generation/identity",
            "$root/ssh-resources/profiles/$id/$generation/../$generation/identity",
            "$root/ssh-resources/profiles/$id/not-a-uuid/identity",
            "$root/ssh-resources/staging/$generation/identity",
            "$root/ssh-resources/profiles/$id/$generation/identity/",
            "/other/files/ssh-resources/profiles/$id/$generation/identity")
        for (path in outside) {
            refused(typed(identityFile = path), "IdentityFile $path")
            refused(typed(knownHostsFile = path.replace("identity", "known_hosts")), "KnownHostsFile $path")
        }
        // The right directory but the wrong kind of file.
        refused(typed(identityFile = knownHosts), "known_hosts as identity")
        refused(typed(knownHostsFile = identity), "identity as known_hosts")
    }

    @Test fun sshUriFileParametersAreChecked() {
        for (query in listOf("identity=/sdcard/id_rsa", "known_hosts=/sdcard/known_hosts", "identity=%2Fsdcard%2Fid_rsa",
            "x=1&known_hosts=~/.ssh/known_hosts", "Identity=/sdcard/id", "identity=$identity&identity=/sdcard/id",
            "identity=$knownHosts", "known_hosts=%zz")) {
            refused(key("ssh://u@ssh.example.com:22?$query"), query)
        }
        // A file parameter in any field that Core reads as ssh:// counts.
        refused(input("[Profile]\nVersion = 1\nName = S\n\n[AccessKey]\nSSH = ssh://u@h:22?identity=/sdcard/id\n"), "SSH field")
    }

    /** The text overload (used at save time) and the service's SessionInput check give the same answer for every input. */
    @Test fun textOverloadMatchesTheSessionInputCheck() {
        val texts = listOf(typed(identity, knownHosts), typed(), typed(identityFile = "/sdcard/id"), typed(identityFile = knownHosts),
            typed(knownHostsFile = "$root/ssh-resources/profiles/${UUID.randomUUID()}/$generation/known_hosts"),
            typed(identityFile = "$root/ssh-resources/profiles/$id/${generation.uppercase()}/identity"),
            key("ssh://u@h:22?identity=${identity.replace("/", "%2F")}"), key("ssh://u@h:22?identity=/sdcard/id"),
            key("ssh://u@h:22?known_hosts=%zz"), input("[Broken"), input("\u0000 not a profile ["))
        for (input in texts) for (filesRoot in listOf(root, null, File("/other/files"))) {
            val text = Conf.read(input.bytes)
            assertEquals("$filesRoot: $text", SshFileGuard.insideApp(input, filesRoot), SshFileGuard.insideApp(text, id, filesRoot))
        }
        assertTrue(SshFileGuard.insideApp(Conf.read(typed(identity, knownHosts).bytes), id, root))
        assertFalse("another profile id", SshFileGuard.insideApp(Conf.read(typed(identity).bytes), UUID.randomUUID(), root))
        assertFalse("no root", SshFileGuard.insideApp(Conf.read(typed(identity).bytes), id, null))
        assertTrue("no references, no root", SshFileGuard.insideApp(Conf.read(typed().bytes), id, null))
        assertFalse("malformed", SshFileGuard.insideApp("[Broken", id, root))
    }

    @Test fun noRootOrUnreadableInputFailsClosed() {
        try { requireSshFilesInsideApp(typed(identity), null); fail() } catch (_: BimeronVpnService.SessionFailure) {}
        requireSshFilesInsideApp(typed(), null)
        assertFalse(SshFileGuard.insideApp(input("\u0000 not a profile ["), root))
        assertNull(SshFileGuard.references("[Broken"))
        assertTrue(SshFileGuard.references(typed().bytes.decodeToString())!!.isEmpty())
    }
}
