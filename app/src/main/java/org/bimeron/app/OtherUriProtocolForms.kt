package org.bimeron.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

internal object OtherUriProtocolForms {
    private fun recognized(doc: ConfigDocument): Boolean {
        val (key, uri) = doc.source() ?: return false
        val scheme = uri.substringBefore(':').lowercase()
        return key in setOf("Hysteria2", "VMess", "AnyTLS", "TUIC") ||
            (key == "Naive" && scheme == "naive+quic") ||
            (key == "Key" && scheme in OtherUriParser.schemes)
    }
    internal fun value(doc: ConfigDocument): OtherUriValue? {
        val (key, uri) = doc.source() ?: return null
        val absent = "\u0000other-uri-absent"
        if (Conf.sources.count { doc.value("AccessKey", it, absent) != absent } != 1 ||
            doc.value("AccessKey", "Type", absent) != absent) return null
        val expected = OtherUriParser.schemes[uri.substringBefore("://")] ?: return null
        if (key != "Key" && key != expected) return null
        return OtherUriParser.parse(uri)
    }
    fun supported(doc: ConfigDocument): Boolean = recognized(doc)
    fun rawOnly(doc: ConfigDocument): Boolean = recognized(doc) && value(doc) == null
    fun invalid(doc: ConfigDocument): Boolean = value(doc)?.valid == false
    fun protected(doc: ConfigDocument): Boolean = recognized(doc)
}

/** A failed candidate never replaces the source; buffers remain available for correction. */
internal fun otherUriCandidate(original: String, fields: Map<String, String>): String? = runCatching {
    val originalModel = requireNotNull(OtherUriParser.parse(original))
    var model = originalModel
    fields.filter { (field, value) -> originalModel.fields[field] != value }.forEach { (field, value) ->
        model = requireNotNull(OtherUriParser.parse(model.edit(field, value)))
    }
    require(model.valid)
    model.original
}.getOrNull()

@Composable internal fun OtherUriProtocolSettings(
    draft: EditorDraft,
    enabled: Boolean,
    onPendingChange: (Boolean) -> Unit = {},
) {
    val doc = draft.document
    if (!OtherUriProtocolForms.supported(doc)) return
    val source = doc.source() ?: return
    val model = OtherUriProtocolForms.value(doc)
    if (model == null) {
        Text(stringResource(R.string.other_uri_raw_only))
        TextButton(onClick = { draft.tab = 3 }, enabled = enabled) { Text(stringResource(R.string.other_uri_raw)) }
        return
    }
    var buffers by remember(draft.profile.id, source) { mutableStateOf(model.fields) }
    val pending = buffers != model.fields
    val candidate = remember(source, buffers) { otherUriCandidate(source.second, buffers) }
    buffers.forEach { (field, value) ->
        key(source.first, field) {
            // All credentials and query/payload values are masked, including SNI and ALPN.
            val secret = field !in setOf("host", "port", "add")
            var reveal by remember(draft.profile.id) { mutableStateOf(false) }
            val label = stringResource(when (field) {
                "host", "add" -> R.string.other_uri_host
                "port" -> R.string.other_uri_port
                "username", "id" -> R.string.other_uri_username
                "password", "auth" -> R.string.other_uri_password
                else -> R.string.other_uri_option
            }) + if (field in setOf("sni", "alpn", "fp", "scy", "ps", "insecure", "udp_relay_mode")) " ($field)" else ""
            val input: @Composable (KeyboardOptions) -> Unit = { keyboardOptions ->
                OutlinedTextField(value = value, onValueChange = { edited ->
                    buffers = buffers + (field to edited)
                    if (buffers != model.fields) onPendingChange(true)
                }, label = { Text(label) }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = keyboardOptions,
                    trailingIcon = if (secret) ({ TextButton(onClick = { reveal = !reveal }, enabled = enabled) { Text(stringResource(if (reveal) R.string.other_uri_hide else R.string.other_uri_show)) } }) else null)
            }
            if (secret) SecretInput(SecretKeyboard.Masked, input)
            else input(KeyboardOptions(autoCorrectEnabled = false, keyboardType = if (field == "port") KeyboardType.Number else KeyboardType.Text))
        }
    }
    if (pending) {
        Text(stringResource(R.string.other_uri_pending))
        TextButton(onClick = {
            candidate?.let { applied ->
                draft.set("AccessKey", source.first, applied)
                buffers = requireNotNull(OtherUriParser.parse(applied)).fields
                onPendingChange(false)
            }
        }, enabled = enabled && candidate != null) { Text(stringResource(R.string.other_uri_apply)) }
    }
    TextButton(onClick = {
        buffers = OtherUriProtocolForms.value(draft.document)?.fields ?: model.fields
        onPendingChange(false)
    }, enabled = enabled) { Text(stringResource(R.string.other_uri_revert)) }
    Text(stringResource(R.string.other_uri_native))
    if (candidate == null) Text(stringResource(R.string.other_uri_invalid), color = MaterialTheme.colorScheme.error)
}
