package org.bimeron.app

internal object Socks5Form : ProtocolFormDescriptor {
    override val types = setOf("socks5")
    override val sourceSchemes = setOf("socks5")
    override val fields = listOf(
        ProtocolField("Server", R.string.protocol_form_server),
        ProtocolField("Port", R.string.protocol_form_port, keyboardType = ProtocolKeyboardType.NUMBER),
        ProtocolField("Username", R.string.protocol_form_username, secret = true),
        ProtocolField("Password", R.string.protocol_form_password, secret = true),
    )
    override fun rawOnly(doc: ConfigDocument) =
        listOf("Method", "Prefix", "Plugin", "PluginOpts").any { doc.value("AccessKey", it).isNotEmpty() }
    override fun invalid(doc: ConfigDocument): Boolean {
        val user = doc.value("AccessKey", "Username")
        val password = doc.value("AccessKey", "Password")
        return user.isEmpty() != password.isEmpty() || user.toByteArray().size > 255 || password.toByteArray().size > 255
    }
}
