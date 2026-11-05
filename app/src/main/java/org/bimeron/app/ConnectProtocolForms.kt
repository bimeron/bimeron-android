package org.bimeron.app

internal object HttpConnectForm : ProtocolFormDescriptor {
    override val types = setOf("http", "https")
    override val sourceSchemes = setOf("http", "https")
    override val fields = connectFields()
    override fun rawOnly(doc: ConfigDocument) = connectRawOnly(doc, fields)
    override fun invalid(doc: ConfigDocument): Boolean =
        connectCredentialsInvalid(doc) || doc.value("AccessKey", "Server").any { it in " /?#@\\%\t\r\n" }
}

internal object NaiveH2Form : ProtocolFormDescriptor {
    override val types = setOf("naive")
    override val sourceSchemes = setOf("naive+https")
    override val fields = connectFields() + ProtocolField("ServerName", R.string.connect_form_server_name)
    override fun rawOnly(doc: ConfigDocument) = connectRawOnly(doc, fields)
    override fun invalid(doc: ConfigDocument) = connectCredentialsInvalid(doc, required = true) || connectNameInvalid(doc)
}

internal object MasqueForm : ProtocolFormDescriptor {
    override val types = setOf("masque")
    override val sourceSchemes = setOf("masque")
    override val fields = connectFields() + listOf(
        ProtocolField("ServerName", R.string.connect_form_server_name),
        ProtocolField("CAFile", R.string.connect_form_ca_file, secret = true),
        ProtocolField("MASQUEPath", R.string.connect_form_masque_path, secret = true),
    )
    override fun rawOnly(doc: ConfigDocument) = connectRawOnly(doc, fields)
    override fun invalid(doc: ConfigDocument): Boolean {
        val ca = doc.value("AccessKey", "CAFile")
        val path = doc.value("AccessKey", "MASQUEPath")
        val rest = path.replace("{target_host}", "").replace("{target_port}", "")
        return connectCredentialsInvalid(doc) || connectNameInvalid(doc) ||
            doc.value("AccessKey", "Server").any { it in "?#" } ||
            (ca.isNotEmpty() && !ca.startsWith('/')) ||
            (path.isNotEmpty() && (!path.startsWith('/') || path.startsWith("//") ||
                path.any { it in "?#\r\n\t " } ||
                path.windowed("{target_host}".length).count { it == "{target_host}" } != 1 ||
                path.windowed("{target_port}".length).count { it == "{target_port}" } != 1 ||
                rest.any { it in "{}" }))
    }
}

private fun connectFields() = listOf(
    ProtocolField("Server", R.string.connect_form_server),
    ProtocolField("Port", R.string.connect_form_port, keyboardType = ProtocolKeyboardType.NUMBER),
    ProtocolField("Username", R.string.connect_form_username, secret = true),
    ProtocolField("Password", R.string.connect_form_password, secret = true),
)

private fun connectCredentialsInvalid(doc: ConfigDocument, required: Boolean = false): Boolean {
    val user = doc.value("AccessKey", "Username")
    val password = doc.value("AccessKey", "Password")
    return (required && user.isEmpty()) || user.isEmpty() != password.isEmpty() ||
        user.any { it in ":\r\n" } || password.any { it in "\r\n" }
}

private fun connectNameInvalid(doc: ConfigDocument): Boolean {
    val host = doc.value("AccessKey", "Server")
    val name = doc.value("AccessKey", "ServerName").ifEmpty { host }
    return host.isEmpty() || name.isEmpty() || host.any { it in " /\t\r\n" } || name.any { it in " /\t\r\n" }
}

private fun connectRawOnly(doc: ConfigDocument, fields: List<ProtocolField>): Boolean {
    if (doc.source() != null) return true
    val allowed = fields.map { it.key }.toSet() + "Type"
    var section = ""
    // Inspect names only: unknown options (even empty ones) and wrappers remain untouched in Raw.
    return doc.render().lineSequence().any { line ->
        val text = line.trim()
        if (text.startsWith('[') && text.endsWith(']')) section = text.substring(1, text.lastIndex).trim()
        section in setOf("ShadowTLS", "IKEv2") ||
            (section == "AccessKey" && text.isNotEmpty() && !text.startsWith('[') &&
                !text.startsWith('#') && !text.startsWith(';') && text.substringBefore('=').trim() !in allowed)
    }
}
