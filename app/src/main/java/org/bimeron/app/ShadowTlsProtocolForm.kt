package org.bimeron.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

/** Optional strict-v3 wrapper. Inner protocol validation remains with its form and Core. */
internal object ShadowTlsProtocolForm {
    val fields = listOf("ServerName", "Password")
    fun present(doc: ConfigDocument) = "ShadowTLS" in doc.sections
    fun protected(doc: ConfigDocument) = present(doc)
    fun supported(doc: ConfigDocument): Boolean {
        if (doc.source() != null) return false
        fun value(key: String) = doc.value("AccessKey", key)
        if (value("Plugin").isNotEmpty() || value("ALPN").contains("h3")) return false
        // Extended and split-download XHTTP are deliberately left to protected Raw.
        if (doc.render().lineSequence().any {
            val key = it.trim().substringBefore('=').trim()
            key.startsWith("XHTTP") && key !in listOf("XHTTPPath", "XHTTPHost", "XHTTPMode")
        }) return false
        return when (value("Type")) {
            "ss", "shadowsocks" -> value("Method") in listOf("chacha20-ietf-poly1305", "aes-128-gcm", "aes-256-gcm", "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305")
            "vless" -> value("Security") in listOf("tls", "reality") && value("Network") in listOf("", "tcp", "ws", "grpc", "xhttp")
            "trojan" -> value("Network") in listOf("", "tcp", "ws", "grpc") && value("Security") in listOf("", "tls")
            "ssh" -> true
            "naive" -> doc.values("AccessKey").keys.all {
                it in listOf("Type", "Server", "Port", "Username", "Password", "ServerName", "CAFile")
            }
            else -> false
        }
    }
    fun rawOnly(doc: ConfigDocument): Boolean {
        if (!supported(doc)) return true
        return doc.values("ShadowTLS").any { (key, value) ->
            key !in fields || value.any(Char::isISOControl)
        }
    }

    fun invalid(doc: ConfigDocument): Boolean {
        if (!present(doc) || rawOnly(doc)) return false
        val name = doc.value("ShadowTLS", "ServerName")
        val password = doc.value("ShadowTLS", "Password")
        return !validName(name) || password.isEmpty() || password.any { it == '\u0000' || it == '\r' || it == '\n' } ||
            !Charsets.UTF_8.newEncoder().canEncode(password)
    }
    private fun validName(name: String): Boolean {
        if (name.isEmpty() || name.length > 253) return false
        if (':' in name) return name.all { it in "0123456789abcdefABCDEF:." } && runCatching {
            java.net.InetAddress.getByName(name) != null
        }.getOrDefault(false)
        return name.removeSuffix(".").split('.').all { label ->
            label.length in 1..63 && label.first() != '-' && label.last() != '-' && label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
        }
    }
}

@Composable internal fun ShadowTlsSettings(draft: EditorDraft, enabled: Boolean) {
    val doc = draft.document
    // An incompatible profile without a wrapper has no ShadowTLS settings to edit.
    if (!draft.rawInvalid && !ShadowTlsProtocolForm.present(doc) && !ShadowTlsProtocolForm.supported(doc)) return
    Text(stringResource(R.string.shadowtls_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.shadowtls_guidance))
    if (draft.rawInvalid || ShadowTlsProtocolForm.rawOnly(doc)) {
        Text(stringResource(R.string.shadowtls_raw_only))
        TextButton(onClick = { draft.tab = 3 }, enabled = enabled) { Text(stringResource(R.string.shadowtls_raw)) }
        return
    }
    if (!ShadowTlsProtocolForm.present(doc)) {
        TextButton(onClick = { draft.set("ShadowTLS", "ServerName", "") }, enabled = enabled) {
            Text(stringResource(R.string.shadowtls_add))
        }
        return
    }
    ShadowTlsProtocolForm.fields.forEach { field ->
        key(field) {
            var reveal by remember(draft.profile.id) { mutableStateOf(false) }
            SecretInput(SecretKeyboard.Masked) { keyboardOptions ->
                OutlinedTextField(value = doc.value("ShadowTLS", field), onValueChange = { draft.set("ShadowTLS", field, it) },
                    label = { Text(stringResource(if (field == "ServerName") R.string.shadowtls_name else R.string.shadowtls_password)) },
                    enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = keyboardOptions,
                    trailingIcon = { TextButton(onClick = { reveal = !reveal }, enabled = enabled) {
                        Text(stringResource(if (reveal) R.string.shadowtls_hide else R.string.shadowtls_show))
                    } })
            }
        }
    }
    if (ShadowTlsProtocolForm.invalid(doc)) Text(stringResource(R.string.shadowtls_invalid), color = MaterialTheme.colorScheme.error)
}
