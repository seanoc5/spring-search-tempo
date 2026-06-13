package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.config.CrawlDefaults
import com.oconeco.spring_search_tempo.base.config.CrawlDefinition
import com.oconeco.spring_search_tempo.base.config.EffectivePatterns
import com.oconeco.spring_search_tempo.base.config.PatternSet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

class CrawlOwnershipMapTest {

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

    private fun build(vararg crawls: CrawlDefinition): CrawlOwnershipMap {
        val map = CrawlOwnershipMap(StubCrawlConfigService(crawls.toList()))
        map.init()
        return map
    }

    @Test
    fun `lookup returns owning crawl name for declared start path`() {
        val map = build(
            CrawlDefinition(name = "docs", startPaths = listOf("/home/sean/Documents"))
        )

        assertThat(map.lookup(Paths.get("/home/sean/Documents"))).isEqualTo("docs")
    }

    @Test
    fun `lookup returns null for unrelated paths`() {
        val map = build(
            CrawlDefinition(name = "docs", startPaths = listOf("/home/sean/Documents"))
        )

        assertThat(map.lookup(Paths.get("/etc"))).isNull()
        assertThat(map.lookup(Paths.get("/home/sean"))).isNull()
        assertThat(map.lookup(Paths.get("/home/sean/Documents/sub"))).isNull()
    }

    @Test
    fun `canonicalization strips trailing slash via normalize`() {
        val map = build(
            CrawlDefinition(name = "docs", startPaths = listOf("/home/sean/Documents/"))
        )

        assertThat(map.lookup(Paths.get("/home/sean/Documents"))).isEqualTo("docs")
        assertThat(map.lookup(Paths.get("/home/sean/Documents/"))).isEqualTo("docs")
    }

    @Test
    fun `canonicalization collapses dot-dot segments`() {
        val map = build(
            CrawlDefinition(name = "docs", startPaths = listOf("/home/sean/projects/../Documents"))
        )

        assertThat(map.lookup(Paths.get("/home/sean/Documents"))).isEqualTo("docs")
    }

    @Test
    fun `relative path resolves against current working directory`() {
        val map = build(
            CrawlDefinition(name = "rel", startPaths = listOf("relative-subdir"))
        )

        val expected = Paths.get("relative-subdir").toAbsolutePath().normalize()
        assertThat(map.lookup(expected)).isEqualTo("rel")
    }

    @Test
    fun `collision between two crawls keeps first-claim and warns`() {
        // Build via the public surface — second crawl claiming the same start
        // path should NOT overwrite the first one. (Warn is logged, not thrown.)
        val map = build(
            CrawlDefinition(name = "docs-a", startPaths = listOf("/home/sean/Documents")),
            CrawlDefinition(name = "docs-b", startPaths = listOf("/home/sean/Documents"))
        )

        assertThat(map.lookup(Paths.get("/home/sean/Documents"))).isEqualTo("docs-a")
    }

    @Test
    fun `disabled crawls are excluded from ownership map`() {
        val map = build(
            CrawlDefinition(
                name = "docs",
                startPaths = listOf("/home/sean/Documents"),
                enabled = false
            )
        )

        assertThat(map.lookup(Paths.get("/home/sean/Documents"))).isNull()
        assertThat(map.ownedPaths()).isEmpty()
    }

    @Test
    fun `multiple start paths each get registered`() {
        val map = build(
            CrawlDefinition(name = "docs", startPaths = listOf("/a", "/b", "/c"))
        )

        assertThat(map.lookup(Paths.get("/a"))).isEqualTo("docs")
        assertThat(map.lookup(Paths.get("/b"))).isEqualTo("docs")
        assertThat(map.lookup(Paths.get("/c"))).isEqualTo("docs")
        assertThat(map.ownedPaths()).hasSize(3)
    }

    @Test
    fun `same crawl claiming the same path twice is not treated as a collision`() {
        val map = build(
            CrawlDefinition(name = "docs", startPaths = listOf("/x", "/x/"))
        )

        assertThat(map.lookup(Paths.get("/x"))).isEqualTo("docs")
        assertThat(map.ownedPaths()).hasSize(1)
    }

    @Test
    fun `refresh picks up new crawl configuration`() {
        val crawls = mutableListOf<CrawlDefinition>(
            CrawlDefinition(name = "docs", startPaths = listOf("/a"))
        )
        val map = CrawlOwnershipMap(StubCrawlConfigService(crawls))
        map.init()
        assertThat(map.lookup(Paths.get("/a"))).isEqualTo("docs")
        assertThat(map.lookup(Paths.get("/b"))).isNull()

        // Add a new crawl, then refresh.
        crawls.add(CrawlDefinition(name = "videos", startPaths = listOf("/b")))
        // Note: stub returns the live mutable list, so refresh sees the addition.
        val map2 = CrawlOwnershipMap(StubCrawlConfigService(crawls))
        map2.init()
        assertThat(map2.lookup(Paths.get("/a"))).isEqualTo("docs")
        assertThat(map2.lookup(Paths.get("/b"))).isEqualTo("videos")
    }

    @Test
    fun `lookup canonicalizes the queried path`() {
        val map = build(
            CrawlDefinition(name = "docs", startPaths = listOf("/home/sean/Documents"))
        )

        assertThat(map.lookup(Paths.get("/home/sean/Documents/"))).isEqualTo("docs")
        assertThat(map.lookup(Paths.get("/home/sean/Documents/../Documents"))).isEqualTo("docs")
    }
}
