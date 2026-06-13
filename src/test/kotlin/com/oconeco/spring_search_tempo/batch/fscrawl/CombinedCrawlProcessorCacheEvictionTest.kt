package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.config.EffectivePatterns
import com.oconeco.spring_search_tempo.base.config.PatternSet
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.FSFile
import com.oconeco.spring_search_tempo.base.domain.FSFolder
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import com.oconeco.spring_search_tempo.base.service.FSFileMapper
import com.oconeco.spring_search_tempo.base.service.FSFolderMapper
import com.oconeco.spring_search_tempo.base.service.PatternMatchingService
import com.oconeco.spring_search_tempo.base.service.TextExtractionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.nio.file.Paths

/**
 * Issue #96: the previous ConcurrentHashMap-based caches in CombinedCrawlProcessor
 * were unbounded and would grow without limit on a full-system crawl (millions of
 * files). The Caffeine-backed replacements honour `maximumSize`, so under sustained
 * load they evict instead of leaking.
 *
 * This test drives that invariant directly against the cache. We deliberately do
 * not go through the full processor flow: that would conflate cache behaviour with
 * mapper/repository wiring and force a Path-per-entry, when what we actually care
 * about is "after N unique inserts with N >> maximumSize, the bound holds."
 */
class CombinedCrawlProcessorCacheEvictionTest {

    private fun newProcessor(maxCacheSize: Long): CombinedCrawlProcessor =
        CombinedCrawlProcessor(
            startPaths = listOf(Paths.get("/tmp")),
            effectivePatterns = EffectivePatterns(PatternSet(), PatternSet()),
            folderRepository = mock(FSFolderRepository::class.java),
            fileRepository = mock(FSFileRepository::class.java),
            folderMapper = mock(FSFolderMapper::class.java),
            fileMapper = mock(FSFileMapper::class.java),
            patternMatchingService = PatternMatchingService(),
            textExtractionService = mock(TextExtractionService::class.java),
            forceFullRecrawl = false,
            meterRegistry = null,
            maxCacheSize = maxCacheSize
        )

    @Test
    fun `fileCache evicts when filled past maximumSize`() {
        val maxSize = 100L
        val processor = newProcessor(maxSize)

        // Fill well past the cap. Caffeine's Window-TinyLFU is approximate, so we
        // don't expect estimatedSize == maxSize exactly — only that it's bounded
        // and far below the number of inserts (i.e. eviction actually happened).
        val inserts = 10_000
        repeat(inserts) { i ->
            processor.fileCache.put("file-uri-$i", mock(FSFile::class.java))
        }
        processor.fileCache.cleanUp()

        val size = processor.fileCache.estimatedSize()
        assertThat(size)
            .`as`("fileCache should stay bounded near maxCacheSize after $inserts inserts")
            .isLessThanOrEqualTo(maxSize + (maxSize / 4))   // allow ~25% slack for TinyLFU window
        assertThat(size)
            .`as`("fileCache should evict — size must be far below total inserts")
            .isLessThan(inserts.toLong())
    }

    @Test
    fun `folderCache evicts when filled past maximumSize`() {
        val maxSize = 50L
        val processor = newProcessor(maxSize)

        val inserts = 5_000
        repeat(inserts) { i ->
            processor.folderCache.put("folder-uri-$i", mock(FSFolder::class.java))
        }
        processor.folderCache.cleanUp()

        assertThat(processor.folderCache.estimatedSize())
            .isLessThanOrEqualTo(maxSize + (maxSize / 4))
    }

    @Test
    fun `parentStatusCache and skippedFolderCache stay bounded under sustained load`() {
        val maxSize = 200L
        val processor = newProcessor(maxSize)

        val inserts = 20_000
        repeat(inserts) { i ->
            processor.parentStatusCache.put("dir-$i", AnalysisStatus.INDEX)
            processor.skippedFolderCache.put("skip-$i", true)
        }
        processor.parentStatusCache.cleanUp()
        processor.skippedFolderCache.cleanUp()

        assertThat(processor.parentStatusCache.estimatedSize())
            .`as`("parentStatusCache bounded")
            .isLessThanOrEqualTo(maxSize + (maxSize / 4))
        assertThat(processor.skippedFolderCache.estimatedSize())
            .`as`("skippedFolderCache bounded")
            .isLessThanOrEqualTo(maxSize + (maxSize / 4))
    }

    @Test
    fun `clearCaches empties all four caches`() {
        val processor = newProcessor(maxCacheSize = 1_000L)

        repeat(50) { i ->
            processor.fileCache.put("f-$i", mock(FSFile::class.java))
            processor.folderCache.put("d-$i", mock(FSFolder::class.java))
            processor.parentStatusCache.put("p-$i", AnalysisStatus.INDEX)
            processor.skippedFolderCache.put("s-$i", true)
        }

        processor.clearCaches()
        processor.fileCache.cleanUp()
        processor.folderCache.cleanUp()
        processor.parentStatusCache.cleanUp()
        processor.skippedFolderCache.cleanUp()

        assertThat(processor.fileCache.estimatedSize()).isZero
        assertThat(processor.folderCache.estimatedSize()).isZero
        assertThat(processor.parentStatusCache.estimatedSize()).isZero
        assertThat(processor.skippedFolderCache.estimatedSize()).isZero
    }
}
