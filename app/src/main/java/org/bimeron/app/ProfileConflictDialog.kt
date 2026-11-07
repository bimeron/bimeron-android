package org.bimeron.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

/**
 * The edit-conflict panel over the editor. For a changed profile it lists the conflicting fields with
 * mine/theirs choices (secrets masked until revealed per field) and applies the merge locally; nothing
 * is saved until the user saves again. For a deleted or unreadable profile only save-as-new is offered.
 * After a delete conflict it asks to delete anyway ([active]: the profile's VPN would be stopped first).
 * [failure] is the latest operation error to show inside the panel, if any.
 */
@Composable internal fun EditorConflictDialog(model: AppModel, draft: EditorDraft, conflict: EditorConflict, locked: Boolean,
                                              failure: String?, active: Boolean, onClosed: () -> Unit) {
    val context = LocalContext.current
    var confirmReload by remember(conflict) { mutableStateOf(false) }
    val saveAsNew = { model.saveAsNew(context.getString(R.string.profile_saved_as_new)) { onClosed() }; Unit }
    val properties = DialogProperties(dismissOnClickOutside = false)
    val failureText: @Composable () -> Unit = { failure?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
    when (conflict) {
        EditorConflict.ChangedBeforeDelete -> AlertDialog(
            onDismissRequest = { if (!locked) draft.conflict = null }, properties = properties,
            title = { Text(stringResource(R.string.profile_delete_changed_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.profile_delete_changed_message))
                    if (active) Text(stringResource(R.string.delete_active_warning))
                    failureText()
                }
            },
            confirmButton = { TextButton(onClick = { model.deleteAnyway { onClosed() } }, enabled = !locked) { Text(stringResource(R.string.profile_delete_anyway)) } },
            dismissButton = { TextButton(onClick = { draft.conflict = null }, enabled = !locked) { Text(stringResource(R.string.cancel)) } })
        is EditorConflict.Gone -> AlertDialog(
            onDismissRequest = { if (!locked) draft.conflict = null }, properties = properties,
            title = { Text(stringResource(if (conflict.deleted) R.string.profile_conflict_deleted_title else R.string.profile_conflict_unreadable_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(if (conflict.deleted) R.string.profile_conflict_deleted_message else R.string.profile_conflict_unreadable_message))
                    failureText()
                }
            },
            confirmButton = { TextButton(onClick = saveAsNew, enabled = !locked) { Text(stringResource(R.string.profile_conflict_save_new)) } },
            dismissButton = { TextButton(onClick = { draft.conflict = null }, enabled = !locked) { Text(stringResource(R.string.cancel)) } })
        is EditorConflict.Changed -> {
            val merge = conflict.merge
            val choices = remember(conflict) { mutableStateMapOf<MergeField, MergeChoice>() }
            val revealed = remember(conflict) { mutableStateMapOf<MergeField, Boolean>() }
            // Set when the chosen merge fails the local validation; cleared by any new choice.
            var invalid by remember(conflict) { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { if (!locked) draft.conflict = null }, properties = properties,
                title = { Text(stringResource(R.string.profile_conflict_title)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.profile_conflict_message))
                        Text(stringResource(R.string.profile_conflict_auto_merged, merge.autoMerged), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        merge.wholeTextReason?.let { reason ->
                            Text(stringResource(if (reason == WholeTextReason.COMMENTS) R.string.profile_conflict_whole_comments else R.string.profile_conflict_whole_unparsable))
                        }
                        if (merge.conflicts.isEmpty()) Text(stringResource(R.string.profile_conflict_none))
                        merge.conflicts.forEach { item ->
                            key(item.field) {
                                ConflictCard(item, choices[item.field], revealed[item.field] == true, !locked,
                                    onChoose = { choices[item.field] = it; invalid = false }, onReveal = { revealed[item.field] = revealed[item.field] != true })
                            }
                        }
                        if (merge.conflicts.isNotEmpty()) Text(stringResource(R.string.profile_conflict_progress, merge.conflicts.count { it.field in choices }, merge.conflicts.size),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (invalid) Text(stringResource(R.string.profile_conflict_invalid), color = MaterialTheme.colorScheme.error)
                        failureText()
                        HorizontalDivider()
                        TextButton(onClick = { confirmReload = true }, enabled = !locked) { Text(stringResource(R.string.profile_conflict_reload)) }
                        TextButton(onClick = saveAsNew, enabled = !locked) { Text(stringResource(R.string.profile_conflict_save_new)) }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        when (draft.applyMerge(choices.toMap())) {
                            EditorDraft.MergeOutcome.APPLIED -> model.clearError()
                            EditorDraft.MergeOutcome.INVALID -> invalid = true
                            EditorDraft.MergeOutcome.NOT_APPLICABLE -> Unit
                        }
                    },
                        enabled = !locked && merge.resolved(choices)) { Text(stringResource(R.string.profile_conflict_apply)) }
                },
                dismissButton = { TextButton(onClick = { draft.conflict = null }, enabled = !locked) { Text(stringResource(R.string.cancel)) } })
        }
    }
    if (confirmReload) AlertDialog(onDismissRequest = { confirmReload = false },
        title = { Text(stringResource(R.string.profile_conflict_reload_title)) },
        text = { Text(stringResource(R.string.profile_conflict_reload_message)) },
        confirmButton = { TextButton(onClick = { confirmReload = false; model.reloadLatest() }, enabled = !locked) { Text(stringResource(R.string.profile_conflict_reload_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmReload = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable private fun conflictTitle(field: MergeField): String = when (field) {
    is MergeField.Key -> "${field.section} · ${field.key}"
    is MergeField.Section -> stringResource(R.string.profile_conflict_section, field.section)
    MergeField.Rules -> stringResource(R.string.profile_conflict_rules)
    MergeField.WholeText -> stringResource(R.string.profile_conflict_whole_text)
}

@Composable private fun ConflictCard(conflict: MergeConflict, choice: MergeChoice?, revealed: Boolean, enabled: Boolean,
                                     onChoose: (MergeChoice) -> Unit, onReveal: () -> Unit) {
    val title = conflictTitle(conflict.field)
    val secret = (conflict.mine.orEmpty() + conflict.theirs.orEmpty()).any { it.secret }
    // Unresolved conflicts stay highlighted until a side is chosen.
    val colors = if (choice == null) MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    val notChosen = stringResource(R.string.profile_conflict_not_chosen)
    Surface(shape = RoundedCornerShape(16.dp), color = colors.first, contentColor = colors.second,
        modifier = Modifier.fillMaxWidth().semantics { if (choice == null) stateDescription = notChosen }) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).semantics { heading() })
                if (secret) {
                    val description = stringResource(if (revealed) R.string.profile_conflict_hide else R.string.profile_conflict_show, title)
                    TextButton(onClick = onReveal, enabled = enabled, modifier = Modifier.semantics { contentDescription = description }) {
                        Text(stringResource(if (revealed) R.string.protocol_form_hide else R.string.protocol_form_show))
                    }
                }
            }
            Column(Modifier.selectableGroup()) {
                ConflictOption(stringResource(R.string.profile_conflict_mine), conflict.field, conflict.mine, revealed, choice == MergeChoice.MINE, enabled) { onChoose(MergeChoice.MINE) }
                ConflictOption(stringResource(R.string.profile_conflict_theirs), conflict.field, conflict.theirs, revealed, choice == MergeChoice.THEIRS, enabled) { onChoose(MergeChoice.THEIRS) }
            }
        }
    }
}

@Composable private fun ConflictOption(label: String, field: MergeField, lines: List<MergeLine>?, revealed: Boolean, selected: Boolean,
                                       enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 8.dp).weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium)
            when {
                lines == null -> Text(stringResource(R.string.profile_conflict_absent))
                lines.isEmpty() -> Text(stringResource(if (field == MergeField.Rules) R.string.profile_conflict_no_rules else R.string.profile_conflict_empty))
                else -> lines.forEach { ConflictLine(it, revealed) }
            }
        }
    }
}

@Composable private fun ConflictLine(line: MergeLine, revealed: Boolean) {
    val masked = line.secret && !revealed
    val value = when {
        masked -> line.display(revealed)
        line.imported -> stringResource(R.string.profile_conflict_imported_file)
        else -> line.value.ifEmpty { stringResource(R.string.profile_conflict_empty) }
    }
    val keyed = line.key?.let { "$it = $value" } ?: value
    val text = if (line.disabled) stringResource(R.string.profile_conflict_rule_disabled, keyed) else keyed
    val hidden = stringResource(R.string.profile_conflict_hidden)
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp,
        modifier = if (masked) Modifier.semantics { contentDescription = line.key?.let { "$it: $hidden" } ?: hidden } else Modifier)
}
