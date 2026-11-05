package org.bimeron.app

import java.util.UUID

/** Owns imports through raw edits and undo. Only terminal disposal releases retry bytes. */
class SshDraftResources(val profileId: UUID) {
    private val tokens = linkedMapOf<String, SshProfileResources.StagedToken>()
    private val committed = mutableSetOf<SshProfileResources.StagedToken>()
    private var disposed = false

    @Synchronized fun accept(token: SshProfileResources.StagedToken): String? {
        if (disposed) return null
        return "/bimeron-import/${UUID.randomUUID()}".also { tokens[it] = token }
    }

    @Synchronized fun resolve(text: String, resources: SshProfileResources): String {
        check(!disposed)
        var document = ConfigDocument.parse(text)
        fields.forEach { (field, kind) ->
            val reference = document.value("AccessKey", field)
            val token = tokens[reference]
            if (token != null) {
                require(token.kind == kind)
                val generation = resources.prepare(profileId, token)
                document = document.set("AccessKey", field, generation.path)
            } else if (reference.startsWith("/bimeron-import/")) {
                throw ProfileError(R.string.ssh_error_prepare_files)
            }
        }
        return document.render()
    }

    @Synchronized fun <T> commit(block: () -> T): T {
        check(!disposed)
        return block()
    }

    @Synchronized fun saved(text: String) {
        val paths = paths(text)
        tokens.values.filterTo(committed) { it.prepared?.path in paths }
    }

    @Synchronized fun dispose(resources: SshProfileResources) {
        disposed = true
        tokens.values.filter { it !in committed }.forEach { token ->
            runCatching { token.prepared?.let(resources::abort) ?: resources.discard(token) }
        }
        tokens.clear()
    }

    companion object {
        private val fields = mapOf("IdentityFile" to SshProfileResources.Kind.IDENTITY,
            "KnownHostsFile" to SshProfileResources.Kind.KNOWN_HOSTS)
        fun paths(text: String): Set<String> {
            val document = ConfigDocument.parse(text)
            return fields.keys.map { document.value("AccessKey", it) }.filter { it.isNotEmpty() }.toSet()
        }
    }
}
