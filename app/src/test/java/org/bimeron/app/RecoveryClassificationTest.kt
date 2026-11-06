package org.bimeron.app

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryClassificationTest {
    private fun input(text: String) = input(text.toByteArray(Charsets.UTF_8))
    private fun input(bytes: ByteArray) =
        SessionInput(UUID.randomUUID(), bytes, ProfileRevision(1, 1), Sha256.of(bytes), null, null, null)

    private fun keyProfile(key: String) = Conf.fromKey("Home server", key)

    private fun accessProfile(vararg access: String) =
        "[Profile]\nVersion = 1\nName = Home server\n\n[AccessKey]\n" + access.joinToString("\n") + "\n"

    private val allowlistedKeys = mapOf(
        "SS" to "ss://YWVzLTEyOC1nY206c2VjcmV0@example.com:8388",
        "SSConf" to "ssconf://example.com/config",
        "SOCKS5" to "socks5://example.com:1080",
        "HTTP" to "https://user:secret@example.com:443",
        "MASQUE" to "masque://example.com:443",
        "VLESS" to "vless://00000000-0000-0000-0000-000000000000@example.com:443",
        "VMess" to "vmess://eyJhZGQiOiJleGFtcGxlLmNvbSJ9",
        "Trojan" to "trojan://secret@example.com:443",
        "Naive" to "naive+https://user:secret@example.com:443",
        "Hysteria2" to "hysteria2://secret@example.com:443",
        "AnyTLS" to "anytls://secret@example.com:443",
        "TUIC" to "tuic://00000000-0000-0000-0000-000000000000:secret@example.com:443",
    )

    @Test fun everyAllowlistedSourceKeyIsNonInteractive() {
        assertEquals(NON_INTERACTIVE_PROTOCOLS, allowlistedKeys.keys)
        allowlistedKeys.forEach { (field, key) ->
            val text = keyProfile(key)
            assertTrue(text, text.contains("\n$field = "))
            assertFalse(field, mayNeedInteractiveAuth(input(text)))
        }
    }

    @Test fun everyAllowlistedGenericKeyIsNonInteractive() {
        allowlistedKeys.forEach { (field, key) ->
            assertFalse(field, mayNeedInteractiveAuth(input(accessProfile("Key = $key"))))
        }
    }

    @Test fun structuredNonSshTypesAreNonInteractive() {
        listOf(
            "Type = ss\nServer = example.com\nPort = 8388\nMethod = aes-128-gcm\nPassword = secret",
            "Type = shadowsocks\nServer = example.com\nPort = 8388\nMethod = aes-128-gcm\nPassword = secret",
            "Type = socks5\nServer = example.com\nPort = 1080",
            "Type = vless\nServer = example.com\nPort = 443\nUUID = 00000000-0000-0000-0000-000000000000",
            "Type = trojan\nServer = example.com\nPort = 443\nPassword = secret",
            "Type = http\nServer = example.com\nPort = 8080",
            "Type = https\nServer = example.com\nPort = 443",
            "Type = naive\nServer = example.com\nPort = 443",
            "Type = masque\nServer = example.com\nPort = 443",
        ).forEach { assertFalse(it, mayNeedInteractiveAuth(input(accessProfile(it)))) }
    }

    @Test fun sshSourceKeyIsInteractive() {
        assertTrue(mayNeedInteractiveAuth(input(keyProfile("ssh://user@example.com:22"))))
        assertTrue(mayNeedInteractiveAuth(input(accessProfile("SSH = user@example.com"))))
        assertTrue(mayNeedInteractiveAuth(input(accessProfile("Type = ss", "ssh = user@example.com"))))
    }

    @Test fun sshTypeIsInteractiveInAnyCase() {
        listOf("ssh", "SSH", "Ssh", "sSh").forEach { type ->
            assertTrue(type, mayNeedInteractiveAuth(input(accessProfile("Type = $type", "Server = example.com", "Port = 22", "User = root"))))
        }
        assertTrue(mayNeedInteractiveAuth(input(accessProfile("Type = \" ssh \"", "Server = example.com"))))
    }

    @Test fun sshSchemeInAnyAccessValueIsInteractive() {
        listOf("ssh://user@example.com", "SSH://user@example.com", "Ssh://user@example.com").forEach { key ->
            assertTrue(key, mayNeedInteractiveAuth(input(accessProfile("Key = $key"))))
            assertTrue(key, mayNeedInteractiveAuth(input(accessProfile("SS = $key"))))
            assertTrue(key, mayNeedInteractiveAuth(input(accessProfile("Type = ss", "Server = example.com", "Port = 8388", "Note = $key"))))
        }
    }

    @Test fun unknownOrUnresolvedProtocolIsInteractive() {
        listOf(
            accessProfile("Type = wireguard", "Server = example.com", "Port = 51820"),
            accessProfile("Type = vmess", "Server = example.com", "Port = 443"),
            accessProfile("Type = SS", "Server = example.com", "Port = 8388"),
            accessProfile("Key = future://example.com"),
            accessProfile("File = server.key"),
        ).forEach { assertTrue(it, mayNeedInteractiveAuth(input(it))) }
    }

    @Test fun malformedOrAbsentInputIsInteractive() {
        assertTrue(mayNeedInteractiveAuth(null))
        assertTrue(mayNeedInteractiveAuth(input(ByteArray(0))))
        assertTrue(mayNeedInteractiveAuth(input(byteArrayOf(0xC3.toByte(), 0x28))))
        assertTrue(mayNeedInteractiveAuth(input("not a profile")))
        // No Version, two sources, no source, and an unknown section all fail Conf.parse.
        assertTrue(mayNeedInteractiveAuth(input("[AccessKey]\nSS = ss://example.com\n")))
        assertTrue(mayNeedInteractiveAuth(input(accessProfile("SS = ss://example.com", "VLESS = vless://example.com"))))
        assertTrue(mayNeedInteractiveAuth(input(accessProfile())))
        assertTrue(mayNeedInteractiveAuth(input(keyProfile("ss://example.com") + "[Future]\nA = b\n")))
        assertTrue(mayNeedInteractiveAuth(input(ByteArray(70000) { 'a'.code.toByte() })))
    }

    @Test fun leadingBomOrNextLineBeforeSshSchemeIsInteractive() {
        // Core trims Go white space (including U+0085) and one leading BOM before it dispatches a source value.
        listOf("\uFEFFssh://u@h", "\u0085ssh://u@h", "\uFEFF\u0085ssh://u@h", "\u0085\uFEFF\u0085ssh://u@h", "\uFEFF\uFEFFssh://u@h").forEach { value ->
            assertTrue(value, mayNeedInteractiveAuth(input(accessProfile("SS = $value"))))
            assertTrue(value, mayNeedInteractiveAuth(input(accessProfile("Key = $value"))))
            assertTrue(value, mayNeedInteractiveAuth(input(accessProfile("Type = ss", "Server = example.com", "Port = 8388", "Note = $value"))))
        }
        listOf("\uFEFFssh", "\u0085ssh", "\uFEFF ssh\u0085").forEach { type ->
            assertTrue(type, mayNeedInteractiveAuth(input(accessProfile("Type = $type", "Server = example.com", "Port = 22"))))
        }
    }

    @Test fun pinnedBomSourceValueIsInteractive() {
        // The exact review case: an SS field whose value Core reads as ssh://u@h.
        val text = "[Profile]\nVersion = 1\nName = Home server\n\n[AccessKey]\nSS = \uFEFFssh://u@h\n"
        // The app parser accepts it as an SS profile, so only the value check can catch it.
        assertEquals("SS", Conf.parse(text).protocol)
        assertTrue(mayNeedInteractiveAuth(input(text)))
    }

    @Test fun skippedRecoveryStateCarriesNoProfileData() {
        val state = recoverySkippedState { if (it == R.string.vpn_error_recovery_needs_user) "fixed" else "other" }
        assertEquals("failed", state.state)
        assertEquals(VpnErrorCode.RECOVERY_NEEDS_USER, state.code)
        assertEquals("fixed", state.message)
        assertNull(state.activeProfileId)
        assertNull(state.attemptProfileId)
        assertEquals(0L, state.received)
        assertEquals(0L, state.sent)
        assertEquals(0L, state.connectedAtElapsed)
    }
}
