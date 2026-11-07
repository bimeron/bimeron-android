package org.bimeron.app

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/**
 * App-owned SSH host keys accepted through accept-new offers (core ADR 0037): one plain known_hosts file per
 * profile, `<filesDir>/trusted-hosts/<uuid>.known_hosts` (directory 0700, file 0600). The directory is its
 * own top-level tree, outside `profiles/`, `ssconf/` and `ssh-resources/`, so the store's fail-closed orphan
 * sweep never sees it. Lines are only ever ones Core offered and the user trusted, one per line.
 *
 * Not thread-safe: [ProfileStore] serializes every call under its guard. Calls do short blocking file IO
 * only and take no lock. Any failure throws [IOException] with fixed text.
 */
internal class TrustedHostKeys(root: File) {
    enum class Append { ADDED, DUPLICATE, FULL }

    val directory: File = root.absoluteFile.toPath().normalize().toFile()
    /** Test hook for publication failures; must be atomic. */
    internal var publish: (File, File) -> Unit = { source, target -> Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE) }

    /**
     * The stored lines for [id] (empty when none), one per line, at most [MAX_BYTES]. Fails closed: a file
     * with any line that is not a valid offer line ([SshAuthenticationController.validKnownHostsLine]) is a
     * read failure, never silently shortened, because a dropped line would turn its host into a new one.
     */
    fun read(id: UUID): ByteArray = safe { valid(readFile(file(id)) ?: ByteArray(0)).joinToString("") { "$it\n" }.toByteArray(Charsets.US_ASCII) }

    /** Number of stored keys for [id]. */
    fun count(id: UUID): Int = safe { valid(readFile(file(id)) ?: ByteArray(0)).size }

    /**
     * Appends [line] for [id] with an atomic replace (tmp, fsync, ATOMIC_MOVE, directory fsync). An exact
     * duplicate is not written again; a line that would grow the file past [MAX_BYTES] (Core's limit) is
     * refused with [Append.FULL]. [line] must already be a validated offer line.
     */
    fun append(id: UUID, line: String): Append = safe {
        require(SshAuthenticationController.validKnownHostsLine(line))
        val existing = valid(readFile(file(id)) ?: ByteArray(0))
        if (line in existing) return@safe Append.DUPLICATE
        val next = (existing + line).joinToString("") { "$it\n" }.toByteArray(Charsets.US_ASCII)
        if (next.size > MAX_BYTES) return@safe Append.FULL
        write(id, next)
        Append.ADDED
    }

    /** Removes every stored key of [id]; a missing file is not an error. */
    fun forget(id: UUID): Unit = safe {
        checked(directory)
        if (!Files.exists(directory.toPath(), NOFOLLOW_LINKS)) return@safe
        removeTemporaries(id)
        val target = file(id)
        if (Files.deleteIfExists(target.toPath())) syncDirectory(directory)
    }

    private fun file(id: UUID): File = File(directory, "$id$SUFFIX").also(::checked)

    private fun write(id: UUID, bytes: ByteArray) {
        privateDirectory()
        removeTemporaries(id)
        val temp = File(directory, "$id$SUFFIX$TEMP${java.security.SecureRandom().nextLong().toULong().toString(16)}")
        checked(temp)
        try {
            Files.createFile(temp.toPath(), PosixFilePermissions.asFileAttribute(PRIVATE_FILE))
            Files.setPosixFilePermissions(temp.toPath(), PRIVATE_FILE)
            FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            publish(temp, file(id))
        } finally {
            Files.deleteIfExists(temp.toPath())
        }
        syncDirectory(directory)
    }

    private fun readFile(file: File): ByteArray? {
        val path = file.toPath()
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        require(Files.isRegularFile(path, NOFOLLOW_LINKS))
        val bytes = file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= MAX_BYTES) {
                val n = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
                if (n < 0) break
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
        require(bytes.size <= MAX_BYTES)
        return bytes
    }

    /** Leftovers of an interrupted write for [id]; never the published file. */
    private fun removeTemporaries(id: UUID) {
        val prefix = "$id$SUFFIX$TEMP"
        (directory.listFiles() ?: return).filter { it.name.startsWith(prefix) }.forEach {
            checked(it)
            Files.deleteIfExists(it.toPath())
        }
    }

    private fun privateDirectory() {
        checked(directory)
        if (!Files.exists(directory.toPath(), NOFOLLOW_LINKS)) {
            Files.createDirectory(directory.toPath(), PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY))
            directory.parentFile?.let(::syncDirectory)
        }
        require(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS))
        Files.setPosixFilePermissions(directory.toPath(), PRIVATE_DIRECTORY)
    }

    /** Rejects traversal outside [directory] and any symlink on the path. */
    private fun checked(file: File) {
        val path = file.absoluteFile.toPath().normalize()
        val base = directory.toPath()
        require(path == base || path.parent == base)
        var ancestor: java.nio.file.Path? = path
        while (ancestor != null) {
            require(!Files.isSymbolicLink(ancestor))
            ancestor = ancestor.parent
        }
    }

    private fun syncDirectory(target: File) {
        runCatching { FileChannel.open(target.toPath(), READ).use { it.force(true) } }
    }

    private inline fun <T> safe(block: () -> T): T = try { block() } catch (_: Exception) {
        throw IOException("Trusted host key operation failed")
    }

    companion object {
        const val DIRECTORY = "trusted-hosts"
        /** Core's `AcceptNewHostKeys` limit. */
        const val MAX_BYTES = 256 * 1024
        private const val SUFFIX = ".known_hosts"
        private const val TEMP = ".tmp-"
        private val PRIVATE_DIRECTORY = PosixFilePermissions.fromString("rwx------")
        private val PRIVATE_FILE = PosixFilePermissions.fromString("rw-------")

        /** The distinct lines of a stored file, in order; throws unless every non-empty line is valid. */
        private fun valid(bytes: ByteArray): List<String> {
            val lines = String(bytes, Charsets.ISO_8859_1).split('\n').filter { it.isNotEmpty() }
            require(lines.all(SshAuthenticationController::validKnownHostsLine))
            return lines.distinct()
        }
    }
}

/**
 * Persists a key the user trusted after Core accepted the reply. Never throws: on an I/O failure or a full
 * store the current session still proceeds (Core remembers the key for its reconnects) and a fixed WARN is
 * journaled. A profile deleted meanwhile has nothing to persist into.
 */
internal fun persistAcceptedHostKey(line: String, store: (String) -> TrustedHostKeys.Append?, warn: (Int) -> Unit) {
    val saved = try { store(line) != TrustedHostKeys.Append.FULL } catch (_: Exception) { false }
    if (!saved) warn(R.string.journal_trusted_host_key_not_saved)
}
