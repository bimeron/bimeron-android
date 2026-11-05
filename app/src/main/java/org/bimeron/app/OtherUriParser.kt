package org.bimeron.app

import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Raw slices are retained so an edit never rewrites an untouched component. */
internal class OtherUriValue(
    val original: String,
    val fields: Map<String, String>,
    val valid: Boolean,
    private val replace: (String, String) -> String,
) {
    fun edit(field: String, value: String): String =
        if (fields[field] == value || field !in fields) original else replace(field, value)
    override fun toString() = "OtherUriValue(redacted)"
}

internal object OtherUriParser {
    val schemes = mapOf("hy2" to "Hysteria2", "hysteria2" to "Hysteria2", "anytls" to "AnyTLS", "tuic" to "TUIC", "naive+quic" to "Naive", "vmess" to "VMess")
    private val uuid = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
    private fun clean(s: String) = s.none { it.isISOControl() }
    private fun name(s: String) = s.isNotEmpty() && s.none { it.isWhitespace() || it.isISOControl() || it in "/\\@?#" }
    private fun host(s: String): Boolean = name(s) && '%' !in s && '[' !in s && ']' !in s && if (':' in s) {
        // URI validates IPv6 literals locally, without DNS resolution.
        runCatching { URI("https://[$s]").host != null }.getOrDefault(false)
    } else '[' !in s && ']' !in s
    private fun port(s: String) = s.matches(Regex("[0-9]+")) && s.toIntOrNull() in 1..65535
    private fun decode(s: String, query: Boolean = false): String {
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        val source = if (query) s.replace('+', ' ') else s
        while (i < source.length) {
            if (source[i] == '%') {
                require(i + 2 < source.length)
                bytes.write(source.substring(i + 1, i + 3).toInt(16)); i += 3
            } else {
                val end = source.indexOf('%', i).let { if (it < 0) source.length else it }
                bytes.write(source.substring(i, end).toByteArray(Charsets.UTF_8)); i = end
            }
        }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    }
    private fun encode(s: String): String {
        require(Charsets.UTF_8.newEncoder().canEncode(s))
        return URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("*", "%2A")
    }
    fun parse(raw: String): OtherUriValue? = try {
        require(raw.length <= 131072 && clean(raw) && raw.none(Char::isWhitespace) && Charsets.UTF_8.newEncoder().canEncode(raw))
        val scheme = raw.substringBefore("://")
        require(scheme in schemes)
        if (scheme == "vmess") vmess(raw) else uri(raw, scheme)
    } catch (_: Exception) { null }

    private fun uri(raw: String, scheme: String): OtherUriValue {
        val u = URI(raw)
        require(!u.isOpaque && u.rawAuthority != null)
        val authority = u.rawAuthority
        require(authority.count { it == '@' } <= 1)
        val user = authority.substringBefore('@').takeIf { '@' in authority }
        val endpoint = authority.substringAfter('@')
        val h: String
        val p: String?
        if (endpoint.startsWith('[')) {
            val end = endpoint.indexOf(']'); require(end > 0)
            h = endpoint.substring(1, end)
            val tail = endpoint.substring(end + 1); require(tail.isEmpty() || tail.startsWith(':'))
            p = if (tail.isEmpty()) null else tail.drop(1)
        } else {
            require(endpoint.count { it == ':' } <= 1)
            h = endpoint.substringBefore(':'); p = endpoint.substringAfter(':', "").takeIf { ':' in endpoint }
        }
        require('%' !in h && (u.rawPath.isNullOrEmpty() || scheme == "anytls" && u.rawPath == "/"))
        val query = u.rawQuery?.split('&') ?: emptyList()
        val q = linkedMapOf<String, String>()
        val allowed = when (scheme) { "hy2", "hysteria2" -> setOf("sni", "auth", "insecure", "alpn", "fp"); "tuic" -> setOf("sni", "alpn", "udp_relay_mode"); else -> setOf("sni", "insecure") }
        query.forEach { item ->
            require(item.isNotEmpty() && ';' !in item)
            val k = decode(item.substringBefore('='), true)
            require(k in allowed && k !in q)
            q[k] = decode(item.substringAfter('=', ""), true)
        }
        val pair = scheme == "tuic" || scheme == "naive+quic"
        require(if (pair) user != null && ':' in user else user == null || ':' !in user)
        require(if (scheme == "hy2" || scheme == "hysteria2") (user != null) xor ("auth" in q) else user != null)
        val fields = linkedMapOf("host" to h, "port" to (p ?: "443"))
        if (pair) {
            fields["username"] = decode(user!!.substringBefore(':'))
            fields["password"] = decode(user.substringAfter(':'))
        } else if (user != null) fields["password"] = decode(user)
        q.forEach { (k, v) -> fields[k] = v }
        val optional = when (scheme) {
            "hy2", "hysteria2" -> mapOf("sni" to h, "alpn" to "", "fp" to "")
            "tuic" -> mapOf("sni" to h, "alpn" to "h3")
            else -> mapOf("sni" to h)
        }
        optional.forEach { (key, default) -> fields.putIfAbsent(key, default) }
        val password = fields["password"] ?: q["auth"].orEmpty()
        var valid = host(h) && port(fields.getValue("port")) && password.isNotEmpty() && clean(password) && name(q["sni"] ?: h)
        if (scheme in setOf("hy2", "hysteria2", "tuic") && p == null) valid = false
        if (pair) valid = valid && if (scheme == "tuic") uuid.matches(fields.getValue("username")) else fields.getValue("username").let { it.isNotEmpty() && ':' !in it && clean(it) }
        valid = valid && (q["insecure"] == null || q["insecure"] == "0")
        if (scheme == "tuic") valid = valid && (q["udp_relay_mode"] == null || q["udp_relay_mode"] == "quic") && (q["alpn"] ?: "h3").let { it.isNotEmpty() && it.none { c -> c.isWhitespace() || c.isISOControl() || c == ',' } }
        if (scheme in setOf("hy2", "hysteria2")) valid = valid && q["alpn"].orEmpty() in setOf("", "h3") && (q["fp"] == null || q["fp"] in setOf("firefox", "firefox-116", "firefox-116b", "firefox-116c", "chrome", "chrome-115", "chrome-146"))
        return OtherUriValue(raw, fields, valid) { key, value ->
            if (key == "host") require(host(value))
            if (key == "port") require(port(value))
            val newUser = when (key) {
                "username" -> encode(value) + ":" + user!!.substringAfter(':')
                "password" -> if (pair) user!!.substringBefore(':') + ":" + encode(value) else encode(value)
                else -> user
            }
            val newHost = if (key == "host") { if (':' in value) "[$value]" else value } else endpoint.substringBeforeLast(':').takeIf { p != null } ?: endpoint
            val newPort = if (key == "port") value else p
            val newQuery = when {
                key in q -> query.map { if (decode(it.substringBefore('='), true) == key) it.substringBefore('=') + "=" + encode(value) else it }.joinToString("&")
                key in optional -> (query + (key + "=" + encode(value))).joinToString("&")
                else -> u.rawQuery
            }
            "$scheme://" + (newUser?.let { "$it@" } ?: "") + newHost + (newPort?.let { ":$it" } ?: "") + u.rawPath.orEmpty() + (newQuery?.let { "?$it" } ?: "") + (u.rawFragment?.let { "#$it" } ?: "")
        }
    }

    private val jsonString = Regex("\"(?:[^\"\\\\\\x00-\\x1f]|\\\\(?:[\"\\\\/bfnrt]|u[0-9a-fA-F]{4}))*\"")
    private fun unquote(token: String): String {
        val out = StringBuilder()
        var i = 1
        while (i < token.lastIndex) {
            val c = token[i++]
            if (c != '\\') out.append(c) else {
                when (val escape = token[i++]) {
                    'u' -> { out.append(token.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    'b' -> out.append('\b'); 'f' -> out.append('\u000c'); 'n' -> out.append('\n')
                    'r' -> out.append('\r'); 't' -> out.append('\t'); else -> out.append(escape)
                }
            }
        }
        val value = out.toString()
        // Reject unpaired surrogates rather than silently substituting replacement bytes.
        Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value))
        return value
    }
    private fun quote(value: String): String = buildString {
        require(Charsets.UTF_8.newEncoder().canEncode(value))
        append('"')
        value.forEach { c -> when (c) {
            '"', '\\' -> { append('\\'); append(c) }
            else -> if (c.code < 32) append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
        } }
        append('"')
    }
    private fun vmess(raw: String): OtherUriValue {
        val payload = raw.removePrefix("vmess://")
        require(payload.isNotEmpty() && payload.none { it in "?#" })
        val url = '-' in payload || '_' in payload
        val decoder = if (url) Base64.getUrlDecoder() else Base64.getDecoder()
        val bytes = decoder.decode(payload)
        require(bytes.size <= 65536)
        val json = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val fields = linkedMapOf<String, String>()
        val spans = mutableMapOf<String, IntRange>()
        val numbers = mutableSetOf<String>()
        var pos = 0
        fun whitespace() { while (pos < json.length && json[pos] in " \t\r\n") pos++ }
        fun consume(c: Char) { whitespace(); require(pos < json.length && json[pos++] == c) }
        fun stringToken(): String {
            whitespace()
            val token = jsonString.matchAt(json, pos)?.value ?: error("Invalid JSON")
            pos += token.length
            return token
        }
        consume('{'); whitespace()
        if (pos < json.length && json[pos] != '}') while (true) {
            val key = unquote(stringToken()); require(key !in fields)
            consume(':'); whitespace()
            val start = pos
            val value = if (pos < json.length && json[pos] == '"') unquote(stringToken()) else {
                require(key in setOf("v", "port", "aid"))
                val token = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?").matchAt(json, pos)?.value ?: error("Invalid JSON")
                pos += token.length; numbers += key; token
            }
            fields[key] = value; spans[key] = start until pos
            whitespace(); require(pos < json.length)
            if (json[pos] != ',') break
            pos++
        }
        consume('}'); whitespace(); require(pos == json.length)
        require(fields.keys.all { it in setOf("v", "ps", "add", "port", "id", "aid", "scy", "net", "type", "tls", "sni", "alpn", "fp", "host", "path", "mode", "pbk", "sid", "spx") })
        // shortcut: only TCP TLS is editable; add transports after lossless native fixtures exist.
        require((fields["net"] ?: "tcp") == "tcp" && fields["tls"] == "tls")
        require(listOf("host", "path", "mode", "pbk", "sid", "spx").all { fields[it].isNullOrEmpty() })
        require(fields.filterKeys { it in setOf("net", "scy", "sni") }.values.none { it.isEmpty() })
        require(fields.keys.containsAll(setOf("v", "add", "port", "id", "tls")))
        require(fields["v"] == "2" && fields["aid"].orEmpty() in setOf("", "0") && fields["type"].orEmpty() in setOf("", "none"))
        val valid = fields["v"] == "2" && fields["aid"].orEmpty() in setOf("", "0") && fields["type"].orEmpty() in setOf("", "none") &&
            host(fields["add"].orEmpty()) && port(fields["port"].orEmpty()) && uuid.matches(fields["id"].orEmpty()) &&
            (fields["scy"] ?: "auto") in setOf("auto", "aes-128-gcm", "chacha20-poly1305") && name(fields["sni"] ?: fields["add"].orEmpty()) &&
            fields["alpn"].orEmpty().let { it.isEmpty() || it.split(',').all { token -> token.isNotEmpty() && token.none { c -> c.isWhitespace() || c.isISOControl() } } } &&
            fields["fp"].orEmpty() in setOf("", "chrome", "firefox", "safari", "ios", "android", "edge") && fields.values.all(::clean)
        val editable = fields.filterKeys { it in setOf("add", "port", "id", "ps", "scy", "sni", "alpn", "fp") }.toMutableMap()
        mapOf("ps" to "", "scy" to "auto", "sni" to fields.getValue("add"), "alpn" to "", "fp" to "").forEach { (key, default) -> editable.putIfAbsent(key, default) }
        return OtherUriValue(raw, editable, valid) { key, value ->
            if (key == "add") require(host(value))
            if (key == "port") require(port(value))
            val replacement = if (key in numbers) {
                require(value.matches(Regex("0|[1-9][0-9]*")))
                value
            } else quote(value)
            val span = spans[key]
            val edited = if (span != null) json.replaceRange(span, replacement)
                else json.substringBeforeLast('}') + "," + quote(key) + ":" + replacement + "}" + json.substringAfterLast('}')
            val encoder = (if (url) Base64.getUrlEncoder() else Base64.getEncoder()).let { if ('=' in payload) it else it.withoutPadding() }
            "vmess://" + encoder.encodeToString(edited.toByteArray(Charsets.UTF_8))
        }
    }
}
