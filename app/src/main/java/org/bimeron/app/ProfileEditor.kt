@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.bimeron.app

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * The edit conflict shown over an editor whose save did not commit. Held by its [EditorDraft], so it
 * disappears with the draft when the editor is closed or replaced.
 */
sealed interface EditorConflict {
    /** The stored profile changed: [merge] rebases [mine] (the draft text at save time) onto [latest]. */
    class Changed(val latest: StoredProfile, val mine: String, val merge: ProfileMerge.Result) : EditorConflict
    /** The stored profile was deleted ([deleted]) or cannot be read: only saving as a new profile is offered. */
    data class Gone(val deleted: Boolean) : EditorConflict
}

/**
 * [baseRevision] is the store revision [profile] was read at when the editor opened; null means the
 * draft has no update authority and any save or delete of it conflicts. Only an explicit [rebase] or
 * [reload] advances both to the latest stored profile. [editGeneration] advances on every text change,
 * pending-protocol-edit toggle, undo, rebase and reload so late results can detect a newer state.
 */
class EditorDraft(profile: Profile, baseRevision: ProfileRevision? = null) {
    var profile by mutableStateOf(profile)
        private set
    var baseRevision: ProfileRevision? = baseRevision
        private set
    var editGeneration = 0L
        private set
    /** Advanced by [rebase] and [reload]; the editor keys its form-local state on it. */
    var session by mutableIntStateOf(0)
        private set
    /** The conflict panel shown for this draft, if any. Main-thread owned. */
    var conflict by mutableStateOf<EditorConflict?>(null)
    val resources = SshDraftResources(runCatching { java.util.UUID.fromString(profile.id) }.getOrElse { java.util.UUID.randomUUID() })
    fun importKey(kind: SshProfileResources.Kind, token: SshProfileResources.StagedToken): Boolean {
        if (rawInvalid) return false
        val reference = resources.accept(token) ?: return false
        set("AccessKey", if (kind == SshProfileResources.Kind.IDENTITY) "IdentityFile" else "KnownHostsFile", reference)
        return true
    }
    var source by mutableStateOf(TextFieldValue(profile.text))
        private set
    var document by mutableStateOf(ConfigDocument.parse(profile.text))
        private set
    var tab by mutableIntStateOf(0)
    private var errorText by mutableStateOf<String?>(null)
    /** Parse failure of the current text; shares one slot with [error] and is resolved for display. */
    var parseError by mutableStateOf<ProfileError?>(null)
        private set
    var error: String?
        get() = errorText
        set(value) { errorText = value; parseError = null }
    private fun failed(e: ProfileError) { errorText = null; parseError = e }
    var rawInvalid by mutableStateOf(false)
        private set
    var validating by mutableStateOf(false)
    private var pendingEdits by mutableStateOf(false)
    var pendingProtocolEdits: Boolean
        get() = pendingEdits
        set(value) { if (pendingEdits != value) { editGeneration++; pendingEdits = value } }
    private val history = ArrayDeque<TextFieldValue>()
    val dirty get() = source.text != profile.text || pendingProtocolEdits
    val canUndo get() = history.isNotEmpty()
    fun update(value: TextFieldValue) {
        if (value.text != source.text) {
            editGeneration++
            history.addLast(source)
            if (history.size > 32) history.removeFirst()
            error = null
        }
        source = value
        try { document = ConfigDocument.parse(value.text); rawInvalid = false }
        catch (e: ProfileError) { rawInvalid = true; failed(e) }
    }
    fun edit(next: ConfigDocument) { update(TextFieldValue(next.render())) }
    fun set(section: String, key: String, value: String) { edit(document.set(section, key, value)) }
    fun undo() {
        if (pendingProtocolEdits || history.isEmpty()) return
        val previous = history.removeLast()
        editGeneration++
        source = previous
        try { document = ConfigDocument.parse(previous.text); rawInvalid = false; error = null }
        catch (e: ProfileError) { rawInvalid = true; failed(e) }
    }
    fun configurationChanged() = document.effective() != ConfigDocument.parse(profile.text).effective()

    /**
     * Applies the user's choices to a [EditorConflict.Changed] conflict: the draft text becomes the merged
     * text and its base becomes the latest stored profile and revision, so the next save compares against
     * it. Nothing is saved. Returns false, changing nothing, while a conflict is unresolved or if the
     * draft text changed since the merge was computed.
     */
    fun applyMerge(choices: Map<MergeField, MergeChoice>): Boolean {
        val pending = conflict as? EditorConflict.Changed ?: return false
        if (!pending.merge.resolved(choices) || source.text != pending.mine || pending.latest.id != profile.id) return false
        update(TextFieldValue(pending.merge.resolve(choices)))
        rebase(pending.latest)
        return true
    }

    /** Discards the draft's edits and continues from [latest], the profile as stored now, at its revision. */
    fun reload(latest: StoredProfile) {
        require(latest.id == profile.id)
        source = TextFieldValue(latest.text)
        try { document = ConfigDocument.parse(latest.text); rawInvalid = false; error = null }
        catch (e: ProfileError) { rawInvalid = true; failed(e) }
        rebase(latest)
    }

    /** Undo does not cross a new base; imports stay owned by [resources] until the draft is disposed. */
    private fun rebase(latest: StoredProfile) {
        history.clear()
        pendingEdits = false
        profile = latest.profile
        baseRevision = latest.revision
        conflict = null
        editGeneration++
        session++
    }
}

@Composable fun ProfileEditor(model: AppModel, profile: Profile, busy: Boolean, saveError: String?, vpn: VpnState, onBack: () -> Unit) {
    val draft = remember(profile.id) { model.editorDraft?.takeIf { it.profile.id == profile.id } ?: model.openEditor(profile) }
    var discard by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var reveal by remember(profile.id) { mutableStateOf(false) }
    var rawReveal by remember(profile.id, draft.tab) { mutableStateOf(false) }
    val original = ConfigDocument.parse(draft.profile.text)
    val protected = ProtocolForms.protected(draft.document) || ProtocolForms.protected(original) ||
        OtherUriProtocolForms.protected(draft.document) || OtherUriProtocolForms.protected(original) ||
        ShadowTlsProtocolForm.protected(draft.document) || ShadowTlsProtocolForm.protected(original) ||
        SsConfForms.protected(original) || SsConfForms.protected(draft.document)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val protection by VpnLive.protection.collectAsStateWithLifecycle()
    val locked = busy || draft.validating || vpn.state in setOf("permission", "connecting", "reconnecting", "stopping")
    fun leave() { model.editorDraft = null; model.clearError(); onBack() }
    val conflictMessages = setOf(stringResource(R.string.profile_conflict_review), stringResource(R.string.profile_deleted_elsewhere),
        stringResource(R.string.profile_conflict_unreadable))
    fun back() { if (!locked) { if (draft.dirty) discard = true else leave() } }
    fun validate(save: Boolean) {
        if (locked || draft.pendingProtocolEdits) return
        if (ShadowTlsProtocolForm.invalid(draft.document)) { draft.error = context.getString(R.string.shadowtls_invalid); return }
        if (ProtocolForms.invalid(draft.document) || SsConfForms.invalid(draft.document)) { draft.error = context.getString(R.string.protocol_form_invalid); return }
        if (OtherUriProtocolForms.invalid(draft.document)) { draft.error = context.getString(R.string.other_uri_invalid); return }
        draft.validating = true
        draft.error = null
        model.clearError()
        val text = draft.source.text
        val generation = draft.editGeneration
        // A result for a replaced or further-edited draft must not close the editor or replace its message.
        fun current() = model.editorDraft === draft && draft.editGeneration == generation
        scope.launch {
            try {
                model.store.validate(text, draft.resources)
                if (!current()) return@launch
                if (save) model.save(text, profile.id, context.getString(R.string.editor_saved), draft.configurationChanged()) { leave() }
                else draft.error = context.getString(R.string.editor_validated)
            } catch (e: ProfileError) { if (current()) draft.error = if (protected) context.getString(R.string.protocol_form_failed) else e.text(context) }
            catch (_: Exception) { if (current()) draft.error = context.getString(R.string.editor_validation_failed) }
            finally { draft.validating = false }
        }
    }
    BackHandler { back() }
    SSConfRefreshConsentHost(model.ssconfRefresh)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = 600.dp).fillMaxSize().imePadding().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { back() }, enabled = !locked) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.profile_editor), fontSize = 20.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { validate(true) }, enabled = !locked && !draft.pendingProtocolEdits && !draft.rawInvalid) { Text(stringResource(R.string.editor_done)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf(stringResource(R.string.editor_general), stringResource(R.string.editor_network), stringResource(R.string.editor_routing), stringResource(R.string.editor_raw_config)).forEachIndexed { index, title ->
                FilterChip(selected = draft.tab == index, onClick = { draft.tab = index }, label = { Text(title, fontSize = 12.sp) }, colors = FilterChipDefaults.filterChipColors(selectedLabelColor = MaterialTheme.colorScheme.primary), enabled = !locked && !draft.pendingProtocolEdits && (!draft.rawInvalid || index == 3))
            }
        }
        val message = draft.error ?: draft.parseError?.text(context) ?: saveError
        if (message != null) Text(if (!protected || message in conflictMessages || message == stringResource(R.string.profile_edit_conflict) || message == stringResource(R.string.profile_no_longer_exists) || message == stringResource(R.string.editor_validated) || message == stringResource(R.string.protocol_form_invalid) || message == stringResource(R.string.other_uri_invalid) || message == stringResource(R.string.shadowtls_invalid)) message else stringResource(R.string.protocol_form_failed), color = if (message == stringResource(R.string.editor_validated)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
        if (locked) LinearProgressIndicator(Modifier.fillMaxWidth())
        // A rebase or reload replaces the draft's base: form-local state (reveals, pending form edits) starts over.
        key(draft.session) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val doc = draft.document
            when (draft.tab) {
                0 -> {
                    EditorField(stringResource(R.string.editor_name), doc.value("Profile", "Name"), !locked) { draft.set("Profile", "Name", it) }
                    val source = doc.source()
                    Text(stringResource(R.string.editor_key_source), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(18.dp)) { Text(stringResource(R.string.editor_type), modifier = Modifier.weight(1f)); Text(ProtocolForms.typeLabel(doc) ?: stringResource(R.string.editor_parameters)) }
                    }
                    if (OtherUriProtocolForms.supported(doc)) {
                        OtherUriProtocolSettings(draft, !locked) { draft.pendingProtocolEdits = it }
                    } else if (source != null && source.first != "File") {
                        OutlinedTextField(colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface), value = source.second, onValueChange = {}, readOnly = true, label = { Text(stringResource(R.string.editor_key_or_link)) }, enabled = !locked, modifier = Modifier.fillMaxWidth(),
                            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                            trailingIcon = {
                                Row {
                                    IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (reveal) stringResource(R.string.editor_hide_key) else stringResource(R.string.editor_show_key)) }
                                    IconButton(onClick = {
                                        val clip = android.content.ClipData.newPlainText("Bimeron access key", source.second)
                                        clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                                        context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip)
                                        android.widget.Toast.makeText(context, context.getString(R.string.editor_key_copied), android.widget.Toast.LENGTH_SHORT).show()
                                    }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.editor_copy_key)) }
                                }
                            })
                    } else EditorHint(stringResource(R.string.editor_source_hint))
                    if (SsConfForms.supported(doc) || SsConfForms.protected(doc)) SsConfSettings(draft, !locked)
                    else ProtocolSettings(draft, !locked)
                    SSConfRefreshSection(model, draft, !locked)
                    if (doc.value("AccessKey", "Type").equals("SSH", ignoreCase = true)) {
                        SshKeyImportButtons(draft, !locked && !draft.rawInvalid, model.store.resources,
                            onStaged = { kind, token -> model.editorDraft === draft && draft.importKey(kind, token) },
                            onFailure = { draft.error = context.getString(R.string.editor_validation_failed) },
                            onCancelled = {})
                    }
                    EditorHint(stringResource(R.string.editor_save_hint))
                    Button(onClick = { delete = true }, enabled = !locked, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.editor_delete_profile)) }
                }
                1 -> {
                    ShadowTlsSettings(draft, !locked)
                    EditorField(stringResource(R.string.editor_interface_name), doc.value("Interface", "Name", "Bimeron"), !locked) { draft.set("Interface", "Name", it) }
                    EditorHint(stringResource(R.string.editor_interface_hint))
                    EditorField("MTU · 1280–1500", doc.value("Interface", "MTU", "1420"), !locked, KeyboardType.Number) { draft.set("Interface", "MTU", it) }
                    EditorField(stringResource(R.string.editor_dns), doc.value("Interface", "DNS", "1.1.1.1"), !locked) { draft.set("Interface", "DNS", it) }
                    EditorHint(stringResource(R.string.editor_dns_hint))
                    EditorChoice("IPv6", doc.value("Interface", "IPv6", "enable"), listOf("enable" to stringResource(R.string.editor_enabled), "disable" to stringResource(R.string.editor_block_ipv6)), !locked) { draft.set("Interface", "IPv6", it) }
                    EditorHint(stringResource(R.string.editor_ipv6_hint))
                    OutlinedButton(onClick = {
                        try { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
                        catch (_: Exception) { draft.error = context.getString(R.string.editor_vpn_settings_error) }
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.editor_block_without_vpn)); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Filled.OpenInNew, null) }
                    EditorHint(stringResource(R.string.editor_vpn_settings_hint))
                    protection?.let { (alwaysOn, lockdown) ->
                        EditorHint(stringResource(R.string.editor_android_status, stringResource(if (alwaysOn) R.string.editor_on_masculine else R.string.editor_off_masculine), stringResource(if (lockdown) R.string.editor_on_feminine else R.string.editor_off_feminine)))
                    }
                    if (doc.value("Interface", "Mode", "vpn") != "vpn" || doc.value("Interface", "KillSwitch", "false") != "false") {
                        EditorHint(stringResource(R.string.editor_android_config_hint))
                    }
                }
                2 -> {
                    val actions = listOf("proxy" to stringResource(R.string.editor_via_vpn), "direct" to stringResource(R.string.editor_direct), "block" to stringResource(R.string.editor_block))
                    EditorChoice(stringResource(R.string.editor_default_action), doc.value("Routing", "Default", "proxy"), actions, !locked) { draft.set("Routing", "Default", it) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.editor_rules_count, doc.rules.size), modifier = Modifier.weight(1f))
                        TextButton(onClick = { draft.edit(doc.addRule()) }, enabled = !locked) { Text(stringResource(R.string.editor_add_rule)) }
                    }
                    EditorHint(stringResource(R.string.editor_rule_order_hint))
                    doc.rules.forEachIndexed { index, rule ->
                        val ruleDescription = stringResource(R.string.editor_enable_rule, index + 1)
                        Surface(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.editor_rule_number, index + 1), modifier = Modifier.weight(1f))
                                    Switch(modifier = Modifier.semantics { contentDescription = ruleDescription }, checked = !rule.disabled, onCheckedChange = { draft.edit(doc.enableRule(index, it)) }, enabled = !locked)
                                }
                                val values = rule.values()
                                val type = if (values.containsKey("CIDR")) "CIDR" else "Domain"
                                EditorChoice(stringResource(R.string.editor_type), type, listOf("CIDR" to "CIDR", "Domain" to "Domain"), !locked) { draft.edit(doc.editRule(index, it, "")) }
                                EditorField(stringResource(R.string.editor_destination), values[type].orEmpty(), !locked) { draft.edit(doc.editRule(index, type, it)) }
                                EditorChoice(stringResource(R.string.editor_action), values["Action"] ?: "proxy", actions, !locked) { draft.edit(doc.editRule(index, "Action", it)) }
                                Row {
                                    IconButton(onClick = { draft.edit(doc.moveRule(index, -1)) }, enabled = !locked && index > 0) { Icon(Icons.Default.ArrowUpward, stringResource(R.string.editor_move_rule_up, index + 1)) }
                                    IconButton(onClick = { draft.edit(doc.moveRule(index, 1)) }, enabled = !locked && index < doc.rules.lastIndex) { Icon(Icons.Default.ArrowDownward, stringResource(R.string.editor_move_rule_down, index + 1)) }
                                    Spacer(Modifier.weight(1f))
                                    IconButton(onClick = { draft.edit(doc.removeRule(index)) }, enabled = !locked) { Icon(Icons.Default.DeleteOutline, stringResource(R.string.editor_delete_rule, index + 1)) }
                                }
                            }
                        }
                    }
                    if (doc.rules.isEmpty()) EditorHint(stringResource(R.string.editor_no_rules))
                    EditorHint(stringResource(R.string.editor_domain_hint))
                }
                3 -> {
                    EditorHint(stringResource(R.string.editor_raw_hint))
                    if (protected) TextButton(onClick = { rawReveal = !rawReveal }, enabled = !locked) { Text(stringResource(if (rawReveal) R.string.protocol_form_hide else R.string.protocol_form_show)) }
                    if (!protected || rawReveal) OutlinedTextField(colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface), value = draft.source, onValueChange = draft::update, enabled = !locked, modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
                        label = { Text(stringResource(R.string.editor_raw_config)) }, textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Password),
                        visualTransformation = IniHighlight(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onSurfaceVariant), minLines = 12)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { validate(false) }, enabled = !locked && !draft.pendingProtocolEdits && !draft.rawInvalid) { Text(stringResource(R.string.editor_validate)) }
                        OutlinedButton(onClick = { draft.edit(doc.formatted()) }, enabled = !locked && !draft.pendingProtocolEdits && !draft.rawInvalid) { Text(stringResource(R.string.editor_format)) }
                        OutlinedButton(onClick = draft::undo, enabled = !locked && !draft.pendingProtocolEdits && draft.canUndo) { Text(stringResource(R.string.editor_undo)) }
                    }
                }
            }
        }
        }
    }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.editor_discard_title)) }, text = { Text(stringResource(R.string.editor_discard_message)) },
        confirmButton = { TextButton(onClick = { leave() }) { Text(stringResource(R.string.editor_discard)) } }, dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.editor_continue_editing)) } })
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text(stringResource(R.string.delete_confirm)) }, text = { if (vpn.activeProfileId == profile.id) Text(stringResource(R.string.delete_active_warning)) },
        confirmButton = { TextButton(onClick = { delete = false; model.delete(profile.id) { leave() } }) { Text(stringResource(R.string.delete)) } }, dismissButton = { TextButton(onClick = { delete = false }) { Text(stringResource(R.string.cancel)) } })
    draft.conflict?.let { conflict ->
        // Operation failures use the same protected-profile wording as the editor's message line.
        val failure = saveError?.takeIf { it !in conflictMessages }?.let { if (protected) stringResource(R.string.protocol_form_failed) else it }
        EditorConflictDialog(model, draft, conflict, locked, failure) { leave() }
    }
}

@Composable private fun EditorHint(text: String) { Text(text, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun EditorField(label: String, value: String, enabled: Boolean, keyboard: KeyboardType = KeyboardType.Text, change: (String) -> Unit) {
    OutlinedTextField(colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface), value = value, onValueChange = change, label = { Text(label) }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false))
}
@Composable internal fun EditorChoice(label: String, value: String, options: List<Pair<String, String>>, enabled: Boolean, change: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }, enabled = enabled, shape = RoundedCornerShape(5.dp), colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface), contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxWidth()) { Text(options.firstOrNull { it.first == value }?.second ?: value, modifier = Modifier.weight(1f)); Icon(Icons.Default.ExpandMore, null) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) { options.forEach { (key, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { open = false; change(key) }) } }
        }
    }
}
private class IniHighlight(val primary: Color, val comment: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val result = AnnotatedString.Builder(text)
        var offset = 0
        text.text.split('\n').forEach { line ->
            val trimmed = line.trimStart()
            val color = if (trimmed.startsWith('#') || trimmed.startsWith(';')) comment else primary
            val length = if (trimmed.startsWith('[') || trimmed.startsWith('#') || trimmed.startsWith(';')) line.length else line.indexOf('=').coerceAtLeast(0)
            if (length > 0) result.addStyle(SpanStyle(color = color), offset, offset + length)
            offset += line.length + 1
        }
        return TransformedText(result.toAnnotatedString(), OffsetMapping.Identity)
    }
}
