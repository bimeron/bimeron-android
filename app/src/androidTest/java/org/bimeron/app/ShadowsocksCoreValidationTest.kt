package org.bimeron.app

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import org.bimeron.core.androidcore.Androidcore
import org.bimeron.core.androidcore.SocketProtector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Embedded AAR constructor validation only: no Start, sockets, peers, or handshake proof. */
@RunWith(AndroidJUnit4::class)
class ShadowsocksCoreValidationTest {
    // Synthetic adaptations of bimeron-core 0d5fd06bbb9d7b00942e50606fc0017851ca6c24:
    // internal/accesskey/{accesskey,ss2022,plugin,simpleobfs}_test.go and
    // internal/config/{config,plugin}_test.go. CoreProtocolValidationTest supplies the JNI API shape.
    private fun document(access: String) =
        "[Profile]\nVersion = 1\nName = TEST\n[AccessKey]\n$access\n" +
            "[Interface]\nMode = vpn\nKillSwitch = false\nDNS = 192.0.2.53\n"

    private fun base64(value: ByteArray) = Base64.encodeToString(value, Base64.NO_WRAP)
    private fun key(size: Int, value: Int = 0) = base64(ByteArray(size) { value.toByte() })
    private fun query(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun explicit(method: String, secret: String, extra: String = "") = document(
        "Type = shadowsocks\nServer = 192.0.2.1\nPort = 8388\n" +
            "Method = $method\nPassword = $secret\n$extra"
    )

    private fun uri(method: String, secret: String, suffix: String = ""): String {
        val auth = Base64.encodeToString(
            "$method:$secret".toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        return document("SS = ss://$auth@192.0.2.1:8388/$suffix")
    }

    private fun validate(id: String, text: String, accepted: Boolean) {
        val protected = AtomicBoolean(false)
        var constructed = false
        try {
            val session = try {
                Androidcore.newSession(text.toByteArray(Charsets.UTF_8), object : SocketProtector {
                    override fun protect(fd: Long): Boolean {
                        protected.set(true)
                        throw AssertionError("$id attempted socket protection; no-handshake validation only")
                    }
                })
            } catch (_: Exception) {
                // Do not expose Core errors, fixture credentials, or exception causes to test reports.
                null
            }
            if (session != null) {
                constructed = true
                try {
                    session.stop()
                } catch (_: Exception) {
                    throw AssertionError("$id failed to stop unstarted session")
                }
            }
        } finally {
            assertFalse("$id attempted socket protection", protected.get())
        }
        assertEquals("$id constructor acceptance (no handshake)", accepted, constructed)
    }

    private fun both(id: String, method: String, secret: String, accepted: Boolean) {
        validate("$id-uri", uri(method, secret), accepted)
        validate("$id-explicit", explicit(method, secret), accepted)
    }

    @Test fun classicSsConstructorValidation_noHandshake() {
        for (method in listOf("aes-256-gcm", "chacha20-ietf-poly1305")) {
            both(method, method, "TEST", true)
        }
        both("unsupported-cipher", "rc4-md5", "TEST", false)
        both("empty-password", "aes-256-gcm", "", false)
    }

    @Test fun ss2022KeysAndChainsConstructorValidation_noHandshake() {
        for ((method, size) in listOf(
            "2022-blake3-aes-128-gcm" to 16,
            "2022-blake3-aes-256-gcm" to 32,
            "2022-blake3-chacha20-poly1305" to 32
        )) {
            val key = key(size, 255)
            both("$method-single", method, key, true)
            both("$method-chain", method, "$key:${key(size, 254)}:$key",
                method != "2022-blake3-chacha20-poly1305")
            for ((index, invalid) in listOf(
                "", "invalid", key(24), key.dropLast(1),
                ":$key", "$key:", "$key::$key", "$key:invalid", "$key:${key(24)}"
            ).withIndex()) {
                both("$method-malformed-$index", method, invalid, false)
            }
        }
    }

    @Test fun pluginOptionsConstructorValidation_noHandshake() {
        val cases = listOf(
            Triple("v2ray-plugin", "", true),
            Triple("v2ray-plugin", "tls;host=ws.example;path=tunnel;mux=8", true),
            Triple("v2ray-plugin", "tls=false;path=/a\\;b\\=c;host=ws.example;mux=0", true),
            Triple("v2ray-plugin", "mode=quic", false),
            Triple("v2ray-plugin", "mux=-1", false),
            Triple("v2ray-plugin", "mux=4294967296", false),
            Triple("v2ray-plugin", "tls;tls", false),
            Triple("v2ray-plugin", "unknown=TEST", false),
            Triple("obfs-local", "obfs=http", true),
            Triple("obfs-local", "obfs=tls;obfs-host=cover.example", true),
            Triple("obfs-local", "obfs=http;obfs-host=cover.example;obfs-uri=/a%2Fb?q=x%2By;http-method=POST", true),
            Triple("obfs-local", "", false),
            Triple("obfs-local", "obfs=quic", false),
            Triple("obfs-local", "obfs=http;obfs=tls", false),
            Triple("obfs-local", "obfs=tls;obfs-uri=/", false),
            Triple("obfs-local", "obfs=http;unknown=TEST", false),
            Triple("unknown", "", false)
        )
        cases.forEachIndexed { index, (plugin, options, accepted) ->
            val settings = plugin + if (options.isEmpty()) "" else ";$options"
            validate("plugin-$index-uri", uri("aes-256-gcm", "TEST", "?plugin=${query(settings)}"), accepted)
            // Core config rejects blank fields; omitted PluginOpts lets the plugin apply defaults.
            val explicitOptions = if (options.isEmpty()) "" else "PluginOpts = $options\n"
            validate("plugin-$index-explicit", explicit("aes-256-gcm", "TEST",
                "Plugin = $plugin\n$explicitOptions"), accepted)
        }
        validate("blank-plugin-options-explicit", explicit("aes-256-gcm", "TEST",
            "Plugin = v2ray-plugin\nPluginOpts = \n"), false)
        validate("orphan-plugin-options", explicit("aes-256-gcm", "TEST", "PluginOpts = tls\n"), false)
        validate("duplicate-plugin-query", uri("aes-256-gcm", "TEST",
            "?plugin=v2ray-plugin&plugin=v2ray-plugin"), false)
    }
}
