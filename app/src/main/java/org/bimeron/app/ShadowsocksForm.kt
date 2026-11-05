package org.bimeron.app

internal object ShadowsocksForm : ProtocolFormDescriptor {
    val methods = listOf("chacha20-ietf-poly1305", "aes-128-gcm", "aes-192-gcm", "aes-256-gcm", "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305")
    val plugins = listOf("", "v2ray-plugin", "obfs-local")
    override val types = setOf("ss", "shadowsocks")
    override val sourceSchemes = setOf("ss")
    override val fields = listOf(
        ProtocolField("Server", R.string.protocol_form_server),
        ProtocolField("Port", R.string.protocol_form_port, keyboardType = ProtocolKeyboardType.NUMBER),
        ProtocolField("Method", R.string.protocol_form_method, options = methods),
        ProtocolField("Password", R.string.protocol_form_password, secret = true),
        ProtocolField("Prefix", R.string.protocol_form_prefix),
        ProtocolField("Plugin", R.string.protocol_form_plugin, options = plugins),
        ProtocolField("PluginOpts", R.string.protocol_form_options, secret = true),
    )
    override val hintRes = R.string.protocol_form_plugin_hint
    override fun rawOnly(doc: ConfigDocument): Boolean =
        doc.value("AccessKey", "Method") !in methods || doc.value("AccessKey", "Plugin") !in plugins ||
            (doc.value("AccessKey", "Method").startsWith("2022-") && doc.value("AccessKey", "Prefix").isNotEmpty())
    override fun invalid(doc: ConfigDocument) = doc.value("AccessKey", "Password").isEmpty()
}
