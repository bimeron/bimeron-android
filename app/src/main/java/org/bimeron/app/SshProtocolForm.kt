package org.bimeron.app

import java.io.File

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

    /**
     * Decision 8 at the field: a typed IdentityFile/KnownHostsFile value must be empty, a placeholder of one of
     * [imports]' own imports of that kind, or one of its profile's saved generation files of that kind. The form
     * has no files root, so this checks the path shape only; `ProfileStore.validate` repeats the check against
     * the trusted root at save time and the service again at connect time ([SshFileGuard]).
     */
    override fun fieldError(doc: ConfigDocument, key: String, imports: SshDraftResources): Int? {
        val kind = FILES[key] ?: return null
        val path = coreTrim(doc.value("AccessKey", key))
        return if (path.isEmpty() || imported(path, kind, imports)) null else R.string.ssh_form_file_not_imported
    }

    private fun imported(path: String, kind: SshProfileResources.Kind, imports: SshDraftResources): Boolean {
        if (imports.importKind(path) == kind) return true
        val generation = SshGenerationPins.generationOf(imports.profileId, path)?.let(::File) ?: return false
        return File(path).name == kind.filename && generation.parentFile?.parentFile?.parentFile?.name == "ssh-resources"
    }

    private val FILES = mapOf("IdentityFile" to SshProfileResources.Kind.IDENTITY, "KnownHostsFile" to SshProfileResources.Kind.KNOWN_HOSTS)
}
