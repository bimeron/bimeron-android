package org.bimeron.app

import java.util.UUID

/** Owns imports through raw edits and undo. Only terminal disposal releases retry bytes. */
class SshDraftResources(val profileId: UUID) {
    private val tokens = linkedMapOf<String, SshProfileResources.StagedToken>()
    private val committed = mutableSetOf<SshProfileResources.StagedToken>()
    private val pins = mutableSetOf<String>()
    private var disposed = false

    /**
     * Pins the saved generations that [text] (a base this draft was opened or rebased at) references, so
     * another writer's save or delete of the profile keeps them while this draft may still save them,
     * e.g. as a new profile. Released by [dispose]. Memory only; lock order: draft, then the pin registry.
     */
    @Synchronized fun pinBase(text: String) {
        if (disposed) return
        val paths = runCatching { paths(text) }.getOrDefault(emptySet())
        paths.mapNotNull { SshGenerationPins.generationOf(profileId, it) }.filter { pins.add(it) }.let(SshGenerationPins::pin)
    }

    @Synchronized fun accept(token: SshProfileResources.StagedToken): String? {
        if (disposed) return null
        return "/bimeron-import/${UUID.randomUUID()}".also { tokens[it] = token }
    }

    /** The kind of this draft's import whose placeholder is [reference], or null when it is not one. */
    @Synchronized fun importKind(reference: String): SshProfileResources.Kind? = if (disposed) null else tokens[reference]?.kind

    @Synchronized fun resolve(text: String, resources: SshProfileResources): String {
        check(!disposed)
        var document = ConfigDocument.parse(text)
        fields.forEach { (field, kind) ->
            val reference = document.value("AccessKey", field)
            val token = tokens[reference]
            if (token != null) {
                require(token.kind == kind)
                val generation = resources.prepare(profileId, token, draft = true)
                document = document.set("AccessKey", field, generation.path)
            } else if (reference.startsWith("/bimeron-import/")) {
                throw ProfileError(R.string.ssh_error_prepare_files)
            }
        }
        return document.render()
    }

    /**
     * For saving [text] as a new profile [profileId]: copies the SSH files it references into a new draft
     * for that profile, both this draft's imports (staged or published) and files of this profile's saved
     * generations. Other paths are left as they are. Returns the new draft, which the caller owns and must
     * dispose, and [text] pointing at its placeholders. This draft keeps its own imports unchanged.
     * Lock order: this draft, then the new one (not yet shared), then [resources].
     */
    @Synchronized fun copyTo(profileId: UUID, text: String, resources: SshProfileResources): Pair<SshDraftResources, String> {
        check(!disposed)
        val target = SshDraftResources(profileId)
        try {
            var document = ConfigDocument.parse(text)
            fields.forEach { (field, kind) ->
                val reference = document.value("AccessKey", field)
                val token = tokens[reference]
                val copy = when {
                    token != null -> if (token.kind == kind) resources.copy(token) else throw ProfileError(R.string.ssh_error_prepare_files)
                    reference.startsWith("/bimeron-import/") -> throw ProfileError(R.string.ssh_error_prepare_files)
                    reference.isEmpty() -> null
                    else -> resources.copyCommitted(this.profileId, reference, kind)
                } ?: return@forEach
                document = document.set("AccessKey", field, checkNotNull(target.accept(copy)))
            }
            return target to document.render()
        } catch (e: Exception) {
            target.dispose(resources)
            throw e as? ProfileError ?: ProfileError(R.string.ssh_error_prepare_files)
        }
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
        if (pins.isNotEmpty()) {
            SshGenerationPins.release(pins)
            runCatching { resources.afterUnpinned(pins) }
            pins.clear()
        }
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

/**
 * Process-wide, memory-only pins on saved SSH generation directories referenced by open editor drafts'
 * base texts. [SshProfileResources.afterSaved] and [SshProfileResources.afterDeleted] keep pinned
 * generations. The startup sweep runs before any draft exists and ignores pins. Innermost lock: it never
 * calls out while held.
 */
internal object SshGenerationPins {
    private val counts = mutableMapOf<String, Int>()

    @Synchronized fun pin(directories: Collection<String>) { directories.forEach { counts[it] = (counts[it] ?: 0) + 1 } }
    @Synchronized fun release(directories: Collection<String>) {
        directories.forEach { directory ->
            val count = (counts[directory] ?: return@forEach) - 1
            if (count > 0) counts[directory] = count else counts.remove(directory)
        }
    }
    @Synchronized fun pinned(directory: String) = directory in counts

    /** The generation directory of a saved file of [profileId], from the path alone (no filesystem access). */
    fun generationOf(profileId: UUID, path: String): String? {
        val file = java.io.File(path)
        if (!file.isAbsolute || file.toPath().normalize().toString() != path) return null
        if (SshProfileResources.Kind.entries.none { it.filename == file.name }) return null
        val generation = file.parentFile ?: return null
        val profile = generation.parentFile ?: return null
        if (profile.name != profileId.toString() || profile.parentFile?.name != "profiles") return null
        if (runCatching { UUID.fromString(generation.name).toString() == generation.name }.getOrDefault(false).not()) return null
        return generation.path
    }
}
