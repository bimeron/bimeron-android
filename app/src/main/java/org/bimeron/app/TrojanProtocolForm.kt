package org.bimeron.app

import java.net.URI
import java.net.URLDecoder

internal object TrojanProtocolForm : ProtocolFormDescriptor {
    override val types = setOf("trojan")
    override val sourceSchemes = setOf("trojan")
    override val fields = listOf(
        ProtocolField("Server", R.string.trojan_form_server),
        ProtocolField("Port", R.string.trojan_form_port, keyboardType = ProtocolKeyboardType.NUMBER),
        ProtocolField("Password", R.string.trojan_form_password, secret = true),
        ProtocolField("ServerName", R.string.trojan_form_server_name),
        ProtocolField("Network", R.string.trojan_form_network, options = listOf("", "tcp", "ws", "grpc")),
        ProtocolField("Security", R.string.trojan_form_security, options = listOf("", "tls")),
        ProtocolField("ALPN", R.string.trojan_form_alpn),
        ProtocolField("WSPath", R.string.trojan_form_ws_path, secret = true),
        ProtocolField("WSHost", R.string.trojan_form_ws_host, secret = true),
        ProtocolField("GRPCServiceName", R.string.trojan_form_grpc_service, secret = true),
        ProtocolField("GRPCAuthority", R.string.trojan_form_grpc_authority, secret = true),
        ProtocolField("GRPCMultiMode", R.string.trojan_form_grpc_multi, options = listOf("", "false", "true")),
    )
    override val hintRes = R.string.trojan_form_hint

    override fun rawOnly(doc: ConfigDocument): Boolean {
        fun get(key: String) = doc.value("AccessKey", key)
        val allowedKeys = fields.map { it.key }.toSet() + "Type"
        return doc.source() != null || get("Network") !in listOf("", "tcp", "ws", "grpc") ||
            get("Security") !in listOf("", "tls") ||
            doc.values("AccessKey").keys.any { it !in allowedKeys } ||
            fields.any { get(it.key).any(Char::isISOControl) }
    }

    override fun invalid(doc: ConfigDocument): Boolean {
        fun get(key: String) = doc.value("AccessKey", key)
        val network = get("Network").ifEmpty { "tcp" }
        if (get("Password").isEmpty() || get("Password").any { it == '\r' || it == '\n' }) return true
        val server = get("Server")
        if (server.isEmpty() || server.any { it in " /\t\r\n" }) return true
        if (get("ServerName").ifEmpty { server }.any { it in " /\t\r\n" }) return true
        if (get("GRPCMultiMode") !in listOf("", "true", "false")) return true
        // An explicit empty boolean is rejected by the core; omission remains distinct.
        if (doc.value("AccessKey", "GRPCMultiMode", "absent").isEmpty()) return true
        if (network != "ws" && listOf("WSPath", "WSHost").any { get(it).isNotEmpty() }) return true
        if (network != "grpc" && (listOf("GRPCServiceName", "GRPCAuthority").any { get(it).isNotEmpty() } || get("GRPCMultiMode") == "true")) return true
        if (network == "ws" && (!validPath(get("WSPath")) || !validHost(get("WSHost")))) return true
        if (network == "grpc" && (get("GRPCServiceName").isEmpty() || get("GRPCServiceName").any { it in "/?\\#|" || it.isWhitespace() })) return true
        val authority = get("GRPCAuthority")
        if (authority.startsWith(':') || authority.any { it in "/\\@?#" || it.isWhitespace() }) return true
        val alpn = get("ALPN")
        return alpn.isNotEmpty() && alpn.split(',').any {
            it.isEmpty() || it.any(Char::isWhitespace) || (network == "ws" && it != "http/1.1") || (network == "grpc" && it != "h2")
        }
    }

    private fun validPath(value: String): Boolean {
        if (value.isEmpty()) return true
        if (!value.startsWith('/') || value.any { it in "#\\\r\n" }) return false
        return try {
            // Parse as a path even when it starts with //, as Go ParseRequestURI does.
            val uri = URI("/.$value")
            uri.rawQuery.orEmpty().split('&').none {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") == "ed"
            }
        } catch (_: IllegalArgumentException) { false } catch (_: java.net.URISyntaxException) { false }
    }

    private fun validHost(value: String): Boolean {
        if (value.isEmpty()) return true
        if (value.any(Char::isWhitespace)) return false
        return try {
            val uri = URI("https://$value")
            !uri.host.isNullOrEmpty() && uri.rawUserInfo == null && uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null
        } catch (_: java.net.URISyntaxException) { false }
    }
}
