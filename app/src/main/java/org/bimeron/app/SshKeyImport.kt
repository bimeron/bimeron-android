package org.bimeron.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Identity is an opaque draft instance/generation, replaced whenever the draft changes ownership. */
internal class SshKeyImportController {
    class Ticket internal constructor(val kind: SshProfileResources.Kind, internal val generation: Long)
    private var identity: Any? = null
    private var enabled = false
    private var generation = 0L
    private var disposed = false
    private var pending: Ticket? = null
    val busy get() = pending != null
    fun update(draftIdentity: Any, enabled: Boolean) {
        if (identity !== draftIdentity || this.enabled != enabled) {
            generation++; identity = draftIdentity; this.enabled = enabled
        }
    }
    fun begin(kind: SshProfileResources.Kind): Ticket? {
        if (disposed || !enabled || pending != null) return null
        return Ticket(kind, generation).also { pending = it }
    }
    fun finish(ticket: Ticket): Boolean {
        if (pending !== ticket) return false
        pending = null
        return !disposed && enabled && ticket.generation == generation
    }
    fun dispose() { disposed = true; generation++ }
}

/** Host owns accepted tokens: prepare before save; on terminal unsaved discard abort token.prepared
 * if present, otherwise discard. After save/delete use the resource store's lifecycle methods.
 * Construct resources with canonical filesDir/ssh-resources as its root.
 * Return true from onStaged only after taking ownership. Never persist provider names or URIs.
 */
@Composable
internal fun SshKeyImportButtons(
    draftIdentity: Any,
    enabled: Boolean,
    resources: SshProfileResources,
    onStaged: (SshProfileResources.Kind, SshProfileResources.StagedToken) -> Boolean,
    onFailure: () -> Unit,
    onCancelled: () -> Unit,
) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val controller = remember(resources) { SshKeyImportController() }
    val stagedCallback by rememberUpdatedState(onStaged)
    val failureCallback by rememberUpdatedState(onFailure)
    val cancelCallback by rememberUpdatedState(onCancelled)
    var ticket by remember(controller) { mutableStateOf<SshKeyImportController.Ticket?>(null) }
    // Invalidate during composition, before any queued picker result can be delivered.
    controller.update(draftIdentity, enabled)
    DisposableEffect(controller) { onDispose { controller.dispose() } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val picked = ticket ?: return@rememberLauncherForActivityResult
        if (uri == null) {
            ticket = null
            if (controller.finish(picked)) cancelCallback()
        } else scope.launch {
            var token: SshProfileResources.StagedToken? = null
            var transferred = false
            // NonCancellable keeps ownership of a token produced after scope disposal.
            withContext(NonCancellable) {
                var deliverable = false
                try {
                    token = withContext(Dispatchers.IO) {
                        resources.stage(picked.kind, resolver.openInputStream(uri) ?: error(""))
                    }
                    deliverable = controller.finish(picked)
                    if (deliverable) transferred = stagedCallback(picked.kind, token!!)
                } catch (_: Exception) {
                    if (deliverable || controller.finish(picked)) failureCallback()
                } finally {
                    if (!transferred) token?.let { abandoned ->
                        withContext(Dispatchers.IO) {
                            try {
                                abandoned.prepared?.let(resources::abort) ?: resources.discard(abandoned)
                            } catch (_: Exception) { /* Private orphan retained if cleanup fails. */ }
                        }
                    }
                    ticket = null
                }
            }
        }
    }
    Column {
        listOf(SshProfileResources.Kind.IDENTITY, SshProfileResources.Kind.KNOWN_HOSTS).forEach { kind ->
            TextButton(enabled = enabled && ticket == null, onClick = {
                controller.begin(kind)?.let { started ->
                    ticket = started
                    try { launcher.launch(arrayOf("*/*")) }
                    catch (_: Exception) { ticket = null; if (controller.finish(started)) failureCallback() }
                }
            }) {
                Text(stringResource(if (kind == SshProfileResources.Kind.IDENTITY)
                    R.string.ssh_import_identity else R.string.ssh_import_known_hosts))
            }
        }
    }
}
