package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.config.ArchiveConfiguration
import com.oconeco.spring_search_tempo.base.config.EffectivePatterns
import com.oconeco.spring_search_tempo.base.config.PatternSet
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.FSFolder
import com.oconeco.spring_search_tempo.base.model.FSFolderDTO
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import com.oconeco.spring_search_tempo.base.service.ArchiveEnumerationService
import com.oconeco.spring_search_tempo.base.service.FSFileMapper
import com.oconeco.spring_search_tempo.base.service.FSFolderMapper
import com.oconeco.spring_search_tempo.base.service.PatternMatchingService
import com.oconeco.spring_search_tempo.base.service.TextExtractionService
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyCollection
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.nio.file.Files
import java.nio.file.Path

/**
 * Issue #118: archives surface one FSFile row per entry with synthetic jar-style URIs,
 * and each entry runs through PatternMatchingService independently.
 */
class CombinedCrawlProcessorArchiveTest {

    private fun anyFolder(): FSFolder {
        ArgumentMatchers.any(FSFolder::class.java)
        return FSFolder()
    }

    private fun anyFolderDto(): FSFolderDTO {
        ArgumentMatchers.any(FSFolderDTO::class.java)
        return FSFolderDTO()
    }

    @Test
    fun `archive INDEX file yields one entry row per archive entry with synthetic URIs`(@TempDir tmp: Path) {
        val archive = tmp.resolve("sample.zip")
        writeSampleZip(archive)

        val processor = buildProcessor(
            tmp,
            // ANALYZE-level folder pushes the .zip onto the INDEX path so we exercise enumeration.
            // (LOCATE would skip extraction and we'd never call into the archive service.)
            filePatterns = PatternSet(index = listOf(".*\\.zip$"))
        )
        val item = CombinedCrawlItem(directory = tmp, files = listOf(archive))
        val result = processor.process(item)

        assertNotNull(result)
        val files = result!!.files
        // Outer archive row + 3 file entries (subdir/ dir entry is filtered out).
        assertEquals(4, files.size, "expected 1 outer archive row + 3 entry rows, got $files")

        val outer = files.first { it.uri == archive.toString() }
        assertEquals(AnalysisStatus.INDEX, outer.analysisStatus)

        val entries = files.filter { it.parentArchiveUri != null }
        assertEquals(3, entries.size)
        val expectedUris = setOf(
            "${archive}!/readme.txt",
            "${archive}!/src/code.kt",
            "${archive}!/subdir/notes.md"
        )
        assertEquals(expectedUris, entries.map { it.uri }.toSet())
        entries.forEach { assertEquals(archive.toString(), it.parentArchiveUri) }
        entries.forEach { assertEquals("ARCHIVE_ENTRY", it.type) }
    }

    @Test
    fun `SKIP-pattern entries inside archive get a row with no body`(@TempDir tmp: Path) {
        val archive = tmp.resolve("mixed.zip")
        ZipArchiveOutputStream(Files.newOutputStream(archive)).use { zos ->
            putEntry(zos, "keep.txt", "kept content")
            putEntry(zos, "ignored.tmp", "throwaway")
        }

        val processor = buildProcessor(
            tmp,
            filePatterns = PatternSet(
                skip = listOf(".*\\.tmp$"),
                index = listOf(".*\\.zip$")
            )
        )
        val result = processor.process(CombinedCrawlItem(directory = tmp, files = listOf(archive)))!!
        val entries = result.files.filter { it.parentArchiveUri != null }
        assertEquals(2, entries.size)
        val tmpEntry = entries.first { it.uri!!.endsWith("/ignored.tmp") }
        assertEquals(AnalysisStatus.SKIP, tmpEntry.analysisStatus)
        assertNull(tmpEntry.bodyText, "SKIP entries must not carry extracted text")
        val keepEntry = entries.first { it.uri!!.endsWith("/keep.txt") }
        assertEquals(AnalysisStatus.INDEX, keepEntry.analysisStatus)
        assertNotNull(keepEntry.bodyText, "INDEX entries should have extracted text")
        assertTrue(keepEntry.bodyText!!.contains("kept content"))
    }

    @Test
    fun `unchanged archive on second crawl does not re-enumerate`(@TempDir tmp: Path) {
        val archive = tmp.resolve("idempotent.zip")
        writeSampleZip(archive)

        val processor = buildProcessor(
            tmp,
            filePatterns = PatternSet(index = listOf(".*\\.zip$")),
            existingArchive = archive,
            existingArchiveSize = Files.size(archive),
            existingArchiveMtime = java.time.OffsetDateTime.ofInstant(
                Files.getLastModifiedTime(archive).toInstant(),
                java.time.ZoneOffset.UTC
            )
        )
        val result = processor.process(CombinedCrawlItem(directory = tmp, files = listOf(archive)))!!
        // Outer archive row is omitted because it's unchanged; per-entry rows must NOT appear.
        val entryRows = result.files.filter { it.parentArchiveUri != null }
        assertTrue(entryRows.isEmpty(), "unchanged archive must not re-enumerate, got $entryRows")
    }

    @Test
    fun `recursion cap blocks enumeration when parent depth would exceed limit`(@TempDir tmp: Path) {
        val archive = tmp.resolve("inner.zip")
        writeSampleZip(archive)

        // Outer-archive enumeration sits at entry-depth 1 (per issue #118 semantics:
        // outer = depth 1, once-nested = depth 2, twice-nested = depth 3). Setting
        // the cap to 0 forces a SKIP-with-reason at the outermost level so we can
        // assert the gate fires in a single-process test (without needing to
        // synthesise a nested-archive URI from disk).
        val processor = buildProcessor(
            tmp,
            filePatterns = PatternSet(index = listOf(".*\\.zip$")),
            archiveConfig = ArchiveConfiguration(maxRecursionDepth = 0)
        )

        val result = processor.process(CombinedCrawlItem(directory = tmp, files = listOf(archive)))!!
        val entryRows = result.files.filter { it.parentArchiveUri != null }
        assertTrue(entryRows.isEmpty(), "depth cap of 0 must prevent enumerating archive entries")
    }

    @Test
    fun `entry-count cap falls back to LOCATE-only treatment`(@TempDir tmp: Path) {
        val archive = tmp.resolve("big.zip")
        ZipArchiveOutputStream(Files.newOutputStream(archive)).use { zos ->
            for (i in 1..5) {
                putEntry(zos, "f$i.txt", "content $i")
            }
        }

        val processor = buildProcessor(
            tmp,
            filePatterns = PatternSet(index = listOf(".*\\.zip$")),
            archiveConfig = ArchiveConfiguration(maxExtractedEntries = 3)
        )
        val result = processor.process(CombinedCrawlItem(directory = tmp, files = listOf(archive)))!!
        val entryRows = result.files.filter { it.parentArchiveUri != null }
        assertTrue(entryRows.isEmpty(), "over-cap archives must skip per-entry rows")
    }

    private fun buildProcessor(
        startPath: Path,
        filePatterns: PatternSet = PatternSet(),
        existingArchive: Path? = null,
        existingArchiveSize: Long? = null,
        existingArchiveMtime: java.time.OffsetDateTime? = null,
        archiveConfig: ArchiveConfiguration = ArchiveConfiguration()
    ): CombinedCrawlProcessor {
        val folderRepository = mock(FSFolderRepository::class.java)
        val fileRepository = mock(FSFileRepository::class.java)
        val folderMapper = mock(FSFolderMapper::class.java)
        val fileMapper = mock(FSFileMapper::class.java)
        val patternMatchingService = PatternMatchingService()
        val textExtractionService = TextExtractionService()
        val archiveService = ArchiveEnumerationService()

        `when`(folderRepository.findByUri(anyString())).thenReturn(FSFolder())
        `when`(folderMapper.updateFSFolderDTO(anyFolder(), anyFolderDto()))
            .thenAnswer { it.arguments[1] as FSFolderDTO }

        if (existingArchive != null) {
            val existing = com.oconeco.spring_search_tempo.base.domain.FSFile().apply {
                id = 42L
                uri = existingArchive.toString()
                size = existingArchiveSize
                fsLastModified = existingArchiveMtime
            }
            `when`(fileRepository.findByUriIn(anyCollection())).thenReturn(listOf(existing))
        } else {
            `when`(fileRepository.findByUriIn(anyCollection())).thenReturn(emptyList())
        }

        return CombinedCrawlProcessor(
            startPaths = listOf(startPath),
            effectivePatterns = EffectivePatterns(PatternSet(), filePatterns),
            folderRepository = folderRepository,
            fileRepository = fileRepository,
            folderMapper = folderMapper,
            fileMapper = fileMapper,
            patternMatchingService = patternMatchingService,
            textExtractionService = textExtractionService,
            archiveService = archiveService,
            archiveConfig = archiveConfig
        )
    }

    private fun writeSampleZip(target: Path) {
        ZipArchiveOutputStream(Files.newOutputStream(target)).use { zos ->
            putEntry(zos, "readme.txt", "hello readme")
            putEntry(zos, "src/code.kt", "fun main() = println(\"hi\")")
            putEntry(zos, "subdir/notes.md", "# notes\n\nsome content")
            val dirEntry = ZipArchiveEntry("subdir/")
            zos.putArchiveEntry(dirEntry)
            zos.closeArchiveEntry()
        }
    }

    private fun putEntry(zos: ZipArchiveOutputStream, name: String, content: String) {
        val entry = ZipArchiveEntry(name)
        val bytes = content.toByteArray(Charsets.UTF_8)
        entry.size = bytes.size.toLong()
        zos.putArchiveEntry(entry)
        zos.write(bytes)
        zos.closeArchiveEntry()
    }
}
