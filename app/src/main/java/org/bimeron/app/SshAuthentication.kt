package org.bimeron.app

import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.window.DialogProperties

/** Host serializes feed/reply/cancel on Main and invalidates on stop.
 * Create one controller per SSH session; never reuse across sessions or recreation. At most eight requests
 * are admitted; retired IDs remain protected until disposal. Host must protect the window with FLAG_SECURE.
 * A submitted request stays visible (busy on success, failed on rejection) until the next feed replaces it.
 */
internal class SshAuthenticationController {
    class Request(val sessionId: String, val requestId: String, val kind: String, val prompts: List<String>)
    var request by mutableStateOf<Request?>(null); private set
    var answers by mutableStateOf<List<String>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var failed by mutableStateOf(false); private set
    private var spent by mutableStateOf<Request?>(null)
    /** True only while the visible request has not been submitted yet and nothing is in flight. */
    val canSubmit: Boolean get() = request.let { it != null && it !== spent } && !busy && !disposed
    private var disposed = false
    private var sessionId: String? = null
    private val consumed = mutableSetOf<Pair<String, String>>()
    private var generation = 0

    fun feed(pendingJSON: String?) {
        if (disposed) return
        val next = try { parse(pendingJSON) } catch (_: Exception) { null }
        // Ignore stale payloads before clearing a newer request or its answers.
        if (next != null && ((next.sessionId to next.requestId) in consumed ||
                (sessionId != null && next.sessionId != sessionId))) return
        val old = request
        if (next != null && old != null && next.sessionId == old.sessionId && next.requestId == old.requestId) {
            if (next.kind == old.kind && next.prompts == old.prompts) return
            // An immutable request changing under the same IDs is invalid.
            consumed.add(old.sessionId to old.requestId)
            clear(); failed = true; return
        }
        if (old != null) consumed.add(old.sessionId to old.requestId)
        // A rejection stays visible as a hint on the next request; a withdrawal clears it.
        val keepFailed = failed && next != null
        clear(); failed = keepFailed
        if (next != null && consumed.size < 8) {
            sessionId = next.sessionId
            request = next
            answers = List(next.prompts.size) { "" }
        }
    }
    fun answer(index: Int, value: String) {
        if (!canSubmit || index !in answers.indices) return
        val updated = answers.toMutableList().also { it[index] = value }
        if (updated.all { validUnicode(it) && it.toByteArray(Charsets.UTF_8).size <= 4096 } &&
            updated.sumOf { it.toByteArray(Charsets.UTF_8).size } <= 16384) answers = updated
    }
    /** [reply] returns true only when the host accepted the answers. Anything else marks the request failed. */
    suspend fun submit(expected: Request? = request, reply: suspend (String, String, String) -> Boolean) {
        val current = expected ?: return
        if (request !== current || !canSubmit) return
        busy = true; failed = false; spent = current
        consumed.add(current.sessionId to current.requestId)
        val wire = answers.joinToString(prefix = "[", postfix = "]") { quote(it) }
        answers = emptyList()
        val accepted = try { reply(current.sessionId, current.requestId, wire) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { false }
        // A completion for a replaced request or a disposed controller is dropped.
        if (request !== current || disposed) return
        // Accepted: stay busy until the next feed. Rejected: show the hint and allow only cancel.
        if (!accepted) { busy = false; failed = true }
    }
    /** [cancel] returns true only when the host accepted the cancellation. */
    suspend fun cancel(expected: Request? = request, cancel: suspend (String, String) -> Boolean) {
        val current = expected ?: return
        if (request !== current || busy || disposed) return
        consumed.add(current.sessionId to current.requestId)
        clear()
        val started = generation
        val accepted = try { cancel(current.sessionId, current.requestId) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { false }
        if (!accepted && generation == started && !disposed) failed = true
    }
    fun dispose() { disposed = true; clear(); consumed.clear() }
    private fun clear() { generation++; request = null; answers = emptyList(); busy = false; failed = false; spent = null }

    companion object {
        internal fun parse(wire: String?): Request? {
            if (wire == null) return null
            require(wire.length <= 16384 && validUnicode(wire) && wire.toByteArray(Charsets.UTF_8).size <= 16384)
            val json = AuthJson(wire).read() ?: return null
            require(json is Map<*, *> && json.keys == setOf("sessionId", "requestId", "kind", "prompts", "echo"))
            val session = json["sessionId"] as? String ?: error("")
            val request = json["requestId"] as? String ?: error("")
            require(listOf(session, request).all { it.matches(Regex("[0-9a-fA-F]{48}")) })
            val kind = json["kind"] as? String ?: error("")
            require(kind in setOf("password", "key_passphrase", "keyboard_interactive"))
            val prompts = json["prompts"] as? List<*> ?: error("")
            require(prompts.size in 1..8)
            val text = prompts.map { it as? String ?: error("") }
            require(text.all { prompt -> validUnicode(prompt) && prompt.toByteArray(Charsets.UTF_8).size <= 1024 &&
                prompt.codePoints().noneMatch { Character.isISOControl(it) || Character.getType(it) in setOf(Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt()) } })
            require(text.sumOf { it.toByteArray(Charsets.UTF_8).size } <= 8192)
            val echo = json["echo"] as? List<*> ?: error("")
            require(echo.size == text.size && echo.all { it is Boolean })
            return Request(session, request, kind, text)
        }
        private fun validUnicode(s: String): Boolean {
            var i = 0
            while (i < s.length) {
                val c = s[i++]
                if (c.isHighSurrogate()) { if (i == s.length || !s[i++].isLowSurrogate()) return false }
                else if (c.isLowSurrogate()) return false
            }
            return true
        }
        private fun quote(s: String) = buildString {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }
    }
}

/** Deliberately limited strict JSON grammar: pending auth has no numbers or nested containers. */
private class AuthJson(private val source: String) {
    private var at = 0
    fun read(): Any? { val value = value(0); whitespace(); require(at == source.length); return value }
    private fun whitespace() { while (at < source.length && source[at] in " \r\n\t") at++ }
    private fun take(c: Char): Boolean { whitespace(); return if (at < source.length && source[at] == c) { at++; true } else false }
    private fun value(depth: Int): Any? {
        require(depth <= 2); whitespace(); require(at < source.length)
        return when (source[at]) {
            '"' -> string()
            '{' -> { at++; val map = linkedMapOf<String, Any?>(); if (!take('}')) { do {
                whitespace(); val key = string(); require(!map.containsKey(key) && take(':')); map[key] = value(depth + 1)
            } while (take(',')); require(take('}')) }; map }
            '[' -> { at++; val list = mutableListOf<Any?>(); if (!take(']')) { do { require(list.size < 8); list.add(value(depth + 1)) } while (take(',')); require(take(']')) }; list }
            else -> when { literal("null") -> null; literal("true") -> true; literal("false") -> false; else -> error("") }
        }
    }
    private fun literal(s: String): Boolean = if (source.startsWith(s, at)) { at += s.length; true } else false
    private fun string(): String {
        require(at < source.length && source[at++] == '"')
        return buildString {
            while (true) {
                require(at < source.length)
                val c = source[at++]
                if (c == '"') break
                require(c >= ' ')
                if (c != '\\') append(c) else {
                    require(at < source.length)
                    when (val escaped = source[at++]) {
                        '"', '\\', '/' -> append(escaped)
                        'b' -> append('\b'); 'f' -> append('\u000c'); 'n' -> append('\n'); 'r' -> append('\r'); 't' -> append('\t')
                        'u' -> { require(at + 4 <= source.length); val hex = source.substring(at, at + 4); require(hex.all { it in "0123456789abcdefABCDEF" }); append(hex.toInt(16).toChar()); at += 4 }
                        else -> error("")
                    }
                }
            }
        }
    }
}

/**
 * Shows the SSH interactive-auth dialog for the attempt identified by [key] (for example token to sessionId).
 * A new [key] gets a fresh controller; the old one is disposed and its in-flight work is cancelled, so a late
 * result can only touch the disposed controller. [wire] is the exact pendingAuth payload; null or "null" hides
 * the dialog. [reply] and [cancel] return true only when the host accepted the call. Nothing is saved to a Bundle.
 */
@Composable
internal fun SshAuthenticationHost(
    key: Any?,
    wire: String?,
    reply: suspend (sessionId: String, requestId: String, answersWire: String) -> Boolean,
    cancel: suspend (sessionId: String, requestId: String) -> Boolean,
) {
    if (key == null) return
    androidx.compose.runtime.key(key) {
        val controller = remember { SshAuthenticationController() }
        LaunchedEffect(wire) { controller.feed(wire) }
        SshAuthenticationDialog(controller, reply, cancel)
    }
}

@Suppress("DEPRECATION")
@Composable
internal fun SshAuthenticationDialog(
    controller: SshAuthenticationController,
    reply: suspend (String, String, String) -> Boolean,
    cancel: suspend (String, String) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    DisposableEffect(controller) { onDispose { controller.dispose() } }
    val current = controller.request ?: return
    // Dismissing (back, outside tap) cancels the request so the dialog never blocks the app.
    val dismiss = { scope.launch { controller.cancel(current, cancel) }; Unit }
    // Block clipboard reads/writes and selection copy for every answer, including echo=true.
    val clipboard = remember { object : ClipboardManager {
        override fun setText(annotatedString: AnnotatedString) {}
        override fun getText(): AnnotatedString? = null
        override fun hasText() = false
    } }
    CompositionLocalProvider(LocalClipboardManager provides clipboard) {
        AlertDialog(onDismissRequest = dismiss,
            properties = DialogProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn),
            title = { Text(stringResource(R.string.ssh_auth_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                if (controller.failed) Text(stringResource(R.string.ssh_auth_reply_failed),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                current.prompts.forEachIndexed { index, prompt ->
                    OutlinedTextField(value = controller.answers.getOrElse(index) { "" },
                        onValueChange = { controller.answer(index, it) }, label = { Text(prompt) },
                        enabled = controller.canSubmit, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
                }
            } },
            confirmButton = { TextButton(enabled = controller.canSubmit, onClick = { scope.launch { controller.submit(current, reply) } }) { Text(stringResource(R.string.ssh_reply)) } },
            dismissButton = { TextButton(enabled = !controller.busy, onClick = dismiss) { Text(stringResource(R.string.ssh_cancel)) } })
    }
}
