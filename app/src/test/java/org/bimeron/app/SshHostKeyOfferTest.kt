package org.bimeron.app

import kotlinx.coroutines.runBlocking
import org.bimeron.app.HostKeyFixtures.REQUEST_A
import org.bimeron.app.HostKeyFixtures.REQUEST_B
import org.bimeron.app.HostKeyFixtures.SESSION
import org.bimeron.app.HostKeyFixtures.base64
import org.bimeron.app.HostKeyFixtures.blob
import org.bimeron.app.HostKeyFixtures.fields
import org.bimeron.app.HostKeyFixtures.fingerprint
import org.bimeron.app.HostKeyFixtures.json
import org.bimeron.app.HostKeyFixtures.offer
import org.bimeron.app.HostKeyFixtures.wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Strict parsing of `host_key` pendingAuth offers (core ADR 0037). */
class SshHostKeyOfferTest {
    private fun parse(wire: String) = SshAuthenticationController.parse(wire)
    private fun rejected(wire: String, label: String = wire) {
        try { parse(wire); fail("accepted: $label") } catch (_: Exception) {}
    }
    private fun mutated(label: String, change: LinkedHashMap<String, Any?>.() -> Unit) =
        rejected(json(fields(offer()).apply(change)), label)
    private fun withLine(line: String) = json(fields(offer()).apply { this["knownHostsLine"] = line })
    private val good = offer()
    private val goodFields = good.knownHostsLine.split(' ')

    @Test fun acceptsCoreShapedOffersForEveryNormalization() {
        for (host in listOf("ssh.example.com:2222", "ssh.example.com:22", "192.0.2.10:22", "[2001:db8::1]:2222",
            "[2001:db8::1]:22", "[fe80::1%wlan0]:22", "host_1-a.example:65535")) {
            val offer = offer(host)
            val request = parse(wire(offer))!!
            assertEquals(host, "host_key", request.kind)
            assertEquals(SESSION, request.sessionId); assertEquals(REQUEST_A, request.requestId)
            assertTrue(request.prompts.isEmpty())
            assertEquals(offer, request.hostKey)
        }
        assertEquals("ssh.example.com", offer("ssh.example.com:22").knownHostsLine.substringBefore(' '))
        assertEquals("[ssh.example.com]:2222", offer("ssh.example.com:2222").knownHostsLine.substringBefore(' '))
        assertEquals("2001:db8::1", offer("[2001:db8::1]:22").knownHostsLine.substringBefore(' '))
        for (type in listOf("ssh-rsa", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521",
            "sk-ssh-ed25519@openssh.com", "sk-ecdsa-sha2-nistp256@openssh.com")) {
            assertEquals(type, parse(wire(offer(type = type)))!!.hostKey!!.keyType)
        }
        assertNull(parse("null"))
    }

    @Test fun lineHostIsTheLowercasedDialedHost() {
        val mixed = offer("SSH.Example.COM:2222")
        assertEquals("[ssh.example.com]:2222 ", mixed.knownHostsLine.substring(0, 23))
        assertEquals(mixed, parse(wire(mixed))!!.hostKey)
        assertEquals("ssh.example.com", offer("SSH.Example.COM:22").knownHostsLine.substringBefore(' '))
        // The dialed case preserved, or any other host, is not what Core stores and is rejected.
        rejected(json(fields(mixed).apply {
            this["knownHostsLine"] = mixed.knownHostsLine.replaceFirst("[ssh.example.com]", "[SSH.Example.COM]")
        }), "mixed-case line host")
        assertFalse(SshAuthenticationController.validKnownHostsLine(mixed.knownHostsLine.replaceFirst("ssh", "SSH")))
    }

    @Test fun rejectsWrongShape() {
        mutated("missing line") { remove("knownHostsLine") }
        mutated("missing host") { remove("host") }
        mutated("prompts present") { this["prompts"] = listOf("x") }
        mutated("echo present") { this["echo"] = listOf(false) }
        mutated("extra field") { this["comment"] = "x" }
        mutated("null host") { this["host"] = null }
        mutated("boolean fingerprint") { this["fingerprint"] = true }
        mutated("list line") { this["knownHostsLine"] = listOf(good.knownHostsLine) }
        mutated("short session") { this["sessionId"] = "a".repeat(47) }
        mutated("non-hex request") { this["requestId"] = "g".repeat(48) }
        // A known kind never borrows host-key fields, and host_key never carries prompts.
        rejected(json(fields(good).apply { this["kind"] = "password"; this["prompts"] = listOf("P"); this["echo"] = listOf(false) }))
        rejected(json(linkedMapOf("sessionId" to SESSION, "requestId" to REQUEST_A, "kind" to "host_key", "prompts" to listOf("P"), "echo" to listOf(false))))
        rejected(wire(good).replace("\"host_key\"", "\"HOST_KEY\""))
    }

    @Test fun rejectsBadFingerprintsAndKeyTypes() {
        mutated("padded") { this["fingerprint"] = good.fingerprint + "=" }
        mutated("short") { this["fingerprint"] = good.fingerprint.dropLast(1) }
        mutated("lower prefix") { this["fingerprint"] = good.fingerprint.replace("SHA256:", "sha256:") }
        mutated("md5") { this["fingerprint"] = "MD5:" + "ab:".repeat(15) + "ab" }
        mutated("url-safe") { this["fingerprint"] = "SHA256:" + "-".repeat(43) }
        // Well formed but not the digest of the key that would be persisted.
        mutated("other key") { this["fingerprint"] = fingerprint(blob(seed = 2)) }
        for (type in listOf("ssh-dss", "ssh-ed25519-cert-v01@openssh.com", "ssh ed25519", "", "SSH-ED25519", "x-${"a".repeat(130)}")) {
            mutated("type $type") { this["keyType"] = type }
        }
        // Type consistent nowhere: offer says rsa, line and blob say ed25519.
        mutated("type vs line") { this["keyType"] = "ssh-rsa" }
        // Line and offer agree on the type but the blob declares another.
        val rsaBlob = blob("ssh-rsa")
        rejected(json(fields(good).apply {
            this["knownHostsLine"] = "${goodFields[0]} ssh-ed25519 ${base64(rsaBlob)}"; this["fingerprint"] = fingerprint(rsaBlob)
        }), "blob type")
    }

    @Test fun rejectsHostsCoreNeverDials() {
        for (host in listOf("ssh.example.com", "ssh.example.com:", ":22", "ssh.example.com:0", "ssh.example.com:65536",
            "ssh.example.com:022", "ssh.example.com:+22", "a b:22", "a,b:22", "*.example.com:22", "!h:22", "|1|abc:22",
            "h\u202e:22", "h\u0000:22", "h\t:22", "h\n:22", "xn--\u00e9:22", "::1:22", "[::1]", "[::1]22", "[h]]:22",
            "[[h]:22", "h]:22", "h" + "a".repeat(512) + ":22")) {
            mutated("host ${host.take(40)}") { this["host"] = host }
        }
    }

    @Test fun rejectsKnownHostsLineInjection() {
        val (host, type, key) = goodFields
        val bad = listOf(
            "$host $type $key\n@revoked * $type $key",
            "$host $type $key\nevil.example $type $key",
            "$host $type $key\r",
            "$host $type $key\u2028",
            "$host $type $key comment",
            "$host $type $key ",
            " $host $type $key",
            "@revoked $host $type $key",
            "@cert-authority $host $type $key",
            "$host  $type $key",
            "$host\t$type $key",
            "$host,evil.example $type $key",
            "*.example.com $type $key",
            "|1|c2FsdA==|aGFzaA== $type $key",
            "!$host $type $key",
            "[other.example]:2222 $type $key",
            "[ssh.example.com]:2223 $type $key",
            "ssh.example.com $type $key",
            "[ssh.example.com]:2222 ssh-rsa $key",
            "$host $type ${key}=",
            "$host $type ${key.dropLast(4)}",
            "$host $type ${key}AAAA",
            "$host $type",
            "$host $type ${"A".repeat(12288)}",
            "$host $type ${key.replace('A', '\u0410')}",
        )
        bad.forEachIndexed { index, line -> rejected(withLine(line), "line #$index") }
        // Port 22 must not be bracketed; any other port must be.
        rejected(json(fields(offer("ssh.example.com:22")).apply {
            this["knownHostsLine"] = (this["knownHostsLine"] as String).replaceFirst("ssh.example.com", "[ssh.example.com]:22")
        }), "bracketed port 22")
        // A line host longer than Core's 300-character known_hosts limit would poison the whole store.
        rejected(wire(offer("h" + "a".repeat(300) + ":2222")), "long line host")
    }

    @Test fun controllerSendsOnlyTheFixedAcceptance() = runBlocking {
        val controller = SshAuthenticationController()
        controller.feed(wire())
        val request = controller.request!!
        assertNotNull(request.hostKey)
        assertTrue(controller.answers.isEmpty())
        assertTrue(controller.canSubmit)
        controller.answer(0, "anything")
        assertTrue(controller.answers.isEmpty())
        val sent = mutableListOf<String>()
        controller.submit { _, _, answers -> sent += answers; true }
        assertEquals(listOf("[\"accept\"]"), sent)
        assertEquals(SshAuthenticationController.HOST_KEY_ACCEPT, sent.single())
        assertTrue(controller.busy)
    }

    @Test fun changedOfferUnderSameIdsIsDropped() {
        val controller = SshAuthenticationController()
        controller.feed(wire(offer(seed = 1)))
        controller.feed(wire(offer(seed = 1)))
        assertNotNull(controller.request)
        controller.feed(wire(offer(seed = 2)))
        assertNull(controller.request)
        // The spent ids stay protected; a fresh request id is shown.
        controller.feed(wire(offer(seed = 1)))
        assertNull(controller.request)
        controller.feed(wire(offer(seed = 2), REQUEST_B))
        assertEquals(REQUEST_B, controller.request?.requestId)
    }

    @Test fun knownHostsLineValidator() {
        assertTrue(SshAuthenticationController.validKnownHostsLine(good.knownHostsLine))
        assertTrue(SshAuthenticationController.validKnownHostsLine(offer("[2001:db8::1]:22").knownHostsLine))
        listOf("", "# comment", "${goodFields[0]} ${goodFields[1]}", "[h]:22 ${goodFields[1]} ${goodFields[2]}",
            "[h]:x ${goodFields[1]} ${goodFields[2]}", "h] ${goodFields[1]} ${goodFields[2]}",
            "h ssh-dss ${base64(blob("ssh-dss"))}").forEach {
            assertFalse(it, SshAuthenticationController.validKnownHostsLine(it))
        }
    }
}
