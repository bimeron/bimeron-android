package org.bimeron.app

internal object SshProtocolForm : ProtocolFormDescriptor {
    override val types = setOf("ssh")
    override val sourceSchemes = setOf("ssh")
    override val fields = listOf(
        ProtocolField("Server", R.string.ssh_form_server),
        ProtocolField("Port", R.string.ssh_form_port, keyboardType = ProtocolKeyboardType.NUMBER),
        ProtocolField("Username", R.string.ssh_form_username, secret = true),
        ProtocolField("IdentityFile", R.string.ssh_form_identity_file, secret = true),
        ProtocolField("KnownHostsFile", R.string.ssh_form_known_hosts_file, secret = true),
    )
    override val hintRes = R.string.ssh_form_hint

    override fun rawOnly(doc: ConfigDocument): Boolean {
        // Keep unrepresented options (including empty transport options) for Core validation in raw mode.
        var accessKey = false
        val represented = fields.map { it.key } + "Type"
        for (line in doc.render().lineSequence()) {
            val text = line.trim()
            if (text.startsWith('[')) accessKey = text.removePrefix("[").removeSuffix("]").trim() == "AccessKey"
            else if (accessKey && text.isNotEmpty() && !text.startsWith('#') && !text.startsWith(';') &&
                text.substringBefore('=').trim() !in represented) return true
        }
        return listOf("IdentityFile", "KnownHostsFile").any {
            val path = doc.value("AccessKey", it)
            // An absolute filesystem path is representable; actual app readability is checked by Core.
            path.isNotEmpty() && (!path.startsWith('/') || path.startsWith("//"))
        }
    }

    override fun invalid(doc: ConfigDocument) = doc.value("AccessKey", "Username").isEmpty()
}
