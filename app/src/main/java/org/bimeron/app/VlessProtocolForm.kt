package org.bimeron.app

import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/** Explicit core schema only. Advanced/unknown options stay byte-for-byte in the raw editor. */
internal object VlessProtocolForm : ProtocolFormDescriptor {
    override val types = setOf("vless")
    override val sourceSchemes = setOf("vless")
    private val networks = listOf("tcp", "ws", "grpc", "xhttp")
    private val fingerprints = listOf("", "chrome", "firefox", "safari", "ios", "android", "edge")
    private val flows = listOf("", "xtls-rprx-vision", "xtls-rprx-vision-udp443")
    private val groups = mapOf("ws" to listOf("WSPath", "WSHost", "WSEarlyData"),
        "grpc" to listOf("GRPCServiceName", "GRPCAuthority", "GRPCMultiMode"),
        "xhttp" to listOf("XHTTPPath", "XHTTPHost", "XHTTPMode"))
    private val reality = listOf("PublicKey", "ShortID", "SpiderX")
    private fun field(key: String, options: List<String>? = null) = ProtocolField(key, labels.getValue(key),
        secret = key in setOf("ID", "ServerName", "ALPN", "PublicKey", "ShortID", "SpiderX", "WSPath", "WSHost", "GRPCServiceName", "GRPCAuthority", "XHTTPPath", "XHTTPHost"),
        options = options, keyboardType = if (key in setOf("Port", "WSEarlyData")) ProtocolKeyboardType.NUMBER else if (options == null) ProtocolKeyboardType.PASSWORD else ProtocolKeyboardType.TEXT)
    private val labels = mapOf(
        "Server" to R.string.protocol_form_server, "Port" to R.string.protocol_form_port,
        "ID" to R.string.vless_id, "Encryption" to R.string.vless_encryption, "Network" to R.string.vless_network,
        "Security" to R.string.vless_security, "Flow" to R.string.vless_flow, "ServerName" to R.string.vless_server_name,
        "Fingerprint" to R.string.vless_fingerprint, "PublicKey" to R.string.vless_public_key, "ShortID" to R.string.vless_short_id,
        "SpiderX" to R.string.vless_spider_x, "ALPN" to R.string.vless_alpn, "WSPath" to R.string.vless_ws_path,
        "WSHost" to R.string.vless_ws_host, "WSEarlyData" to R.string.vless_early_data,
        "GRPCServiceName" to R.string.vless_service, "GRPCAuthority" to R.string.vless_authority,
        "GRPCMultiMode" to R.string.vless_multi, "XHTTPPath" to R.string.vless_xhttp_path,
        "XHTTPHost" to R.string.vless_xhttp_host, "XHTTPMode" to R.string.vless_xhttp_mode)
    override val fields get() = labels.keys.map { field(it) }
    override fun fields(doc: ConfigDocument): List<ProtocolField> {
        val network = doc.value("AccessKey", "Network")
        val isReality = doc.value("AccessKey", "Security") == "reality"
        return listOf(field("Server"), field("Port"), field("ID"), field("Encryption", listOf("none")),
            field("Network", networks), field("Security", if (network == "ws") listOf("tls") else listOf("tls", "reality"))) +
            (if ((network == "tcp" && isReality) || doc.value("AccessKey", "Flow").isNotEmpty()) listOf(field("Flow", flows).copy(removable = network != "tcp" || !isReality)) else emptyList()) +
            listOf(field("ServerName"), field("Fingerprint", fingerprints), field("ALPN")) +
            reality.filter { isReality || doc.values("AccessKey").containsKey(it) }.map { field(it).copy(removable = !isReality) } +
            groups.flatMap { (transport, keys) -> keys.filter { transport == network || doc.values("AccessKey").containsKey(it) } }.map { field(it, when (it) {
                "GRPCMultiMode" -> listOf("false", "true")
                "XHTTPMode" -> if (isReality) listOf("stream-one", "stream-up", "auto") else listOf("packet-up", "stream-up", "stream-one", "auto")
                else -> null
            }).copy(removable = it !in groups[network].orEmpty()) }
    }
    override fun rawOnly(doc: ConfigDocument): Boolean {
        val v = doc.values("AccessKey")
        fun get(k: String) = v[k].orEmpty()
        return v.keys.any { it != "Type" && it !in labels } || v.values.any { s -> s.any(Char::isISOControl) } ||
            get("Network") !in networks || get("Security") !in listOf("tls", "reality") || get("Encryption") != "none" ||
            get("Fingerprint") !in fingerprints || get("Flow") !in flows ||
            ("GRPCMultiMode" in v && get("GRPCMultiMode") !in listOf("", "true", "false")) ||
            ("XHTTPMode" in v && get("XHTTPMode") !in listOf("", "packet-up", "stream-up", "stream-one", "auto"))
    }
    override fun invalid(doc: ConfigDocument): Boolean {
        fun get(k: String) = doc.value("AccessKey", k)
        fun contains(k: String, chars: String) = get(k).any { it in chars }
        val n = get("Network"); val r = get("Security") == "reality"; val alpn = get("ALPN")
        if (contains("Server", " /\t\r\n") || !Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}").matches(get("ID")) ||
            contains("ServerName", " /\t\r\n") || (get("Flow").isNotEmpty() && !(n == "tcp" && r)) || (n == "ws" && r)) return true
        // Known stale fields stay visible until explicitly corrected; never discard them on a selector change.
        if (groups.any { (network, keys) -> network != n && keys.any { doc.values("AccessKey").containsKey(it) } } ||
            (!r && reality.any { doc.values("AccessKey").containsKey(it) }) ||
            (n == "xhttp" && get("XHTTPMode").isEmpty()) ||
            (n == "grpc" && doc.values("AccessKey").containsKey("GRPCMultiMode") && get("GRPCMultiMode").isEmpty())) return true
        if (r) {
            val key = try { Base64.getUrlDecoder().decode(get("PublicKey")) } catch (_: IllegalArgumentException) { null }
            if (get("ServerName").isEmpty() || get("Fingerprint").isEmpty() || key?.size != 32 ||
                !Regex("[A-Za-z0-9_-]{43}").matches(get("PublicKey")) || !Regex("(?:[0-9a-fA-F]{2}){0,8}").matches(get("ShortID")) ||
                (n == "tcp" && alpn.isNotEmpty()) || (n != "tcp" && alpn !in listOf("", "h2"))) return true
        }
        if (n == "ws" && (if (doc.values("AccessKey").containsKey("WSEarlyData")) get("WSEarlyData") else "0").toIntOrNull() !in 0..8192) return true
        if (n == "grpc" && (get("GRPCServiceName").isEmpty() || contains("GRPCServiceName", "?\\# \t\r\n") ||
                ("/" in get("GRPCServiceName") && (!get("GRPCServiceName").startsWith('/') || get("GRPCServiceName").endsWith('/') || "//" in get("GRPCServiceName"))) ||
                contains("GRPCAuthority", " /\\@?#\t\r\n") || get("GRPCAuthority").startsWith(':'))) return true
        if (n == "xhttp" && ((r && get("XHTTPMode") == "packet-up") || (alpn == "h3" && get("Fingerprint").isNotEmpty()))) return true
        if (alpn.isNotEmpty() && (alpn.split(',').any { it.isEmpty() || it.any { c -> c in " \t\r\n" } || when (n) {
                    "ws" -> it != "http/1.1"; "grpc" -> it != "h2"; "xhttp" -> it !in listOf("h2", "h3", "http/1.1"); else -> false
                } } || (n == "xhttp" && ',' in alpn && alpn != "h2,http/1.1"))) return true
        if (n in listOf("ws", "xhttp")) {
            val prefix = if (n == "ws") "WS" else "XHTTP"
            val path = get(prefix + "Path"); val host = get(prefix + "Host")
            try {
                if (path.isNotEmpty()) {
                    val uri = URI(path)
                    if (!path.startsWith('/') || uri.isAbsolute || path.any { it in "#\\\r\n " } ||
                        (n == "ws" && uri.rawQuery.orEmpty().split('&').any { URLDecoder.decode(it.substringBefore('='), "UTF-8") == "ed" })) return true
                }
                if (host.isNotEmpty()) {
                    val uri = URI("https://$host")
                    if (uri.rawAuthority.isNullOrEmpty() || uri.rawUserInfo != null || uri.rawPath.isNotEmpty() || uri.rawQuery != null || uri.rawFragment != null || host.any { it in " \t\r\n" }) return true
                }
            } catch (_: Exception) { return true }
        }
        return false
    }
}
