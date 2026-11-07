package org.bimeron.app

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
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

    /** Display type of an ssconf source; the stored key ("SSConf" or "Key") is unchanged. */
    fun displayType(doc: ConfigDocument): String? =
        if (supported(doc) || doc.source()?.first == "SSConf") "ssconf" else null

    /**
     * [invalid], plus a link that an edit in the link field ([editingLink]) made unrepresentable as an ssconf
     * form (e.g. a `Key` without the `ssconf://` prefix), so the field never silently turns into another source.
     */
    fun linkInvalid(doc: ConfigDocument, editingLink: Boolean): Boolean = invalid(doc) || editingLink && rawOnly(doc)

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

/**
 * The link format hint and check under the editor's "Key or link" field, which edits an ssconf link in place
 * ([editingLink]); otherwise the raw-only notice.
 */
@Composable internal fun SsConfSettings(draft: EditorDraft, enabled: Boolean, editingLink: Boolean) {
    val doc = draft.document
    if (!editingLink) {
        Text(stringResource(R.string.ssconf_raw_only))
        TextButton(onClick = { draft.tab = 3 }, enabled = enabled) { Text(stringResource(R.string.ssconf_raw_config)) }
        return
    }
    Text(stringResource(R.string.ssconf_local_only))
    if (SsConfForms.linkInvalid(doc, editingLink)) Text(stringResource(R.string.ssconf_invalid), color = MaterialTheme.colorScheme.error)
}
