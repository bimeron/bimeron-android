package org.bimeron.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.*
import java.net.URI

internal object SsConfForms {
    fun supported(doc: ConfigDocument): Boolean = doc.source()?.let { (key, value) ->
        key == "SSConf" || key == "Key" && value.startsWith("ssconf://")
    } == true

    fun protected(doc: ConfigDocument): Boolean =
        doc.value("AccessKey", "SSConf", "\u0000") != "\u0000" ||
            Conf.sources.any { doc.value("AccessKey", it).trimStart().substringBefore(':').equals("ssconf", ignoreCase = true) }

    fun rawOnly(doc: ConfigDocument): Boolean = !supported(doc) ||
        doc.source()!!.second.any(Char::isISOControl) ||
        Conf.sources.count { doc.value("AccessKey", it, "\u0000") != "\u0000" } != 1 ||
        doc.value("AccessKey", "Type").isNotEmpty()

    // Local URL shape only; resolution and connection remain the core's responsibility.
    fun invalid(doc: ConfigDocument): Boolean {
        if (rawOnly(doc)) return false
        val value = doc.source()!!.second
        if (!value.startsWith("ssconf://") || value.any { it.isWhitespace() || it.isISOControl() }) return true
        return try {
            val url = URI("https://" + value.removePrefix("ssconf://"))
            url.host.isNullOrEmpty() || url.rawUserInfo != null || url.port !in -1..65535
        } catch (_: Exception) { true }
    }
}

@Composable internal fun SsConfSettings(draft: EditorDraft, enabled: Boolean) {
    val doc = draft.document
    if (SsConfForms.rawOnly(doc)) {
        Text(stringResource(R.string.ssconf_raw_only))
        TextButton(onClick = { draft.tab = 3 }, enabled = enabled) { Text(stringResource(R.string.ssconf_raw_config)) }
        return
    }
    val (sourceKey, source) = doc.source() ?: return
    var reveal by remember(draft, draft.tab, sourceKey) { mutableStateOf(false) }
    OutlinedTextField(
        value = source,
        onValueChange = { draft.set("AccessKey", sourceKey, it) },
        label = { Text(stringResource(R.string.ssconf_source)) },
        enabled = enabled && !draft.rawInvalid,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
        trailingIcon = {
            TextButton(onClick = { reveal = !reveal }, enabled = enabled) {
                Text(stringResource(if (reveal) R.string.ssconf_hide else R.string.ssconf_show))
            }
        },
    )
    Text(stringResource(R.string.ssconf_local_only))
    if (SsConfForms.invalid(doc)) Text(stringResource(R.string.ssconf_invalid), color = MaterialTheme.colorScheme.error)
}
