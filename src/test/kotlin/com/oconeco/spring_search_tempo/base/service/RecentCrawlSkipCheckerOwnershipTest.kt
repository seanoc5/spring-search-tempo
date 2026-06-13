package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.config.CrawlDefaults
import com.oconeco.spring_search_tempo.base.config.CrawlDefinition
import com.oconeco.spring_search_tempo.base.config.EffectivePatterns
import com.oconeco.spring_search_tempo.base.config.PatternSet
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.nio.file.Paths
import java.time.OffsetDateTime

// Raw Mockito + Kotlin: ArgumentMatchers.any() returns null which trips
// Kotlin's non-null parameter check. This helper supplies a Kotlin-safe
// "any" by suppressing the resulting null via the unchecked cast pattern.
@Suppress("UNCHECKED_CAST")
private fun <T> anyNonNull(): T {
    ArgumentMatchers.any<T>()
    return null as T
}

class RecentCrawlSkipCheckerOwnershipTest {

    private class StubCrawlConfigService(
        private val crawls: List<CrawlDefinition>
    ) : CrawlConfigService {
        override fun getAllCrawls() = crawls
        override fun getEnabledCrawls() = crawls.filter { it.enabled }
        override fun getCrawlByName(name: String) = crawls.firstOrNull { it.name == name }
        override fun getDefaults() = CrawlDefaults()
        override fun getEffectivePatterns(crawl: CrawlDefinition) = EffectivePatterns(
            folderPatterns = PatternSet(),
            filePatterns = PatternSet()
        )
    }

    private fun ownershipMap(vararg crawls: CrawlDefinition): CrawlOwnershipMap {
        val map = CrawlOwnershipMap(StubCrawlConfigService(crawls.toList()))
        map.init()
        return map
    }

    @Test
    fun `ownership short-circuits without a DB query when another crawl owns the path`() {
        val repo = mock(FSFolderRepository::class.java)
        val map = ownershipMap(
            CrawlDefinition(name = "parent", startPaths = listOf("/home/sean")),
            CrawlDefinition(name = "docs", startPaths = listOf("/home/sean/Documents"))
        )

        val checker = RecentCrawlSkipChecker(
            fsFolderRepository = repo,
            currentCrawlConfigId = 1L,
            currentCrawlName = "parent",
            ownershipMap = map
        )

        val result = checker.shouldSkipFolder(Paths.get("/home/sean/Documents"))

        assertThat(result).isInstanceOf(RecentCrawlCheckResult.OwnedByOtherCrawl::class.java)
        assertThat((result as RecentCrawlCheckResult.OwnedByOtherCrawl).otherCrawlName).isEqualTo("docs")
        // No DB hit when ownership decided it.
        verify(repo, never()).findRecentCrawlConfigRootInfo(anyString(), anyNonNull<OffsetDateTime>())
    }

    @Test
    fun `ownership does not short-circuit when same crawl owns the path`() {
        val repo = mock(FSFolderRepository::class.java)
        `when`(repo.findRecentCrawlConfigRootInfo(anyString(), anyNonNull<OffsetDateTime>())).thenReturn(null)
        val map = ownershipMap(
            CrawlDefinition(name = "docs", startPaths = listOf("/home/sean/Documents"))
        )

        val checker = RecentCrawlSkipChecker(
            fsFolderRepository = repo,
            currentCrawlConfigId = 1L,
            currentCrawlName = "docs",
            ownershipMap = map
        )

        val result = checker.shouldSkipFolder(Paths.get("/home/sean/Documents"))

        // Falls through to recency layer; repo returned null → NotRecentlyCrawled.
        assertThat(result).isEqualTo(RecentCrawlCheckResult.NotRecentlyCrawled)
        verify(repo).findRecentCrawlConfigRootInfo(anyString(), anyNonNull<OffsetDateTime>())
    }

    @Test
    fun `falls back to recency query when ownership map has no match`() {
        val repo = mock(FSFolderRepository::class.java)
        val timestamp = OffsetDateTime.now()
        `when`(repo.findRecentCrawlConfigRootInfo(anyString(), anyNonNull<OffsetDateTime>()))
            .thenReturn(arrayOf<Any?>(42L, "INDEX", timestamp))

        val map = ownershipMap(
            CrawlDefinition(name = "docs", startPaths = listOf("/home/sean/Documents"))
        )

        val checker = RecentCrawlSkipChecker(
            fsFolderRepository = repo,
            currentCrawlConfigId = 1L,
            currentCrawlName = "videos",
            ownershipMap = map
        )

        // /tmp/other isn't owned by any crawl, so recency layer runs and reports skip.
        val result = checker.shouldSkipFolder(Paths.get("/tmp/other"))

        assertThat(result).isInstanceOf(RecentCrawlCheckResult.SkipSubtree::class.java)
        result as RecentCrawlCheckResult.SkipSubtree
        assertThat(result.otherCrawlConfigId).isEqualTo(42L)
        assertThat(result.otherAnalysisStatus).isEqualTo(AnalysisStatus.INDEX)
    }

    @Test
    fun `null ownership params preserve prior behavior (recency only)`() {
        val repo = mock(FSFolderRepository::class.java)
        `when`(repo.findRecentCrawlConfigRootInfo(anyString(), anyNonNull<OffsetDateTime>())).thenReturn(null)

        val checker = RecentCrawlSkipChecker(
            fsFolderRepository = repo,
            currentCrawlConfigId = 1L
            // currentCrawlName / ownershipMap left as defaults (null)
        )

        val result = checker.shouldSkipFolder(Paths.get("/anywhere"))

        assertThat(result).isEqualTo(RecentCrawlCheckResult.NotRecentlyCrawled)
        // Recency query did run.
        verify(repo).findRecentCrawlConfigRootInfo(anyString(), anyNonNull<OffsetDateTime>())
    }

    @Test
    fun `ownership result is cached - repeat lookup avoids the map`() {
        val repo = mock(FSFolderRepository::class.java)
        val map = ownershipMap(
            CrawlDefinition(name = "parent", startPaths = listOf("/home/sean")),
            CrawlDefinition(name = "docs", startPaths = listOf("/home/sean/Documents"))
        )

        val checker = RecentCrawlSkipChecker(
            fsFolderRepository = repo,
            currentCrawlConfigId = 1L,
            currentCrawlName = "parent",
            ownershipMap = map
        )

        val first = checker.shouldSkipFolder(Paths.get("/home/sean/Documents"))
        val second = checker.shouldSkipFolder(Paths.get("/home/sean/Documents"))

        assertThat(first).isEqualTo(second)
        // Still no DB hit across multiple lookups.
        verify(repo, never()).findRecentCrawlConfigRootInfo(anyString(), anyNonNull<OffsetDateTime>())
    }
}
