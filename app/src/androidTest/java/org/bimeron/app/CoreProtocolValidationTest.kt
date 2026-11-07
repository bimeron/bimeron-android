package org.bimeron.app

import android.content.Context
import android.content.ContextWrapper
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.bimeron.core.androidcore.Androidcore
import org.bimeron.core.androidcore.SocketProtector
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreProtocolValidationTest {
    private val uuid = "123e4567-e89b-12d3-a456-426614174000"
    private fun document(source: String) = "[Profile]\nVersion = 1\nName = TEST\n[AccessKey]\n$source\n[Interface]\nMode = vpn\nKillSwitch = false\nDNS = 192.0.2.53\n"
    private fun uri(value: String) = document("${Conf.keyField(value)} = $value")
    private fun explicit(type: String, extra: String = "") = document("Type = $type\nServer = 192.0.2.1\nPort = 443\n$extra")
    private fun accepted() = buildList {
        listOf("ss://aes-256-gcm:TEST@192.0.2.1:443", "socks5://192.0.2.1:1080",
            "vless://$uuid@192.0.2.1:443?encryption=none&security=tls&type=tcp",
            "trojan://TEST@example.invalid:443", "anytls://TEST@example.invalid:443",
            "tuic://$uuid:TEST@example.invalid:443?udp_relay_mode=quic",
            "hysteria2://TEST@example.invalid:443", "hy2://TEST@example.invalid:443",
            "http://TEST:TEST@192.0.2.1:8080", "https://TEST:TEST@example.invalid:443",
            "naive+https://TEST:TEST@example.invalid:443", "naive+quic://TEST:TEST@example.invalid:443",
            "masque://TEST:TEST@example.invalid:443",
            "ssh://TEST@example.invalid:22").forEach { add("uri-${it.substringBefore(":")}" to uri(it)) }
        val json = """{"v":"2","add":"example.invalid","port":"443","id":"$uuid","aid":"0","scy":"auto","net":"tcp","tls":"tls"}"""
        add("uri-vmess" to uri("vmess://" + Base64.encodeToString(json.toByteArray(), Base64.NO_WRAP)))
        listOf("ss", "shadowsocks").forEach { add("explicit-$it" to explicit(it, "Method = aes-256-gcm\nPassword = TEST")) }
        listOf("socks5", "http", "https", "masque", "naive").forEach { add("explicit-$it" to explicit(it, "Username = TEST\nPassword = TEST")) }
        // SSH acceptance checks parsing only; no identity files or backend startup.
        add("explicit-ssh" to explicit("ssh", "Username = TEST"))
        add("explicit-trojan" to explicit("trojan", "Password = TEST"))
        add("explicit-vless" to explicit("vless", "ID = $uuid\nEncryption = none\nNetwork = tcp\nSecurity = tls"))
    }
    private fun rejected() = listOf(
        "uri-anytls-insecure" to uri("anytls://TEST@example.invalid:443?insecure=1"),
        "uri-hy2-duplicate-sni" to uri("hy2://TEST@example.invalid:443?sni=example.invalid&sni=other.invalid"),
        "uri-naive-insecure" to uri("naive+https://TEST:TEST@example.invalid:443?insecure=1"),
        "uri-vmess-base64" to uri("vmess://not-base64"),
        "uri-tuic-uuid" to uri("tuic://bad:TEST@example.invalid:443"),
        "uri-trojan-security" to uri("trojan://TEST@example.invalid:443?security=none"),
        "uri-masque-option" to uri("masque://example.invalid:443?unknown=TEST"),
        "uri-vless-uuid" to uri("vless://bad@example.invalid:443"),
        "explicit-unknown" to explicit("future"),
        "explicit-vmess-unsupported" to explicit("vmess"),
        "explicit-socks-auth-pair" to explicit("socks5", "Username = TEST"),
        "explicit-ss-cipher" to explicit("ss", "Method = invalid\nPassword = TEST"),
        "explicit-vless-id" to explicit("vless", "ID = bad\nEncryption = none\nNetwork = tcp\nSecurity = tls"),
        "android-proxy-mode" to explicit("socks5").replace("Mode = vpn", "Mode = proxy"),
        "android-killswitch" to explicit("socks5").replace("KillSwitch = false", "KillSwitch = true")
    )
    private suspend fun isolated(block: suspend (ProfileStore, File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File.createTempFile("protocol-validation-", "", context.cacheDir)
        check(root.delete() && root.mkdir())
        try {
            val wrapper = object : ContextWrapper(context) { override fun getFilesDir() = root }
            block(ProfileStore(wrapper), root)
        } finally { check(root.deleteRecursively()) }
    }
    private fun snapshot(root: File) = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).path to it.readBytes().toList()
    }
    @Test fun supportedUriAndExplicitDocuments() = runBlocking {
        isolated { store, _ -> accepted().forEach { (id, text) ->
            try { store.validate(text, java.util.UUID.randomUUID()); val saved = store.save(text); assertEquals(text, store.load().first.single { it.id == saved.id }.text) }
            catch (e: Exception) { throw AssertionError("$id rejected by packaged Android Core", e) }
        } }
    }
    @Test fun coreRejectionsPreserveStoreAndAllowRecovery() = runBlocking {
        isolated { store, root ->
            val baseline = store.save(explicit("socks5"))
            rejected().forEach { (id, text) ->
                ConfigDocument.parse(text).validate() // Prove the Android document gate accepts this fixture.
                var rejected = false
                try {
                    val session = Androidcore.newSession(text.toByteArray(), object : SocketProtector {
                        override fun protect(fd: Long): Boolean = throw AssertionError("$id attempted socket protection")
                    })
                    session.stop()
                } catch (e: Exception) { rejected = true; assertFalse(id, e.message.isNullOrBlank()) }
                assertTrue("$id must reach and be rejected by Core", rejected)
                val files = snapshot(root)
                val listing = store.load()
                for (target in listOf(null, baseline.id)) {
                    try { store.save(text, target); fail("$id accepted ${target ?: "create"}") }
                    catch (_: ProfileError) { }
                    assertEquals("$id bytes/files", files, snapshot(root))
                    assertEquals("$id IDs/list", listing, store.load())
                }
                val recovered = store.save(baseline.text, baseline.id)
                assertEquals(id, baseline.id, recovered.id)
                assertEquals(id, files, snapshot(root))
            }
        }
    }
}
