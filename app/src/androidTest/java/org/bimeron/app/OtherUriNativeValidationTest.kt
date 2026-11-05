package org.bimeron.app

import android.util.Base64
import org.bimeron.core.androidcore.Androidcore
import org.bimeron.core.androidcore.SocketProtector
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Source-schema expectations against the packaged Core, without starting a session or network. */
@RunWith(Parameterized::class)
class OtherUriNativeValidationTest(
    private val caseId: String,
    private val source: String,
    private val value: String,
    private val expectedAcceptance: Boolean
) {
    @Test
    fun validatesSourceSchema() {
        val document = "[Profile]\nVersion = 1\nName = TEST\n[AccessKey]\n" +
            "$source = $value\n[Interface]\nMode = vpn\nKillSwitch = false\nDNS = 192.0.2.53\n"
        // Never attach native exceptions: their messages/causes may contain credentials.
        val session = try {
            Androidcore.newSession(document.toByteArray(Charsets.UTF_8), object : SocketProtector {
                override fun protect(fd: Long): Boolean =
                    throw AssertionError("$caseId attempted socket protection")
            })
        } catch (_: Exception) {
            null
        }
        if (session != null) {
            try {
                session.stop()
            } catch (_: Exception) {
                throw AssertionError("$caseId session cleanup failed")
            }
        }
        assertEquals("$caseId packaged Core differs from audited source schema", expectedAcceptance, session != null)
    }

    companion object {
        private const val ID = "123e4567-e89b-12d3-a456-426614174000"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = buildList {
            fun fixture(id: String, source: String, uri: String, accepted: Boolean = true) {
                add(arrayOf<Any>(id, source, uri, accepted))
            }
            val protocols = listOf(
                Triple("hy2", "Hysteria2", "hy2://TEST@example.invalid:443"),
                Triple("anytls", "AnyTLS", "anytls://TEST@example.invalid:443"),
                Triple("tuic", "TUIC", "tuic://$ID:TEST@example.invalid:443"),
                Triple("naive-quic", "Naive", "naive+quic://TEST:TEST@example.invalid:443")
            )
            for ((id, source, uri) in protocols) {
                fixture("$id-minimal", source, uri)
                fixture("$id-generic-key", "Key", uri)
                fixture("$id-ipv6", source, uri.replace("example.invalid", "[2001:db8::1]"))
                fixture("$id-unsupported-option", source, "$uri?unknown=1", false)
                fixture("$id-duplicate-option", source, "$uri?sni=example.invalid&sni=example.invalid", false)
                fixture("$id-empty-sni", source, "$uri?sni=", false)
                fixture("$id-port-range", source, uri.replace(":443", ":65536"), false)
                fixture("$id-credential-newline", source, uri.replace("TEST", "TEST%0A"), false)
                fixture("$id-insecure", source, "$uri?insecure=1", false)
            }
            fixture("hy2-long-scheme", "Hysteria2", "hysteria2://TEST@example.invalid:443")
            fixture("hy2-query-auth", "Hysteria2", "hy2://example.invalid:443?auth=TEST")
            fixture("hy2-empty-alpn", "Hysteria2", "hy2://TEST@example.invalid:443?alpn=")
            fixture("hy2-dual-auth", "Hysteria2", "hy2://TEST@example.invalid:443?auth=TEST", false)
            fixture("hy2-empty-fingerprint", "Hysteria2", "hy2://TEST@example.invalid:443?fp=", false)
            fixture("hy2-invalid-alpn", "Hysteria2", "hy2://TEST@example.invalid:443?alpn=h2", false)
            fixture("anytls-default-port-slash", "AnyTLS", "anytls://TEST@example.invalid/")
            fixture("anytls-userinfo-colon", "AnyTLS", "anytls://TEST:TEST@example.invalid:443", false)
            fixture("tuic-relay-quic", "TUIC", "tuic://$ID:TEST@example.invalid:443?udp_relay_mode=quic")
            fixture("tuic-custom-alpn", "TUIC", "tuic://$ID:TEST@example.invalid:443?alpn=custom")
            fixture("tuic-relay-native", "TUIC", "tuic://$ID:TEST@example.invalid:443?udp_relay_mode=native", false)
            fixture("tuic-invalid-id", "TUIC", "tuic://invalid:TEST@example.invalid:443", false)
            fixture("tuic-empty-alpn", "TUIC", "tuic://$ID:TEST@example.invalid:443?alpn=", false)
            fixture("naive-quic-default-port", "Naive", "naive+quic://TEST:TEST@example.invalid")
            fixture("naive-quic-encoded-credentials", "Naive", "naive+quic://T%40%C3%A9:T%3AST@example.invalid")
            fixture("naive-quic-user-colon", "Naive", "naive+quic://T%3AST:TEST@example.invalid", false)
            fixture("naive-quic-empty-password", "Naive", "naive+quic://TEST:@example.invalid", false)

            val base = """"v":"2","add":"example.invalid","port":"443","id":"$ID","tls":"tls""""
            fun vmess(id: String, fields: String = base, accepted: Boolean = true, suffix: String = "") {
                val uri = "vmess://" + Base64.encodeToString("{$fields}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                fixture("vmess-$id", "VMess", uri + suffix, accepted)
            }
            vmess("minimal")
            vmess("null-defaults", "$base,\"aid\":null,\"type\":null,\"scy\":null,\"net\":null,\"sni\":null")
            vmess("empty-defaults", "$base,\"aid\":\"\",\"type\":\"\",\"scy\":\"\",\"net\":\"\",\"sni\":\"\"")
            vmess("unsupported-field", "$base,\"unknown\":\"TEST\"", false)
            vmess("duplicate-field", "$base,\"tls\":\"tls\"", false)
            vmess("query", accepted = false, suffix = "?unknown=1")
            vmess("invalid-tls", base.replace("\"tls\":\"tls\"", "\"tls\":\"none\""), false)
            vmess("invalid-id", base.replace(ID, "invalid"), false)
            vmess("legacy-aid", "$base,\"aid\":1", false)
            vmess("ws-repeated-alpn", "$base,\"net\":\"ws\",\"alpn\":\"http/1.1,http/1.1\"")
            vmess("grpc-repeated-alpn", "$base,\"net\":\"grpc\",\"path\":\"service\",\"alpn\":\"h2,h2\"")
            vmess("tcp-alpn-list", "$base,\"alpn\":\"custom,h2\"")
            vmess("xhttp-alpn-list", "$base,\"net\":\"xhttp\",\"alpn\":\"h2,h2\"", false)
            vmess("bare-ipv6", base.replace("example.invalid", "2001:db8::1"))
            vmess("non-ip-colon", base.replace("example.invalid", "host:443"), false)
            vmess("non-ip-at", base.replace("example.invalid", "host@example.invalid"), false)
        }
    }
}
