package com.oconeco.spring_search_tempo.base.service

import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Per-entry enumeration of archive files (zip / tar / tar.gz / 7z / jar). Issue #118.
 *
 * Each archive entry surfaces as an [ArchiveEntry] with the inner path, size, and
 * mtime. Callers pair the inner path with the archive's URI to build the synthetic
 * `file:///path/to/archive.zip!/inner/file.txt` URI before persisting per-entry
 * FSFile rows.
 *
 * Format dispatch is purely extension-based today. That matches issue scope and
 * avoids paying the cost of a magic-byte probe on every file the crawl touches —
 * archive extensions are the gate, not arbitrary content. Unrecognised extensions
 * return an empty sequence (caller should treat the file as a regular blob).
 *
 * Password-protected and encrypted archives are out of scope (issue #118); failures
 * surface as a logged warning and an empty sequence so the crawl keeps going.
 */
@Service
class ArchiveEnumerationService {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Metadata for a single archive entry. */
    data class ArchiveEntry(
        val entryPath: String,
        val size: Long,
        val lastModified: Instant?,
        val isDirectory: Boolean
    )

    /**
     * Recognise an archive by extension. Returns the canonical kind or null when
     * the extension isn't one of the supported formats.
     */
    fun detectKind(path: Path): ArchiveKind? = detectKind(path.fileName?.toString())

    fun detectKind(name: String?): ArchiveKind? {
        if (name == null) return null
        val lower = name.lowercase()
        return when {
            lower.endsWith(".zip") -> ArchiveKind.ZIP
            lower.endsWith(".jar") -> ArchiveKind.JAR
            lower.endsWith(".7z") -> ArchiveKind.SEVEN_Z
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> ArchiveKind.TAR_GZ
            lower.endsWith(".tar") -> ArchiveKind.TAR
            else -> null
        }
    }

    /**
     * Enumerate the entries of an archive. Returns an empty sequence for unrecognised
     * extensions or when the archive can't be opened (encrypted, corrupt, unreadable).
     *
     * The returned sequence is eager — it materialises into a list internally so the
     * underlying input stream can be closed before the caller iterates. That avoids
     * the "iterator outlives the stream" footgun at the cost of holding all entry
     * metadata in memory (not the bytes — those are read on demand via
     * [extractEntryStream]).
     */
    fun enumerateEntries(archivePath: Path): Sequence<ArchiveEntry> {
        val kind = detectKind(archivePath)
        if (kind == null) {
            log.debug("Not a recognised archive extension, skipping enumeration: {}", archivePath)
            return emptySequence()
        }
        return try {
            collectEntries(archivePath, kind).asSequence()
        } catch (e: Exception) {
            log.warn("Failed to enumerate archive {} ({}): {}", archivePath, kind, e.message)
            emptySequence()
        }
    }

    private fun collectEntries(archivePath: Path, kind: ArchiveKind): List<ArchiveEntry> {
        val out = mutableListOf<ArchiveEntry>()
        when (kind) {
            ArchiveKind.SEVEN_Z -> {
                // 7z requires random-access; commons-compress exposes its own reader
                SevenZFile.builder().setPath(archivePath).get().use { sevenZ ->
                    while (true) {
                        val entry = sevenZ.nextEntry ?: break
                        out += ArchiveEntry(
                            entryPath = normaliseEntryPath(entry.name),
                            size = entry.size,
                            lastModified = if (entry.hasLastModifiedDate) entry.lastModifiedDate?.toInstant() else null,
                            isDirectory = entry.isDirectory
                        )
                    }
                }
            }
            ArchiveKind.ZIP, ArchiveKind.JAR -> {
                Files.newInputStream(archivePath).use { fis ->
                    ZipArchiveInputStream(BufferedInputStream(fis)).use { zip ->
                        drainCommonsArchive(zip, out)
                    }
                }
            }
            ArchiveKind.TAR -> {
                Files.newInputStream(archivePath).use { fis ->
                    TarArchiveInputStream(BufferedInputStream(fis)).use { tar ->
                        drainCommonsArchive(tar, out)
                    }
                }
            }
            ArchiveKind.TAR_GZ -> {
                Files.newInputStream(archivePath).use { fis ->
                    GzipCompressorInputStream(BufferedInputStream(fis)).use { gz ->
                        TarArchiveInputStream(gz).use { tar ->
                            drainCommonsArchive(tar, out)
                        }
                    }
                }
            }
        }
        return out
    }

    private fun drainCommonsArchive(ais: ArchiveInputStream<*>, out: MutableList<ArchiveEntry>) {
        while (true) {
            val entry = ais.nextEntry ?: break
            if (!ais.canReadEntryData(entry)) {
                log.debug("Skipping unreadable archive entry: {}", entry.name)
                continue
            }
            out += ArchiveEntry(
                entryPath = normaliseEntryPath(entry.name),
                size = entry.size,
                lastModified = entry.lastModifiedDate?.toInstant(),
                isDirectory = entry.isDirectory
            )
        }
    }

    /**
     * Read a single entry's bytes by streaming the archive again. This is intentionally
     * not held open between calls — archive crawls are sequential per file, and keeping
     * a handle alive across the chunk-write boundary risks resource leaks. Callers that
     * need many entries' bytes should batch their work.
     *
     * Returns null if the entry can't be found (e.g. archive was rewritten between
     * enumeration and read, or the entry path doesn't match any entry).
     */
    fun openEntryStream(archivePath: Path, entryPath: String): InputStream? {
        val kind = detectKind(archivePath) ?: return null
        val target = normaliseEntryPath(entryPath)
        return try {
            when (kind) {
                ArchiveKind.SEVEN_Z -> openSevenZEntry(archivePath, target)
                ArchiveKind.ZIP, ArchiveKind.JAR -> openCommonsEntry(
                    Files.newInputStream(archivePath),
                    target
                ) { ZipArchiveInputStream(it) }
                ArchiveKind.TAR -> openCommonsEntry(
                    Files.newInputStream(archivePath),
                    target
                ) { TarArchiveInputStream(it) }
                ArchiveKind.TAR_GZ -> openCommonsEntry(
                    GzipCompressorInputStream(BufferedInputStream(Files.newInputStream(archivePath))),
                    target
                ) { TarArchiveInputStream(it) }
            }
        } catch (e: IOException) {
            log.warn("Failed to open entry {} in archive {}: {}", entryPath, archivePath, e.message)
            null
        }
    }

    private fun openSevenZEntry(archivePath: Path, target: String): InputStream? {
        // 7z entries can only be read sequentially through the SevenZFile API,
        // and the entry InputStream is only valid while the SevenZFile is open.
        // Materialise into a ByteArrayInputStream so the caller can close at leisure.
        SevenZFile.builder().setPath(archivePath).get().use { sevenZ ->
            while (true) {
                val entry = sevenZ.nextEntry ?: return null
                if (!entry.isDirectory && normaliseEntryPath(entry.name) == target) {
                    val size = entry.size.toInt().coerceAtLeast(0)
                    val buf = ByteArray(size)
                    var read = 0
                    while (read < size) {
                        val n = sevenZ.read(buf, read, size - read)
                        if (n <= 0) break
                        read += n
                    }
                    return buf.copyOf(read).inputStream()
                }
            }
        }
        return null
    }

    private fun openCommonsEntry(
        underlying: InputStream,
        target: String,
        wrap: (InputStream) -> ArchiveInputStream<*>
    ): InputStream? {
        val buffered = BufferedInputStream(underlying)
        val ais = wrap(buffered)
        try {
            while (true) {
                val entry = ais.nextEntry ?: break
                if (!entry.isDirectory && normaliseEntryPath(entry.name) == target) {
                    // Hand the open ArchiveInputStream to the caller; closing it cascades.
                    return ais
                }
            }
        } catch (e: IOException) {
            ais.close()
            throw e
        }
        ais.close()
        return null
    }

    /**
     * Build the synthetic jar-style URI for an archive entry. The outer URI may
     * already be a synthetic archive URI itself (nested archive), in which case
     * `!` separators stack.
     */
    fun buildEntryUri(archiveUri: String, entryPath: String): String =
        "$archiveUri!/${normaliseEntryPath(entryPath)}"

    /**
     * Count `!` separators in a URI to determine archive recursion depth.
     * A top-level file has depth 0; an entry of a top-level archive has depth 1.
     */
    fun depthOf(uri: String): Int = uri.count { it == '!' }

    private fun normaliseEntryPath(raw: String): String =
        raw.removePrefix("./").removePrefix("/").trimEnd('/')

    enum class ArchiveKind { ZIP, JAR, TAR, TAR_GZ, SEVEN_Z }
}
