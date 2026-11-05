package org.bimeron.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class LogRedactorTest {
    private fun redacted(text: String) = LogRedactor.redact(text)
    private fun assertHidden(text: String, vararg secrets: String) {
        val result = redacted(text)
        secrets.forEach { assertFalse("secret leaked: $result", it in result) }
    }

    @Test fun ordinaryMessagesAreUnchanged() {
        listOf("VPN connected", "VPN error", "Profile deleted", "VPN connection started",
            "error: absolute_config_path_required", "Default network: wifi, internet access validated",
            "Core 0.2.0-beta.1 started at 12:30:45", "Default network lost").forEach {
            assertEquals(it, redacted(it))
        }
    }

    @Test fun accessKeyUrisAreMasked() {
        assertEquals("Key = ***", redacted("Key = ss://YWVzLTI1Ni1nY206VEVTVA@203.0.113.7:8388#name"))
        assertHidden("bad link vless://8a3c1f2e-1111-2222-3333-444455556666@vpn.example.com:443?pbk=AbCdEf123&sid=9f",
            "8a3c1f2e", "vpn.example.com", "AbCdEf123")
        assertEquals("open https://*** failed", redacted("open https://user:pw@api.example.com/x?token=1 failed"))
    }

    @Test fun keyValueSecretsAreMasked() {
        assertEquals("[AccessKey] Type = ss Password = ***", redacted("[AccessKey] Type = ss Password = fixture-secret"))
        assertEquals("token=*** uuid: ***", redacted("token=abc uuid: 0f0f"))
        assertEquals("PrivateKey = ***", redacted("PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="))
        assertEquals("Server=*** Port=443", redacted("Server=vpn.example.com Port=443"))
        assertEquals("Authorization: Bearer ***", redacted("Authorization: Bearer eyJhbGciOi.payload.sig"))
        assertEquals("password=***", redacted("password=\"two words\" next"))
        assertHidden("password=\"two words\"", "two", "words")
    }

    @Test fun quotedKeysAndMultiWordSecretsAreMasked() {
        assertEquals("{\"password\": \"***\", \"server\": \"***\"}",
            redacted("{\"password\": \"hunter2\", \"server\": \"vpn.example.com\"}"))
        assertEquals("password: ***", redacted("password: my yaml secret"))
        assertEquals("Password = ***", redacted("Password = my secret pass"))
        assertEquals("Password = ***, next", redacted("Password = my secret pass, next"))
        assertEquals("auth_str=***", redacted("auth_str=a1 pwd=b2 obfs-password: c3"))
        assertHidden("auth_str=alpha", "alpha")
        assertHidden("pwd=bravo", "bravo")
        assertHidden("obfs-password: charlie", "charlie")
        assertEquals("'token': '***'", redacted("'token': 'xyz'"))
    }

    @Test fun bareServerNamesAndUserHostAreMasked() {
        assertEquals("x509: certificate is valid for <host>, not <host>",
            redacted("x509: certificate is valid for a.example.org, not vpn.example.com"))
        assertEquals("candidate authority certificate \"<host>\"",
            redacted("candidate authority certificate \"vpn.example.com\""))
        assertEquals("ssh: handshake failed: user ***@<host>", redacted("ssh: handshake failed: user root@vpn.example.com"))
        assertEquals("<host> down.", redacted("VPN.Example.COM down."))
        listOf("org.bimeron.app.MainActivity crashed", "Session.Status returned", "Build.VERSION.SDK_INT",
            "saved profile.conf", "kotlinx.coroutines.JobCancellationException").forEach { assertEquals(it, redacted(it)) }
    }

    @Test fun internationalizedServerNamesAreMasked() {
        assertEquals("connect <host>: refused", redacted("connect vpn.m\u00fcnchen.de:443: refused"))
        assertEquals("<host> unreachable", redacted("b\u00fccher.example unreachable"))
        assertEquals("dial <host> failed", redacted("dial vpn.xn--p1ai failed"))
        assertEquals("user ***@<host>", redacted("user root@b\u00fccher.example"))
        // Prose with sentence dots is not a host name.
        assertEquals("VPN connected. Profile saved.", redacted("VPN connected. Profile saved."))
    }

    @Test fun reverseDomainPrefixesAreHostsUnlessTheyLookLikeCode() {
        assertEquals("resolve <host>", redacted("resolve android.clients.google.com"))
        assertEquals("peer <host>", redacted("peer io.example.net"))
        assertEquals("ping <host>", redacted("ping net.example.org:443"))
        listOf("org.bimeron.app.MainActivity crashed", "kotlinx.coroutines.X", "package org.bimeron.app",
            "java.net.SocketTimeoutException", "com.google.android.gms missing", "android.net.vpn")
            .forEach { assertEquals(it, redacted(it)) }
    }

    @Test fun secretValuesWithBracketsOrQuotesKeepNothingAfterThem() {
        assertEquals("Password = ***", redacted("Password = [abc]def123"))
        assertEquals("password=***", redacted("password=[x] rest of secret"))
        assertEquals("secret=***", redacted("secret=[s3cr3t]tail"))
        assertEquals("password=***", redacted("password=\"abc\"def123"))
        assertEquals("{\"password\": \"***\"}", redacted("{\"password\": \"x y\"}"))
        listOf("Password = [abc]def123", "password=[x] rest of secret", "secret=[s3cr3t]tail",
            "password=\"abc\"def123", "Endpoint = [2001:db8::1]:51820 x", "Endpoint = [abc]tail")
            .forEach { val once = redacted(it); assertEquals(once, redacted(once)) }
        listOf("def123", "rest of secret", "tail", "s3cr3t").forEach { leak ->
            listOf("Password = [abc]def123", "password=[x] rest of secret", "secret=[s3cr3t]tail")
                .forEach { assertFalse("$leak leaked from $it", leak in redacted(it)) }
        }
    }

    @Test fun bracketedEndpointValueIsFullyHidden() {
        assertEquals("Endpoint = ***", redacted("Endpoint = [2001:db8::1]:51820"))
        assertEquals("Endpoint = ***, next", redacted("Endpoint = [2001:db8::1]:51820, next"))
        assertEquals("Endpoint = ***", redacted(redacted("Endpoint = [2001:db8::1]:51820")))
        assertEquals("Endpoint = ***", redacted("Endpoint = [abc]tail"))
    }

    @Test fun ipv6PortsAndPaddedTokens() {
        assertEquals("<ip>: reset", redacted("[2001:db8::1]:443: reset"))
        assertEquals("route <ip>: down", redacted("route fe80::1: down"))
        // A time after a word is not an IPv6 address.
        assertEquals("at 12:30:45: error", redacted("at 12:30:45: error"))
        assertEquals("psk ***", redacted("psk c2VjcmV0c2VjcmV0MQ=="))
    }

    @Test fun pathologicalInputIsBoundedAndNeverEchoed() {
        val chain = "ab.".repeat(20_000) + "com"
        val result = redacted(chain)
        assertTrue(result.length <= LogRedactor.MAX_INPUT)
        val long = "x".repeat(LogRedactor.MAX_INPUT) + " Password = tail-secret"
        assertFalse("tail-secret" in redacted(long))
        val quotes = "password=\"" + "a".repeat(50_000)
        assertFalse("aaaa" in redacted(quotes))
    }

    @Test fun addressesAreMasked() {
        assertEquals("dial tcp <ip>: i/o timeout", redacted("dial tcp 203.0.113.7:443: i/o timeout"))
        assertEquals("route <ip> via <ip>", redacted("route 10.0.0.0/8 via 192.168.1.1"))
        assertEquals("peer <ip> closed", redacted("peer [2001:db8::1]:443 closed"))
        assertEquals("dns <ip>", redacted("dns fe80::1%wlan0"))
        assertEquals("connect <host>: refused", redacted("connect vpn.example.com:443: refused"))
        assertEquals("lookup <host> on <ip>: no such host", redacted("lookup vpn.example.com on 8.8.8.8:53: no such host"))
        assertEquals("server <ip>.", redacted("server 198.51.100.4."))
    }

    @Test fun identifiersTokensAndPathsAreMasked() {
        assertEquals("session *** stopped", redacted("session 123e4567-e89b-12d3-a456-426614174000 stopped"))
        assertEquals("pbk ***", redacted("pbk Zx9vQm2Lr7Tt0Ww3Yy5Uu8Ii1Oo4Pp6Aa"))
        assertEquals("open <path>", redacted("open /data/user/0/org.bimeron.app/files/profiles/abc.conf"))
        assertEquals("not_a_secret_but_long_identifier", redacted("not_a_secret_but_long_identifier"))
    }

    @Test fun redactionIsIdempotent() {
        val samples = listOf("Key = ss://abc@1.2.3.4:1", "dial 2001:db8::5 and host.example.org:80",
            "Password = x token=y", "lookup a.example.com", "/storage/emulated/0/x")
        samples.forEach { val once = redacted(it); assertEquals(once, redacted(once)) }
    }
}

class JournalLogTest {
    private var saved = LogSettings()

    private var savedResources: android.content.res.Resources? = null

    @Before fun setUp() {
        saved = AppEvents.settings.value; savedResources = AppEvents.resources
        AppEvents.resources = null; AppEvents.apply(LogSettings()); AppEvents.clear()
    }
    @After fun tearDown() { AppEvents.apply(saved); AppEvents.resources = savedResources; AppEvents.clear() }

    @Test fun storesRedactedBoundedNewestFirst() {
        AppEvents.add("first")
        AppEvents.add("Password = hunter2secret")
        AppEvents.add("x".repeat(AppEvents.MAX_MESSAGE + 50))
        val entries = AppEvents.entries.value
        assertEquals(3, entries.size)
        assertEquals(AppEvents.MAX_MESSAGE, entries[0].message.length)
        assertEquals("Password = ***", entries[1].message)
        assertEquals("first", entries[2].message)
        assertTrue(entries.all { it.epochMillis > 0 })
    }

    @Test fun levelFiltersRecording() {
        AppEvents.apply(LogSettings(level = LogLevel.WARNING))
        AppEvents.add("VPN connected")
        AppEvents.add("network", LogLevel.DEBUG)
        AppEvents.add("VPN error", LogLevel.ERROR)
        assertEquals(listOf("VPN error"), AppEvents.entries.value.map { it.message })
        AppEvents.apply(LogSettings(level = LogLevel.DEBUG))
        AppEvents.add("network", LogLevel.DEBUG)
        assertEquals(LogLevel.DEBUG, AppEvents.entries.value.first().level)
    }

    @Test fun limitTrimsOldestAndApplyTrimsExisting() {
        repeat(600) { AppEvents.add("entry $it") }
        assertEquals(600, AppEvents.entries.value.size)
        AppEvents.apply(LogSettings(maxEntries = 500))
        assertEquals(500, AppEvents.entries.value.size)
        assertEquals("entry 599", AppEvents.entries.value.first().message)
        AppEvents.add("newest")
        assertEquals(500, AppEvents.entries.value.size)
        assertEquals("entry 101", AppEvents.entries.value.last().message)
    }

    @Test fun keepPreviousControlsClearOnConnection() {
        AppEvents.add("old")
        AppEvents.connectionStarting()
        assertEquals(1, AppEvents.entries.value.size)
        AppEvents.apply(LogSettings(keepPrevious = false))
        AppEvents.connectionStarting()
        assertTrue(AppEvents.entries.value.isEmpty())
    }

    @Test fun connectingAfterDisconnectClearsOnlyWhenConfigured() {
        // Plain JVM tests have no AppEvents.resources, so resource-backed entries are recorded as "#<id>".
        val connectingEntry = "#${R.string.journal_vpn_connecting}"
        val state = VpnLive.state.value
        try {
            AppEvents.apply(LogSettings(keepPrevious = false))
            AppEvents.add("old")
            VpnLive.publish(VpnState("connecting"), VpnLive.gate.next())
            assertEquals(listOf(connectingEntry), AppEvents.entries.value.map { it.message })
            // Switching profiles while connected starts a new attempt token and clears again.
            val session = VpnLive.gate.next()
            VpnLive.publish(VpnState("connected", activeProfileId = "a"), session)
            AppEvents.add("during session")
            VpnLive.publish(VpnState("connecting", attemptProfileId = "b"), VpnLive.gate.next())
            assertEquals(listOf(connectingEntry), AppEvents.entries.value.map { it.message })
            // A core-reported connecting within the same attempt keeps the entries.
            val attempt = VpnLive.gate.next()
            VpnLive.publish(VpnState("connecting"), attempt)
            VpnLive.publish(VpnState("connected"), attempt)
            AppEvents.add("kept")
            VpnLive.publish(VpnState("connecting"), attempt)
            assertTrue("kept" in AppEvents.entries.value.map { it.message })
            // Apply while still connecting: cleared once, and the new attempt is recorded.
            VpnLive.publish(VpnState("connecting", attemptProfileId = "c"), VpnLive.gate.next())
            AppEvents.add("stale")
            VpnLive.publish(VpnState("connecting", attemptProfileId = "c"), VpnLive.gate.next())
            assertEquals(listOf(connectingEntry), AppEvents.entries.value.map { it.message })
        } finally { VpnLive.state.value = state }
    }

    @Test fun settingsSanitizeUnknownValues() {
        assertEquals(LogSettings.DEFAULT_LIMIT, LogSettings.sanitizedLimit(7))
        assertEquals(1000, LogSettings.sanitizedLimit(1000))
        assertEquals(LogLevel.INFO, LogSettings.sanitizedLevel(LogLevel.parse("bogus")))
        assertEquals(LogLevel.INFO, LogSettings.sanitizedLevel(LogLevel.ERROR))
        assertEquals(LogLevel.DEBUG, LogSettings.sanitizedLevel(LogLevel.parse("DEBUG")))
    }
}

class DiagnosticReportTest {
    private val zone = ZoneId.of("UTC")
    private val generated = ZonedDateTime.of(2026, 11, 5, 12, 0, 0, 0, zone)
    private val base = generated.toInstant().toEpochMilli()
    private val sections = listOf(
        DiagnosticSection("Application", listOf("Version" to "0.1.0 (1)", "Build" to "debug")),
        DiagnosticSection("Network", listOf("Default network" to "wifi\nvpn")),
    )

    @Test fun formatsHeaderSectionsAndChronologicalEntries() {
        val entries = listOf(
            LogEntry("05.11 12:00:02", "VPN connected", LogLevel.INFO, base + 2000),
            LogEntry("05.11 12:00:01", "Error: Password = leaked", LogLevel.ERROR, base + 1000),
        )
        val text = DiagnosticReport.format(sections, entries, LogSettings(), generated)
        assertTrue(text.startsWith("Bimeron Android diagnostics\nGenerated: 2026-11-05T12:00:00Z\n"))
        assertTrue("[Application]\nVersion: 0.1.0 (1)\nBuild: debug\n" in text)
        assertTrue("Default network: wifi vpn\n" in text)
        assertTrue("[Journal]\nLevel: info\nLimit: 5000\nKeep previous entries: yes\nEntries: 2\n" in text)
        val first = text.indexOf("2026-11-05 12:00:01 ERROR   Error: Password = ***")
        val second = text.indexOf("2026-11-05 12:00:02 INFO    VPN connected")
        assertTrue(text, first in 0 until second)
        assertFalse("leaked" in text)
    }

    @Test fun emptyJournalAndLegacyEntriesFormat() {
        assertTrue("Entries: 0\n(empty)\n" in DiagnosticReport.format(emptyList(), emptyList(), LogSettings(), generated))
        val legacy = DiagnosticReport.entryLine(LogEntry("01.01 00:00:00", "line\nbreak 10.0.0.1"), zone)
        assertEquals("01.01 00:00:00 INFO    line break <ip>", legacy)
    }

    @Test fun truncatesOldestEntriesToByteLimit() {
        val entries = (0 until 200).map { LogEntry("", "message $it ${"é".repeat(40)}", LogLevel.INFO, base + it) }.reversed()
        val text = DiagnosticReport.format(sections, entries, LogSettings(), generated, maxBytes = 4096)
        assertTrue(text.toByteArray().size <= 4096)
        assertTrue("message 199 " in text)
        assertFalse("message 0 " in text)
        assertTrue(Regex("Entries: 200 \\(\\d+ oldest omitted to fit 4 KiB\\)").containsMatchIn(text))
    }

    @Test fun fileNameIsTimestamped() {
        assertEquals("bimeron-diagnostics-20261105-120000.txt", DiagnosticReport.fileName(generated))
    }
}
