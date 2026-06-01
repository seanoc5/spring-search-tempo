package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.config.EffectivePatterns
import com.oconeco.spring_search_tempo.base.config.PatternSet
import com.oconeco.spring_search_tempo.base.domain.FSFolder
import com.oconeco.spring_search_tempo.base.model.FSFolderDTO
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import com.oconeco.spring_search_tempo.base.service.FSFileMapper
import com.oconeco.spring_search_tempo.base.service.FSFolderMapper
import com.oconeco.spring_search_tempo.base.service.PatternMatchingService
import com.oconeco.spring_search_tempo.base.service.TextExtractionService
import org.junit.jupiter.api.Assertions.assertEquals
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
 * Verifies that CombinedCrawlProcessor batches its existing-file lookups into a
 * single `findByUriIn` call per chunk instead of issuing one `findByUri` per file.
 *
 * The acceptance criterion in issue #7 asks that for a chunk of N files the DB
 * sees fewer than N queries. We assert that at the repository boundary — the
 * single layer through which the processor reaches the database — which is the
 * tightest expression of the same invariant and stays robust against unrelated
 * Hibernate-internal traffic.
 */
class CombinedCrawlProcessorBatchLookupTest {

    // Mockito's any(Class) returns null, which trips Kotlin's call-site null-check
    // when the mocked method's parameter type is a non-null Kotlin type. These
    // helpers register the matcher on Mockito's matcher stack the normal way, but
    // hand the call site a real non-null instance — Mockito ignores the returned
    // value and uses the registered matcher.
    private fun anyFolder(): FSFolder {
        ArgumentMatchers.any(FSFolder::class.java)
        return FSFolder()
    }

    private fun anyFolderDto(): FSFolderDTO {
        ArgumentMatchers.any(FSFolderDTO::class.java)
        return FSFolderDTO()
    }

    @Test
    fun `processes a chunk of N files with a single batched URI lookup`(@TempDir tempDir: Path) {
        val chunkSize = 10
        val files = (1..chunkSize).map { i ->
            Files.createFile(tempDir.resolve("file-$i.txt"))
        }

        val folderRepository = mock(FSFolderRepository::class.java)
        val fileRepository = mock(FSFileRepository::class.java)
        val folderMapper = mock(FSFolderMapper::class.java)
        val fileMapper = mock(FSFileMapper::class.java)
        val textExtractionService = mock(TextExtractionService::class.java)
        // Real service with empty PatternSets — folder defaults to LOCATE, files
        // inherit LOCATE from parent. That keeps both on the "metadata only"
        // branch so no text extraction runs and we stay focused on lookup shape.
        val patternMatchingService = PatternMatchingService()

        // Folder lookup returns a bare FSFolder (fsLastModified=null) so the processor
        // takes the "exists, will update" path — that path doesn't issue any extra
        // queries and still runs file processing. The cache stays populated (no null
        // values, which ConcurrentHashMap rejects). Files are unseen in DB.
        `when`(folderRepository.findByUri(anyString())).thenReturn(FSFolder())
        `when`(folderMapper.updateFSFolderDTO(anyFolder(), anyFolderDto()))
            .thenAnswer { it.arguments[1] as FSFolderDTO }
        `when`(fileRepository.findByUriIn(anyCollection())).thenReturn(emptyList())

        val processor = CombinedCrawlProcessor(
            startPaths = listOf(tempDir),
            effectivePatterns = EffectivePatterns(PatternSet(), PatternSet()),
            folderRepository = folderRepository,
            fileRepository = fileRepository,
            folderMapper = folderMapper,
            fileMapper = fileMapper,
            patternMatchingService = patternMatchingService,
            textExtractionService = textExtractionService
        )

        val item = CombinedCrawlItem(directory = tempDir, files = files)
        val result = processor.process(item)

        // Sanity: the processor produced DTOs for every file in the chunk.
        assertEquals(chunkSize, result?.files?.size)

        // The fix: exactly one IN-clause query for the whole chunk, never the
        // per-URI findByUri the old loop fired.
        verify(fileRepository, times(1)).findByUriIn(anyCollection())
        verify(fileRepository, never()).findByUri(anyString())
    }
}
