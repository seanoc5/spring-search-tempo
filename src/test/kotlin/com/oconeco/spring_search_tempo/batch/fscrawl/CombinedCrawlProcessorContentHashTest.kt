package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.config.EffectivePatterns
import com.oconeco.spring_search_tempo.base.config.PatternSet
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.FSFolder
import com.oconeco.spring_search_tempo.base.model.FSFolderDTO
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import com.oconeco.spring_search_tempo.base.service.FSFileMapper
import com.oconeco.spring_search_tempo.base.service.FSFolderMapper
import com.oconeco.spring_search_tempo.base.service.PatternMatchingService
import com.oconeco.spring_search_tempo.base.service.TextExtractionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyCollection
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.nio.file.Files
import java.nio.file.Path

/**
 * Issue #119: byte-identical duplicate detection.
 *
 * Verifies that CombinedCrawlProcessor computes a stable SHA-256 content hash
 * for every INDEX-class file and leaves LOCATE files' hash null. This is the
 * file-level acceptance criterion for the dedup feature — two files with the
 * same bytes in different paths must produce the same `contentHash`.
 *
 * The processor is exercised directly (no Spring context, no DB) with a real
 * TextExtractionService and PatternMatchingService, since the invariant lives
 * at the processor/service boundary, not at the persistence layer.
 */
class CombinedCrawlProcessorContentHashTest {

    private fun anyFolder(): FSFolder {
        ArgumentMatchers.any(FSFolder::class.java)
        return FSFolder()
    }

    private fun anyFolderDto(): FSFolderDTO {
        ArgumentMatchers.any(FSFolderDTO::class.java)
        return FSFolderDTO()
    }

    private fun newProcessor(
        tempDir: Path,
        folderPatterns: PatternSet,
        filePatterns: PatternSet = PatternSet(),
    ): CombinedCrawlProcessor {
        val folderRepository = mock(FSFolderRepository::class.java)
        val fileRepository = mock(FSFileRepository::class.java)
        val folderMapper = mock(FSFolderMapper::class.java)
        val fileMapper = mock(FSFileMapper::class.java)

        `when`(folderRepository.findByUri(anyString())).thenReturn(null)
        `when`(folderMapper.updateFSFolderDTO(anyFolder(), anyFolderDto()))
            .thenAnswer { it.arguments[1] as FSFolderDTO }
        `when`(fileRepository.findByUriIn(anyCollection())).thenReturn(emptyList())

        return CombinedCrawlProcessor(
            startPaths = listOf(tempDir),
            effectivePatterns = EffectivePatterns(
                folderPatterns = folderPatterns,
                filePatterns = filePatterns
            ),
            folderRepository = folderRepository,
            fileRepository = fileRepository,
            folderMapper = folderMapper,
            fileMapper = fileMapper,
            patternMatchingService = PatternMatchingService(),
            textExtractionService = TextExtractionService()
        )
    }

    @Test
    fun `two identical files in different paths produce identical contentHash`(@TempDir tempDir: Path) {
        val payload = "Spring Search Tempo — issue #119 dedup smoke test\n"
        val a = Files.write(tempDir.resolve("a.txt"), payload.toByteArray())
        val b = Files.write(tempDir.resolve("b.txt"), payload.toByteArray())

        // Folder pattern .* → INDEX, so files inherit INDEX from the folder and
        // exercise the text-extraction-with-hash path.
        val processor = newProcessor(
            tempDir = tempDir,
            folderPatterns = PatternSet(index = listOf(".*"))
        )

        val result = processor.process(CombinedCrawlItem(directory = tempDir, files = listOf(a, b)))

        assertThat(result).isNotNull
        val files = result!!.files
        assertThat(files).hasSize(2)
        files.forEach { dto ->
            assertThat(dto.analysisStatus).isEqualTo(AnalysisStatus.INDEX)
            assertThat(dto.contentHash)
                .describedAs("INDEX-class files must be hashed during text extraction")
                .isNotNull()
                .hasSize(64)
        }
        assertThat(files[0].contentHash)
            .describedAs("Two byte-identical files in different paths must share a contentHash")
            .isEqualTo(files[1].contentHash)
    }

    @Test
    fun `LOCATE files are not hashed`(@TempDir tempDir: Path) {
        val file = Files.write(tempDir.resolve("located.txt"), "some content".toByteArray())

        // Default PatternSet leaves folder inheriting LOCATE → files inherit
        // LOCATE → text-extraction path is skipped → no hash should be set.
        val processor = newProcessor(
            tempDir = tempDir,
            folderPatterns = PatternSet()
        )

        val result = processor.process(CombinedCrawlItem(directory = tempDir, files = listOf(file)))

        assertThat(result).isNotNull
        val dto = result!!.files.single()
        assertThat(dto.analysisStatus).isEqualTo(AnalysisStatus.LOCATE)
        assertThat(dto.contentHash)
            .describedAs("LOCATE files must not be hashed — hashing requires opening the file")
            .isNull()
    }
}
