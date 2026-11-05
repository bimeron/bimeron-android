package org.bimeron.app

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Collections

enum class GeoDataKind(val fileName: String) { GEOIP("geoip.dat"), GEOSITE("geosite.dat") }

/** Trusted adapter: fully decode the real format and enumerate groups, or throw. Never mutate file. */
fun interface GeoDataValidator {
    fun validate(kind: GeoDataKind, file: File): List<String>
}

object GeoDataCoreAdapter : GeoDataValidator {
    override fun validate(kind: GeoDataKind, file: File): List<String> =
        throw UnsupportedOperationException("Bundled Android Core has no GeoData API")
}

/** Blocking, process-local serialized operations. Caller owns input (it is never closed). */
class GeoDataStore(
    filesDir: File,
    private val validator: GeoDataValidator,
    private val maxBytes: Long = 64L * 1024 * 1024,
) {
    private val directory = File(filesDir, "geodata").canonicalFile
    internal var publish: (Path, Path) -> Unit = { source, target ->
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    }

    init { require(maxBytes in 1..64L * 1024 * 1024) }

    fun install(kind: GeoDataKind, input: InputStream): List<String> = synchronized(lock) {
        Files.createDirectories(directory.toPath())
        val temporary = Files.createTempFile(directory.toPath(), ".import-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                val buffer = ByteArray(8192)
                var size = 0L
                while (true) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes - size + 1).toInt())
                    if (count < 0) break
                    if (count == 0) throw IOException("Input made no progress")
                    size += count
                    if (size > maxBytes) throw IOException("GeoData exceeds $maxBytes bytes")
                    output.write(buffer, 0, count)
                }
                if (size == 0L) throw IOException("Empty GeoData")
                output.fd.sync()
            }
            val groups = catalog(kind, temporary.toFile())
            publish(temporary, File(directory, kind.fileName).toPath())
            groups
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** Revalidates persisted bytes, including after process restart. null means absent. */
    fun groups(kind: GeoDataKind): List<String>? = synchronized(lock) {
        val file = File(directory, kind.fileName)
        if (!file.exists()) null else catalog(kind, file)
    }

    private fun catalog(kind: GeoDataKind, file: File): List<String> {
        if (file.length() !in 1..maxBytes) throw IOException("Invalid GeoData size")
        val groups = validator.validate(kind, file).toList()
        if (groups.isEmpty() || groups.any { !it.matches(Regex("[a-z0-9_!\\-]{1,256}")) } ||
            groups.distinct().size != groups.size) throw IOException("Invalid GeoData catalog")
        return Collections.unmodifiableList(groups.sorted())
    }

    private companion object { val lock = Any() }
}
