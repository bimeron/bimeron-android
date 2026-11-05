package org.bimeron.app

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun LogJournalPage(logs: List<LogEntry>, model: DiagnosticsModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    fun toast(id: Int) = Toast.makeText(context, id, Toast.LENGTH_SHORT).show()
    val document = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) { working = false; return@rememberLauncherForActivityResult }
        scope.launch {
            try { model.save(uri, context.contentResolver); toast(R.string.diag_saved) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { toast(R.string.diag_save_failed) }
            finally { working = false }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.width(minOf(maxWidth, 900.dp)).fillMaxHeight().padding(horizontal = 20.dp), contentPadding = PaddingValues(top = 20.dp, bottom = 24.dp)) {
            item { PageHeader(stringResource(R.string.journal)) }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !working, onClick = {
                        working = true
                        scope.launch {
                            try { context.startActivity(model.shareIntent()) }
                            catch (e: CancellationException) { throw e }
                            catch (_: ActivityNotFoundException) { toast(R.string.diag_share_failed) }
                            catch (_: Exception) { toast(R.string.diag_share_failed) }
                            finally { working = false }
                        }
                    }) { Icon(Icons.Default.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.diag_share)) }
                    OutlinedButton(enabled = !working, onClick = {
                        working = true
                        try { document.launch(model.fileName()) }
                        catch (_: Exception) { working = false; toast(R.string.diag_save_failed) }
                    }) { Icon(Icons.Default.Save, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.diag_save)) }
                    TextButton(enabled = logs.isNotEmpty() && !working, onClick = { confirmClear = true }) {
                        Icon(Icons.Default.DeleteSweep, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.diag_clear))
                    }
                }
                Text(stringResource(R.string.diag_privacy_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = MUTED_TEXT_ALPHA), modifier = Modifier.padding(bottom = 14.dp))
            }
            if (logs.isEmpty()) item { CardBlock { Text(stringResource(R.string.log_empty)) } }
            else items(logs) { entry ->
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).semantics(mergeDescendants = true) {}) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = MUTED_TEXT_ALPHA))
                            levelLabel(entry.level)?.let { label ->
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(label), style = MaterialTheme.typography.labelSmall,
                                    color = if (entry.level == LogLevel.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(Modifier.height(5.dp)); Text(entry.message, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.diag_clear_confirm)) },
        confirmButton = { TextButton(onClick = { confirmClear = false; model.clear() }) { Text(stringResource(R.string.diag_clear)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun levelLabel(level: LogLevel): Int? = when (level) {
    LogLevel.ERROR -> R.string.diag_level_tag_error
    LogLevel.WARNING -> R.string.diag_level_tag_warning
    LogLevel.DEBUG -> R.string.diag_level_tag_debug
    LogLevel.INFO -> null
}

@Composable
internal fun LogSettingsCard(model: DiagnosticsModel = viewModel()) {
    val settings by model.settings.collectAsStateWithLifecycle()
    CardBlock {
        Text(stringResource(R.string.diag_level_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        Column(Modifier.selectableGroup()) {
            listOf(LogLevel.WARNING to R.string.diag_level_warning, LogLevel.INFO to R.string.diag_level_info,
                LogLevel.DEBUG to R.string.diag_level_debug).forEach { (level, label) ->
                OptionRow(stringResource(label), settings.level == level) { model.update(settings.copy(level = level)) }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(stringResource(R.string.diag_limit_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        Column(Modifier.selectableGroup()) {
            listOf(500 to R.string.diag_limit_500, 1000 to R.string.diag_limit_1000, 5000 to R.string.diag_limit_5000).forEach { (limit, label) ->
                OptionRow(stringResource(label), settings.maxEntries == limit) { model.update(settings.copy(maxEntries = limit)) }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Row(Modifier.fillMaxWidth().toggleable(settings.keepPrevious, role = Role.Switch) { model.update(settings.copy(keepPrevious = it)) }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.diag_keep_previous))
                Text(stringResource(R.string.diag_keep_previous_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = MUTED_TEXT_ALPHA))
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = settings.keepPrevious, onCheckedChange = null, modifier = Modifier.minimumInteractiveComponentSize())
        }
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.minimumInteractiveComponentSize()); Spacer(Modifier.width(8.dp)); Text(label)
    }
}
