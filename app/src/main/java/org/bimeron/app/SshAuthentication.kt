package org.bimeron.app

import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.window.DialogProperties

/** Host serializes feed/reply/cancel on Main and invalidates on stop.
 * Create one controller per SSH session; never reuse across sessions or recreation. At most eight requests
 * are admitted; retired IDs remain protected until disposal. [SshAuthenticationDialog] protects its own dialog
 * window: it sets `SecureFlagPolicy.SecureOn` (FLAG_SECURE on the dialog window only); the host activity
 * window is not made secure.
 * A submitted request stays visible until the next feed replaces or withdraws it: busy after an accepted reply,
 * [replyRejected] after a rejected one. After a rejection only Cancel is offered; otherwise only the next prompt
 * or Core's own per-prompt timeout moves the attempt forward. A rejected cancel keeps the request visible with
 * [cancelFailed]; a second Cancel then only closes the dialog locally.
 */
internal class SshAuthenticationController {
    /** A `host_key` offer carries [hostKey] and no prompts; every other kind carries prompts and no [hostKey]. */
    class Request(val sessionId: String, val requestId: String, val kind: String, val prompts: List<String>,
        val hostKey: HostKeyOffer? = null)
    /** An accept-new host-key offer, already checked by [parse]: every field is consistent with the others. */
    data class HostKeyOffer(val host: String, val keyType: String, val fingerprint: String, val knownHostsLine: String)
    var request by mutableStateOf<Request?>(null); private set
    var answers by mutableStateOf<List<String>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    /** The last reply was not accepted. Shown on the spent request and carried over to the next request only. */
    var replyRejected by mutableStateOf(false); private set
    /** The last cancel was not accepted. Never carried over to another request. */
    var cancelFailed by mutableStateOf(false); private set
    private var spent by mutableStateOf<Request?>(null)
    /** True only while the visible request has not been submitted yet and nothing is in flight. */
    val canSubmit: Boolean get() = request.let { it != null && it !== spent } && !busy && !disposed
    private var disposed = false
    private var sessionId: String? = null
    private val consumed = mutableSetOf<Pair<String, String>>()

    fun feed(pendingJSON: String?) {
        if (disposed) return
        val next = try { parse(pendingJSON) } catch (_: Exception) { null }
        // Ignore stale payloads before clearing a newer request or its answers.
        if (next != null && ((next.sessionId to next.requestId) in consumed ||
                (sessionId != null && next.sessionId != sessionId))) return
        val old = request
        if (next != null && old != null && next.sessionId == old.sessionId && next.requestId == old.requestId) {
            if (next.kind == old.kind && next.prompts == old.prompts && next.hostKey == old.hostKey) return
            // An immutable request changing under the same IDs is invalid.
            consumed.add(old.sessionId to old.requestId)
            clear(); return
        }
        if (old != null) consumed.add(old.sessionId to old.requestId)
        // A reply rejection stays visible as a hint on the next request; a withdrawal clears it.
        val keepRejected = replyRejected && next != null
        clear(); replyRejected = keepRejected
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
        busy = true; replyRejected = false; spent = current
        consumed.add(current.sessionId to current.requestId)
        // A host-key offer has exactly one answer, the fixed acceptance; it never comes from user input.
        val wire = if (current.hostKey != null) HOST_KEY_ACCEPT else answers.joinToString(prefix = "[", postfix = "]") { quote(it) }
        answers = emptyList()
        val accepted = try { reply(current.sessionId, current.requestId, wire) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { false }
        // A completion for a replaced request or a disposed controller is dropped.
        if (request !== current || disposed) return
        // Accepted: stay busy until the next feed. Rejected: show the hint and allow only cancel.
        if (!accepted) { busy = false; replyRejected = true }
    }
    /** [cancel] returns true only when the host accepted the cancellation. After a rejected cancel the next
     * call closes the dialog locally without calling the host again. */
    suspend fun cancel(expected: Request? = request, cancel: suspend (String, String) -> Boolean) {
        val current = expected ?: return
        if (request !== current || busy || disposed) return
        consumed.add(current.sessionId to current.requestId)
        if (cancelFailed) { clear(); return }
        busy = true; replyRejected = false; spent = current; answers = emptyList()
        val accepted = try { cancel(current.sessionId, current.requestId) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { false }
        if (request !== current || disposed) return
        if (accepted) clear() else { busy = false; cancelFailed = true }
    }
    fun dispose() { disposed = true; clear(); consumed.clear() }
    private fun clear() {
        request = null; answers = emptyList(); busy = false; replyRejected = false; cancelFailed = false; spent = null
    }

    companion object {
        const val HOST_KEY_KIND = "host_key"
        /** The only reply that accepts a host-key offer (core ADR 0037). */
        const val HOST_KEY_ACCEPT = "[\"accept\"]"
        /** SSH public-key types Core can offer in accept-new mode (no certificates; Core omits ssh-dss there). */
        private val HOST_KEY_TYPES = setOf(
            "ssh-ed25519", "ssh-rsa", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521",
            "sk-ssh-ed25519@openssh.com", "sk-ecdsa-sha2-nistp256@openssh.com",
        )
        /** Core's dialed-host alphabet; it excludes whitespace, patterns, lists, hashes and markers. */
        private const val HOST_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-_:%[]"
        private val ID = Regex("[0-9a-fA-F]{48}")
        private val FINGERPRINT = Regex("SHA256:[A-Za-z0-9+/]{43}")
        private const val MAX_HOST = 512
        /** Core's `ParseHostKeys` accepts at most 300 characters per known_hosts host. */
        private const val MAX_LINE_HOST = 300
        private const val MAX_LINE = 12288

        internal fun parse(wire: String?): Request? {
            if (wire == null) return null
            require(wire.length <= 16384 && validUnicode(wire) && wire.toByteArray(Charsets.UTF_8).size <= 16384)
            val json = AuthJson(wire).read() ?: return null
            require(json is Map<*, *>)
            if (json["kind"] == HOST_KEY_KIND) return parseHostKey(json)
            require(json.keys == setOf("sessionId", "requestId", "kind", "prompts", "echo"))
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

        /**
         * Strict `host_key` offer: exactly the seven string fields. [HostKeyOffer.knownHostsLine] must be the
         * OpenSSH-normalized line for the lowercased displayed host, key type and fingerprint, so what is persisted is
         * exactly what the user saw.
         */
        private fun parseHostKey(json: Map<*, *>): Request {
            require(json.keys == setOf("sessionId", "requestId", "kind", "host", "keyType", "fingerprint", "knownHostsLine"))
            val values = json.mapValues { it.value as? String ?: error("") }
            val session = values.getValue("sessionId")
            val request = values.getValue("requestId")
            require(session.matches(ID) && request.matches(ID))
            val offer = HostKeyOffer(values.getValue("host"), values.getValue("keyType"),
                values.getValue("fingerprint"), values.getValue("knownHostsLine"))
            require(validHostKey(offer))
            return Request(session, request, HOST_KEY_KIND, emptyList(), offer)
        }

        /** True when every field of [offer] is well formed and consistent with the others. Never throws. */
        internal fun validHostKey(offer: HostKeyOffer): Boolean = runCatching {
            val (name, port) = splitHost(offer.host) ?: return false
            if (offer.keyType !in HOST_KEY_TYPES || !offer.fingerprint.matches(FINGERPRINT)) return false
            if (!validKnownHostsLine(offer.knownHostsLine)) return false
            val fields = offer.knownHostsLine.split(' ')
            // The dialed host:port, lowercased as Core does, then OpenSSH-normalized (bare for port 22).
            val dialed = name.lowercase(java.util.Locale.ROOT)
            val expectedHost = if (port == 22) dialed else "[$dialed]:$port"
            if (fields[0] != expectedHost || fields[1] != offer.keyType) return false
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(java.util.Base64.getDecoder().decode(fields[2]))
            offer.fingerprint == "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(digest)
        }.getOrDefault(false)

        /**
         * A single plain line Core's `ParseHostKeys` accepts and an offer could produce: `host type base64`,
         * single spaces, printable ASCII, a literal host (`name`, or `[name]:port` for a port other than 22),
         * a known key type, and canonical base64 of a key blob of that type. Never throws.
         */
        internal fun validKnownHostsLine(line: String): Boolean = runCatching {
            if (line.length > MAX_LINE || line.any { it !in '!'..'~' && it != ' ' }) return false
            val fields = line.split(' ')
            if (fields.size != 3 || fields.any { it.isEmpty() }) return false
            val host = fields[0]
            // Stored hosts are lowercase (Core lowercases before normalizing), so no case variant can coexist.
            if (host.length > MAX_LINE_HOST || host.any { it !in HOST_CHARS || it in 'A'..'Z' }) return false
            if (host.startsWith('[')) {
                val (name, port) = splitHost(host) ?: return false
                if (port == 22 || host != "[$name]:$port") return false
            } else if (host.any { it == '[' || it == ']' }) return false
            if (fields[1] !in HOST_KEY_TYPES) return false
            val blob = java.util.Base64.getDecoder().decode(fields[2])
            java.util.Base64.getEncoder().encodeToString(blob) == fields[2] && blobType(blob) == fields[1]
        }.getOrDefault(false)

        /** Splits Core's dialed `host:port` (IPv6 bracketed). Null when the host is not a literal Core could emit. */
        private fun splitHost(host: String): Pair<String, Int>? {
            if (host.isEmpty() || host.length > MAX_HOST || host.any { it !in HOST_CHARS }) return null
            val (name, port) = if (host.startsWith('[')) {
                val close = host.indexOf("]:")
                if (close < 0) return null
                host.substring(1, close) to host.substring(close + 2)
            } else {
                val colon = host.lastIndexOf(':')
                if (colon < 0) return null
                host.substring(0, colon) to host.substring(colon + 1)
            }
            if (name.isEmpty() || name.any { it == '[' || it == ']' } || (!host.startsWith('[') && ':' in name)) return null
            val number = port.toIntOrNull() ?: return null
            if (number !in 1..65535 || number.toString() != port) return null
            return name to number
        }

        /** The type string that starts an SSH wire-format public key, or null when the blob is malformed. */
        private fun blobType(blob: ByteArray): String? {
            if (blob.size < 4) return null
            val length = java.nio.ByteBuffer.wrap(blob, 0, 4).int
            if (length !in 1..128 || blob.size <= 4 + length) return null
            return String(blob, 4, length, Charsets.US_ASCII)
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
    // Dismissing (back, outside tap) cancels the request. It is ignored only while a reply or cancel is in flight
    // or an accepted reply waits for the next feed; a second dismiss after a rejected cancel closes locally.
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
            title = { Text(stringResource(if (current.hostKey != null) R.string.ssh_host_key_title else R.string.ssh_auth_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                val hint = when {
                    controller.cancelFailed -> R.string.ssh_auth_cancel_failed
                    controller.replyRejected -> R.string.ssh_auth_reply_failed
                    else -> null
                }
                if (hint != null) Text(stringResource(hint), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                current.hostKey?.let { HostKeyDetails(it) }
                current.prompts.forEachIndexed { index, prompt ->
                    SecretInput(SecretKeyboard.Masked) { keyboardOptions ->
                        OutlinedTextField(value = controller.answers.getOrElse(index) { "" },
                            onValueChange = { controller.answer(index, it) }, label = { Text(prompt) },
                            enabled = controller.canSubmit, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = keyboardOptions)
                    }
                }
            } },
            confirmButton = { TextButton(enabled = controller.canSubmit, onClick = { scope.launch { controller.submit(current, reply) } }) { Text(stringResource(if (current.hostKey != null) R.string.ssh_host_key_trust else R.string.ssh_reply)) } },
            dismissButton = { TextButton(enabled = !controller.busy, onClick = dismiss) { Text(stringResource(R.string.ssh_cancel)) } })
    }
}

/** Body of a host-key offer: host, key type and the full fingerprint (never truncated), then the advice. */
@Composable
private fun HostKeyDetails(offer: SshAuthenticationController.HostKeyOffer) {
    Text(stringResource(R.string.ssh_host_key_server, offer.host))
    Text(stringResource(R.string.ssh_host_key_type, offer.keyType))
    Text(stringResource(R.string.ssh_host_key_fingerprint_label))
    Text(offer.fingerprint, fontFamily = FontFamily.Monospace)
    Text(stringResource(R.string.ssh_host_key_verify_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
}
