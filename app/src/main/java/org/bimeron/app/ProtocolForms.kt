package org.bimeron.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.*

internal object ProtocolForms {
    private val descriptors: List<ProtocolFormDescriptor> = listOf(ShadowsocksForm, Socks5Form, SshProtocolForm, VlessProtocolForm, HttpConnectForm, NaiveH2Form, MasqueForm, TrojanProtocolForm)
    val methods get() = ShadowsocksForm.methods
    val plugins get() = ShadowsocksForm.plugins
    fun descriptor(doc: ConfigDocument): ProtocolFormDescriptor? =
        if (doc.source() != null) null else descriptors.firstOrNull { doc.value("AccessKey", "Type") in it.types }
    fun typeLabel(doc: ConfigDocument): String? = doc.source()?.first ?: when (doc.value("AccessKey", "Type")) {
        "ss", "shadowsocks" -> "Shadowsocks"
        "socks5" -> "SOCKS5"
        "vless" -> "VLESS"
        else -> null
    }
    fun supported(doc: ConfigDocument) = descriptor(doc) != null
    // Raw profiles may carry credentials in any section, including future schemas.
    fun protected(@Suppress("UNUSED_PARAMETER") doc: ConfigDocument): Boolean = true
    fun fields(doc: ConfigDocument) = (descriptors.firstOrNull { doc.value("AccessKey", "Type") in it.types } ?: ShadowsocksForm).fields(doc).map { it.key }
    fun rawOnly(doc: ConfigDocument): Boolean {
        val descriptor = descriptor(doc) ?: return true
        return doc.sections.any { it !in setOf("Profile", "AccessKey", "Interface", "Routing", "Rule") } ||
            doc.values("AccessKey").keys.any { it != "Type" && it !in descriptor.fields.map { field -> field.key } } ||
            descriptor.fields.any { doc.value("AccessKey", it.key).any(Char::isISOControl) } || descriptor.rawOnly(doc)
    }
    fun invalid(doc: ConfigDocument): Boolean {
        val descriptor = descriptor(doc) ?: return false
        if (rawOnly(doc)) return false
        return doc.value("AccessKey", "Server").isBlank() ||
            doc.value("AccessKey", "Port").toIntOrNull() !in 1..65535 || descriptor.invalid(doc)
    }
}

@Composable internal fun ProtocolSettings(draft: EditorDraft, enabled: Boolean) {
    val doc = draft.document
    if (ProtocolForms.rawOnly(doc)) {
        Text(stringResource(R.string.protocol_form_raw_only))
        TextButton(onClick = { draft.tab = 3 }, enabled = enabled) { Text(stringResource(R.string.editor_raw_config)) }
        return
    }
    val descriptor = ProtocolForms.descriptor(doc) ?: return
    descriptor.fields(doc).forEach { field ->
        val key = field.key
        key(key) {
            val label = stringResource(field.labelRes)
            val options = field.options
            if (options != null) {
                EditorChoice(label, doc.value("AccessKey", key), options.map { it to it.ifEmpty { "—" } }, enabled) { draft.set("AccessKey", key, it) }
            } else {
                val secret = field.secret
                var reveal by remember(draft.profile.id) { mutableStateOf(false) }
                val error = descriptor.fieldError(doc, key, draft.resources)
                OutlinedTextField(value = doc.value("AccessKey", key), onValueChange = { draft.set("AccessKey", key, it) },
                    label = { Text(label) }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = error != null, supportingText = error?.let { { Text(stringResource(it)) } },
                    visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = when (field.keyboardType) { ProtocolKeyboardType.TEXT -> KeyboardType.Text; ProtocolKeyboardType.NUMBER -> KeyboardType.Number; ProtocolKeyboardType.PASSWORD -> KeyboardType.Password }),
                    trailingIcon = if (secret) ({ TextButton(onClick = { reveal = !reveal }, enabled = enabled) { Text(stringResource(if (reveal) R.string.protocol_form_hide else R.string.protocol_form_show)) } }) else null)
            }
            if (field.removable) {
                TextButton(onClick = { draft.edit(doc.remove("AccessKey", key)) }, enabled = enabled) {
                    Text(stringResource(R.string.delete))
                }
            }
        }
    }
    descriptor.hintRes?.let { Text(stringResource(it)) }
    if (ProtocolForms.invalid(doc)) Text(stringResource(R.string.protocol_form_invalid), color = MaterialTheme.colorScheme.error)
}
