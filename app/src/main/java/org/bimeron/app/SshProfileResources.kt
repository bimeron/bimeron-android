package org.bimeron.app

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/** App-private imports; startup sweep runs only before the singleton exposes imports. */
class SshProfileResources(private val root: File) {
    enum class Kind(internal val filename: String) { IDENTITY("identity"), KNOWN_HOSTS("known_hosts") }
    class StagedToken internal constructor(internal val owner: Any, internal val kind: Kind, internal val file: File) {
        /** Retained after publication, even if prepare throws; abort only while config is unsaved. */
        var prepared: OwnedGeneration? = null
            internal set
    }
    class OwnedGeneration internal constructor(
        internal val owner: Any, val kind: Kind, val path: String, val profileId: UUID,
    )
    private val owner = Any()
    /**
     * Generations published for drafts and not yet referenced by a saved config. Another writer's save of
     * the same profile ([afterSaved]) must not prune them while the draft can still retry its save.
     */
    private val unsaved = mutableSetOf<String>()
    /** Unreferenced saved generations kept only because a draft pinned them ([SshGenerationPins]). */
    private val deferred = mutableSetOf<String>()
    private fun kept(generation: File) = generation.absolutePath in unsaved ||
        SshGenerationPins.pinned(generation.absolutePath).also { if (it) deferred += generation.absolutePath }
    private val base = root.absoluteFile.toPath().normalize().toFile()
    internal var publish: (File, File) -> Unit = { source, target ->
        Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE)
    }

    internal var syncDirectory: (File) -> Unit = { file ->
        FileChannel.open(file.toPath(), READ).use { it.force(true) }
    }

    @Synchronized fun stage(kind: Kind, input: InputStream): StagedToken = safe {
        var directory: File? = null
        try {
            input.use { source ->
                privateDirectory(base)
                val staging = File(base, "staging").also(::privateDirectory)
                directory = File(staging, UUID.randomUUID().toString()).also(::privateDirectory)
                val file = File(directory, kind.filename)
                Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = source.read(buffer, 0, minOf(buffer.size, LIMIT + 1 - total))
                        if (count < 0) break
                        if (count == 0) {
                            val byte = source.read()
                            if (byte < 0) break
                            output.write(byte)
                            total++
                        } else {
                            output.write(buffer, 0, count)
                            total += count
                        }
                        if (total > LIMIT) throw IOException()
                    }
                    if (total == 0) throw IOException()
                    output.fd.sync()
                }
                StagedToken(owner, kind, file)
            }
        } catch (e: Exception) {
            directory?.let { runCatching { removeTree(it) } }
            throw e
        }
    }

    /** Publish before saving config. On failure retain the token for retry. To cancel before saving,
     * abort token.prepared if present (publication may precede failure); otherwise discard the token.
     * After saving, use afterSaved instead of aborting a generation referenced by config.
     * [draft]: the generation belongs to an open editor draft until a saved config references it, so another
     * writer's [afterSaved] or [afterDeleted] keeps it; the draft releases it by saving or by [abort].
     */
    @Synchronized fun prepare(profileId: UUID, token: StagedToken, draft: Boolean = false): OwnedGeneration = safe {
        require(token.owner === owner)
        token.prepared?.let {
            require(it.profileId == profileId)
            checked(File(it.path)); require(File(it.path).isFile)
            syncPublished(it)
            return@safe it
        }
        checked(token.file); require(token.file.isFile)
        val profiles = File(base, "profiles").also(::privateDirectory)
        val profile = File(profiles, profileId.toString()).also(::privateDirectory)
        val target = File(profile, UUID.randomUUID().toString())
        syncDirectory(token.file.parentFile)
        val handle = OwnedGeneration(owner, token.kind, File(target, token.kind.filename).absolutePath, profileId)
        try {
            publish(token.file.parentFile, target)
        } catch (e: Exception) {
            // A publisher can fail after rename; retain the moved generation for retry.
            checked(target)
            if (target.isDirectory && !token.file.parentFile.exists()) { token.prepared = handle; if (draft) unsaved += target.absolutePath }
            throw e
        }
        token.prepared = handle
        if (draft) unsaved += target.absolutePath
        syncPublished(handle)
        handle
    }

    private fun syncPublished(handle: OwnedGeneration) {
        val generation = File(handle.path).parentFile
        checked(generation)
        syncDirectory(generation)
        val staging = File(base, "staging")
        checked(staging)
        syncDirectory(staging)
        var directory = generation.parentFile
        while (true) {
            checked(directory)
            syncDirectory(directory)
            if (directory == base) break
            directory = directory.parentFile
        }
        // Publish the owned root's entry too; its parent must already exist.
        val parent = base.parentFile
        require(parent.isDirectory)
        syncDirectory(parent)
    }

    @Synchronized fun discard(token: StagedToken): Unit = safe {
        require(token.owner === owner)
        if (token.prepared == null) removeTree(token.file.parentFile)
    }

    /** Terminal abandonment only: a token whose generation was aborted cannot be retried. */
    @Synchronized fun abort(handle: OwnedGeneration): Unit = safe {
        require(handle.owner === owner)
        require(ownedPath(handle.profileId, handle.path) != null)
        removeTree(File(handle.path).parentFile)
        unsaved -= File(handle.path).parentFile.absolutePath
        removeIfEmpty(File(handle.path).parentFile.parentFile)
    }

    /** Called when drafts released their pins on [generations]: removes those whose prune was deferred. */
    @Synchronized fun afterUnpinned(generations: Collection<String>): Unit = safe {
        generations.filter { it in deferred && !SshGenerationPins.pinned(it) }.forEach { path ->
            deferred -= path
            val generation = File(path)
            checked(generation)
            if (generation.parentFile.parentFile != File(base, "profiles")) return@forEach
            removeTree(generation)
            removeIfEmpty(generation.parentFile)
        }
    }

    private fun removeIfEmpty(directory: File) {
        checked(directory)
        if (directory.isDirectory && directory.list()?.isEmpty() == true) directory.delete()
    }

    /** Stages a private copy of [token]'s file (its published generation once prepared) for another draft. */
    @Synchronized fun copy(token: StagedToken): StagedToken = safe {
        require(token.owner === owner)
        val source = token.prepared?.let { File(it.path) } ?: token.file
        checked(source); require(source.isFile)
        stage(token.kind, source.inputStream())
    }

    /**
     * Stages a private copy of the [kind] file at [path] if it belongs to a generation of [profileId];
     * null if [path] is not such a file (it is then left to the caller as an ordinary path).
     */
    @Synchronized fun copyCommitted(profileId: UUID, path: String, kind: Kind): StagedToken? = safe {
        if (ownedPath(profileId, path) == null || File(path).name != kind.filename) return@safe null
        val source = File(path)
        require(source.isFile)
        stage(kind, source.inputStream())
    }

    /** Pass ALL identity and known-hosts references. Unknown paths conservatively prevent pruning. */
    @Synchronized fun afterSaved(profileId: UUID, referencedPaths: Set<String>): Unit = safe {
        // Owned references are saved now, even when an unowned path below prevents pruning.
        val referenced = referencedPaths.mapNotNull { ownedPath(profileId, it) }.toSet()
        unsaved -= referenced
        deferred -= referenced
        if (referenced.size != referencedPaths.size) return@safe
        val profile = profileDirectory(profileId)
        checked(profile)
        if (!profile.exists()) return@safe
        val generations = profile.listFiles() ?: throw IOException()
        // Validate the entire set before any removal; unexpected entries are retained.
        if (generations.any { !generation(it) }) return@safe
        generations.filter { it.absolutePath !in referenced && !kept(it) }.forEach(::removeTree)
    }

    /**
     * Removes the profile's files, except generations an open draft published and has not saved yet, or
     * pinned through its base text; those go when the draft aborts or unpins them.
     */
    @Synchronized fun afterDeleted(profileId: UUID): Unit = safe {
        val profile = profileDirectory(profileId)
        checked(profile)
        if (!profile.exists()) return@safe
        val generations = profile.listFiles() ?: throw IOException()
        if (generations.none { kept(it) }) return@safe removeTree(profile)
        generations.filterNot { kept(it) }.forEach(::removeTree)
    }

    /** Only at singleton initialization before imports exist. Null means config inventory incomplete. */
    @Synchronized fun sweepBeforeDrafts(committedReferences: Map<UUID, Set<String>>?): Unit = safe {
        if (committedReferences == null) return@safe
        checked(base)
        if (!base.exists()) return@safe
        require(base.isDirectory)
        val staging = File(base, "staging")
        val profiles = File(base, "profiles")
        val rootEntries = base.listFiles() ?: throw IOException()
        rootEntries.forEach(::checked)
        if (rootEntries.any { it != staging && it != profiles }) return@safe
        val references = committedReferences.mapValues { (id, paths) ->
            paths.map { ownedPath(id, it) ?: return@safe }.toSet()
        }
        val candidates = mutableListOf<File>()
        if (staging.exists()) {
            checked(staging)
            require(staging.isDirectory)
            val entries = staging.listFiles() ?: throw IOException()
            if (entries.any { !generation(it) }) return@safe
            candidates.addAll(entries)
        }
        if (profiles.exists()) {
            checked(profiles)
            require(profiles.isDirectory)
            val entries = profiles.listFiles() ?: throw IOException()
            entries.forEach(::checked)
            if (entries.any { !uuid(it.name) || !it.isDirectory }) return@safe
            entries.forEach { profile ->
                val generations = profile.listFiles() ?: throw IOException()
                if (generations.any { !generation(it) }) return@safe
                val keep = references[UUID.fromString(profile.name)].orEmpty()
                candidates.addAll(generations.filter { it.absolutePath !in keep })
            }
        }
        // Every candidate and retained entry has been checked before the first deletion.
        candidates.forEach(::removeTree)
    }

    private fun profileDirectory(id: UUID) = File(base, "profiles/$id")
    private fun ownedPath(id: UUID, path: String): String? {
        val file = File(path)
        if (!file.isAbsolute || file.absoluteFile.toPath().normalize().toString() != path) return null
        val directory = file.parentFile ?: return null
        if (directory.parentFile != profileDirectory(id) || !uuid(directory.name)) return null
        if (Kind.entries.none { it.filename == file.name }) return null
        checked(file)
        return directory.absolutePath
    }
    private fun generation(file: File): Boolean {
        checked(file)
        if (!uuid(file.name) || !file.isDirectory) return false
        val entries = file.listFiles() ?: return false
        return entries.size == 1 && entries.all { child ->
            checked(child)
            child.isFile && Kind.entries.any { it.filename == child.name }
        }
    }
    private fun uuid(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    private fun checked(file: File) {
        val path = file.absoluteFile.toPath().normalize()
        require(path.startsWith(base.toPath()))
        var ancestor: java.nio.file.Path? = path
        while (ancestor != null) {
            require(!Files.isSymbolicLink(ancestor))
            ancestor = ancestor.parent
        }
        require(file.canonicalFile == file.absoluteFile)
    }
    private fun privateDirectory(file: File) {
        checked(file)
        if (!file.exists()) require(file.mkdir())
        require(file.isDirectory)
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rwx------"))
    }
    private fun removeTree(file: File) {
        checked(file)
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return
        fun validate(entry: File) {
            checked(entry)
            if (entry.isDirectory) (entry.listFiles() ?: throw IOException()).forEach(::validate)
        }
        validate(file)
        fun delete(entry: File) {
            if (entry.isDirectory) (entry.listFiles() ?: throw IOException()).forEach(::delete)
            if (!entry.delete()) throw IOException()
        }
        delete(file)
    }
    private inline fun <T> safe(block: () -> T): T = try { block() } catch (_: Exception) {
        throw IOException("SSH resource operation failed")
    }
    private companion object { const val LIMIT = 64 * 1024 }
}
