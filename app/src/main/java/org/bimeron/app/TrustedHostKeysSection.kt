package org.bimeron.app

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch
import java.util.UUID

/** True for a document Core may run as SSH: `Type = ssh`, an `SSH` source, or a generic `ssh://` key. */
internal fun sshDocument(doc: ConfigDocument): Boolean {
    if (doc.value("AccessKey", "Type").trim().equals("ssh", ignoreCase = true)) return true
    val source = doc.source() ?: return false
    return source.first == "SSH" || (source.first == "Key" && source.second.trim().startsWith("ssh://", ignoreCase = true))
}

/**
 * "Trusted server keys: N — Forget" for a saved SSH profile. Keys accepted through the accept-new dialog
 * belong to the stored profile [profileId], not to the draft, so forgetting acts immediately (after a
 * confirmation) and is not part of Save. Nothing is shown for an unsaved profile. An unreadable store is
 * shown as such with Forget enabled, since Forget is how the user recovers from it. The count is read off
 * Main (the store calls switch to IO) when shown, on every resume and whenever [refresh] changes (for
 * example the VPN state, as a session may have stored a key).
 */
@Composable
internal fun TrustedHostKeysSection(
    profileId: String,
    count: suspend (UUID) -> Int,
    forget: suspend (UUID) -> Unit,
    enabled: Boolean,
    onFailure: () -> Unit,
    refresh: Any? = null,
) {
    val id = remember(profileId) { runCatching { UUID.fromString(profileId) }.getOrNull()?.takeIf { it.toString() == profileId } } ?: return
    // null: not loaded yet; -1: unreadable.
    var stored by remember(id) { mutableStateOf<Int?>(null) }
    var confirm by remember(id) { mutableStateOf(false) }
    var resumes by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    suspend fun reload() { stored = try { count(id) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { UNREADABLE } }
    LifecycleResumeEffect(Unit) { resumes++; onPauseOrDispose {} }
    LaunchedEffect(id, refresh, resumes) { reload() }
    val current = stored ?: return
    val description = stringResource(R.string.ssh_trusted_host_keys_forget_description)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (current == UNREADABLE) stringResource(R.string.ssh_trusted_host_keys_unreadable)
            else stringResource(R.string.ssh_trusted_host_keys_count, current), modifier = Modifier.weight(1f))
        TextButton(onClick = { confirm = true }, enabled = enabled && current != 0,
            modifier = Modifier.semantics { contentDescription = description }) {
            Text(stringResource(R.string.ssh_trusted_host_keys_forget))
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.ssh_trusted_host_keys_forget_title)) },
        text = { Text(stringResource(R.string.ssh_trusted_host_keys_forget_message)) },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                scope.launch {
                    try { forget(id) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { onFailure() }
                    reload()
                }
            }) { Text(stringResource(R.string.ssh_trusted_host_keys_forget)) }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
}

private const val UNREADABLE = -1
