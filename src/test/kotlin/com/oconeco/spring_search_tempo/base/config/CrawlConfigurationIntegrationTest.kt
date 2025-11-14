package com.oconeco.spring_search_tempo.base.config

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.service.CrawlConfigService
import com.oconeco.spring_search_tempo.base.service.PatternMatchingService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * Integration test for the complete crawl configuration system.
 * Verifies YAML loading, pattern merging, and pattern matching work together correctly.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Crawl Configuration Integration Tests")
class CrawlConfigurationIntegrationTest {

    @Autowired
    private lateinit var crawlConfigService: CrawlConfigService

    @Autowired
    private lateinit var patternMatchingService: PatternMatchingService

    @Autowired
    private lateinit var crawlConfiguration: CrawlConfiguration

    @Test
    @DisplayName("Should load crawl configuration from application.yml")
    fun testConfigurationLoaded() {
        val crawls = crawlConfigService.getAllCrawls()
        assertTrue(crawls.isNotEmpty(), "Should load at least one crawl definition")

        val defaults = crawlConfigService.getDefaults()
        assertNotNull(defaults, "Should load default settings")
        assertTrue(defaults.maxDepth > 0, "Default maxDepth should be positive")
    }

    @Test
    @DisplayName("Should find enabled crawls")
    fun testEnabledCrawls() {
        val enabledCrawls = crawlConfigService.getEnabledCrawls()
        assertTrue(enabledCrawls.isNotEmpty(), "Should have at least one enabled crawl")

        enabledCrawls.forEach { crawl ->
            assertTrue(crawl.enabled, "All returned crawls should be enabled")
            assertTrue(crawl.name.isNotEmpty(), "Crawl name should not be empty")
            assertTrue(crawl.startPaths.isNotEmpty(), "Start paths should not be empty")
        }
    }

    @Test
    @DisplayName("Should find crawl by name")
    fun testGetCrawlByName() {
        val workCrawl = crawlConfigService.getCrawlByName("WORK")
        assertNotNull(workCrawl, "Should find WORK crawl")
        assertEquals("WORK", workCrawl?.name)
        assertTrue(workCrawl?.startPaths?.contains("/opt/work") == true,
            "WORK crawl should contain /opt/work in start paths")
    }

    @Test
    @DisplayName("Should return null for non-existent crawl")
    fun testGetNonExistentCrawl() {
        val crawl = crawlConfigService.getCrawlByName("NON_EXISTENT")
        assertNull(crawl, "Should return null for non-existent crawl")
    }

    @Test
    @DisplayName("Should merge default patterns with crawl-specific patterns")
    fun testPatternMerging() {
        val workCrawl = crawlConfigService.getCrawlByName("WORK")
        assertNotNull(workCrawl, "WORK crawl should exist")

        val effectivePatterns = crawlConfigService.getEffectivePatterns(workCrawl!!)

        // SKIP patterns should be merged (defaults + crawl-specific)
        assertTrue(
            effectivePatterns.folderPatterns.skip.contains(".*/\\.git/.*"),
            "Should include default skip pattern for .git"
        )
        assertTrue(
            effectivePatterns.folderPatterns.skip.contains(".*/\\.gradle/.*"),
            "Should include default skip pattern for .gradle"
        )

        // Other patterns should be crawl-specific only
        assertTrue(
            effectivePatterns.filePatterns.index.isNotEmpty(),
            "Should have file index patterns"
        )
    }

    @Test
    @DisplayName("Should skip folders matching default patterns")
    fun testSkipDefaultFolderPatterns() {
        val workCrawl = crawlConfigService.getCrawlByName("WORK")!!
        val effectivePatterns = crawlConfigService.getEffectivePatterns(workCrawl)

        val gitFolderStatus = patternMatchingService.determineFolderAnalysisStatus(
            path = "/opt/work/project/.git/objects",
            patterns = effectivePatterns.folderPatterns,
            parentStatus = null
        )

        assertEquals(AnalysisStatus.SKIP, gitFolderStatus,
            ".git folders should be skipped based on default patterns")
    }

    @Test
    @DisplayName("Should skip files matching default patterns")
    fun testSkipDefaultFilePatterns() {
        val workCrawl = crawlConfigService.getCrawlByName("WORK")!!
        val effectivePatterns = crawlConfigService.getEffectivePatterns(workCrawl)

        val tmpFileStatus = patternMatchingService.determineFileAnalysisStatus(
            path = "/opt/work/project/file.tmp",
            filePatterns = effectivePatterns.filePatterns,
            parentFolderStatus = AnalysisStatus.INDEX
        )

        assertEquals(AnalysisStatus.SKIP, tmpFileStatus,
            ".tmp files should be skipped based on default patterns")
    }

    @Test
    @DisplayName("Should index files matching WORK crawl patterns")
    fun testWorkCrawlFileIndexing() {
        val workCrawl = crawlConfigService.getCrawlByName("WORK")!!
        val effectivePatterns = crawlConfigService.getEffectivePatterns(workCrawl)

        // Test Kotlin file
        val kotlinFileStatus = patternMatchingService.determineFileAnalysisStatus(
            path = "/opt/work/project/src/Main.kt",
            filePatterns = effectivePatterns.filePatterns,
            parentFolderStatus = AnalysisStatus.INDEX
        )
        assertEquals(AnalysisStatus.INDEX, kotlinFileStatus,
            "Kotlin files should be indexed in WORK crawl")

        // Test YAML file
        val yamlFileStatus = patternMatchingService.determineFileAnalysisStatus(
            path = "/opt/work/project/application.yml",
            filePatterns = effectivePatterns.filePatterns,
            parentFolderStatus = AnalysisStatus.INDEX
        )
        assertEquals(AnalysisStatus.INDEX, yamlFileStatus,
            "YAML files should be indexed in WORK crawl")
    }

    @Test
    @DisplayName("Should analyze files matching WORK crawl analyze patterns")
    fun testWorkCrawlFileAnalysis() {
        val workCrawl = crawlConfigService.getCrawlByName("WORK")!!
        val effectivePatterns = crawlConfigService.getEffectivePatterns(workCrawl)

        val markdownFileStatus = patternMatchingService.determineFileAnalysisStatus(
            path = "/opt/work/project/README.md",
            filePatterns = effectivePatterns.filePatterns,
            parentFolderStatus = AnalysisStatus.INDEX
        )

        assertEquals(AnalysisStatus.ANALYZE, markdownFileStatus,
            "Markdown files should be analyzed in WORK crawl")
    }

    @Test
    @DisplayName("Should handle USER_DOCUMENTS crawl configuration")
    fun testUserDocumentsCrawl() {
        val docsCrawl = crawlConfigService.getCrawlByName("USER_DOCUMENTS")
        assertNotNull(docsCrawl, "USER_DOCUMENTS crawl should exist")

        val effectivePatterns = crawlConfigService.getEffectivePatterns(docsCrawl!!)

        // Test PDF indexing
        val pdfStatus = patternMatchingService.determineFileAnalysisStatus(
            path = "${System.getProperty("user.home")}/Documents/report.pdf",
            filePatterns = effectivePatterns.filePatterns,
            parentFolderStatus = AnalysisStatus.INDEX
        )
        assertEquals(AnalysisStatus.ANALYZE, pdfStatus,
            "PDF files should be analyzed in USER_DOCUMENTS crawl")

        // Test text file analysis
        val txtStatus = patternMatchingService.determineFileAnalysisStatus(
            path = "${System.getProperty("user.home")}/Documents/notes.txt",
            filePatterns = effectivePatterns.filePatterns,
            parentFolderStatus = AnalysisStatus.INDEX
        )
        assertEquals(AnalysisStatus.ANALYZE, txtStatus,
            "Text files should be analyzed in USER_DOCUMENTS crawl")
    }

    @Test
    @DisplayName("Should handle USER_PICTURES crawl for locate-only")
    fun testUserPicturesCrawl() {
        val picturesCrawl = crawlConfigService.getCrawlByName("USER_PICTURES")
        assertNotNull(picturesCrawl, "USER_PICTURES crawl should exist")

        val effectivePatterns = crawlConfigService.getEffectivePatterns(picturesCrawl!!)

        val jpgStatus = patternMatchingService.determineFileAnalysisStatus(
            path = "${System.getProperty("user.home")}/Pictures/vacation.jpg",
            filePatterns = effectivePatterns.filePatterns,
            parentFolderStatus = AnalysisStatus.LOCATE
        )
        assertEquals(AnalysisStatus.LOCATE, jpgStatus,
            "Image files should be located (metadata only) in USER_PICTURES crawl")
    }

    @Test
    @DisplayName("Should use default maxDepth when not specified in crawl")
    fun testDefaultMaxDepth() {
        val defaults = crawlConfigService.getDefaults()
        val docsCrawl = crawlConfigService.getCrawlByName("USER_DOCUMENTS")!!

        val effectiveMaxDepth = docsCrawl.getMaxDepth(defaults)
        assertEquals(15, effectiveMaxDepth,
            "USER_DOCUMENTS should override default maxDepth to 15")
    }

    @Test
    @DisplayName("Should handle parallel crawl setting")
    fun testParallelSetting() {
        val defaults = crawlConfigService.getDefaults()
        val workCrawl = crawlConfigService.getCrawlByName("WORK")!!

        val isParallel = workCrawl.getParallel(defaults)
        assertTrue(isParallel, "WORK crawl should be configured for parallel processing")
    }

    @Test
    @DisplayName("Should handle disabled crawls")
    fun testDisabledCrawl() {
        val configCrawl = crawlConfigService.getCrawlByName("CONFIG")
        assertNotNull(configCrawl, "CONFIG crawl should exist")
        assertFalse(configCrawl!!.enabled, "CONFIG crawl should be disabled")

        val enabledCrawls = crawlConfigService.getEnabledCrawls()
        assertFalse(enabledCrawls.contains(configCrawl),
            "Disabled crawls should not appear in enabled crawls list")
    }

    @Test
    @DisplayName("Should handle environment variable substitution in paths")
    fun testEnvironmentVariableSubstitution() {
        val docsCrawl = crawlConfigService.getCrawlByName("USER_DOCUMENTS")!!

        // Spring Boot should automatically resolve ${user.home}
        assertTrue(docsCrawl.startPaths.isNotEmpty(), "Should have at least one start path")
        val firstPath = docsCrawl.startPaths.first()
        assertTrue(firstPath.contains("user.home") || firstPath.startsWith("/"),
            "Start path should contain variable or be resolved to absolute path")
    }

    @Test
    @DisplayName("Should respect hierarchical folder-to-file inheritance")
    fun testHierarchicalInheritance() {
        val workCrawl = crawlConfigService.getCrawlByName("WORK")!!
        val effectivePatterns = crawlConfigService.getEffectivePatterns(workCrawl)

        // Folder is INDEX
        val folderStatus = patternMatchingService.determineFolderAnalysisStatus(
            path = "/opt/work/project/src",
            patterns = effectivePatterns.folderPatterns,
            parentStatus = null
        )
        assertEquals(AnalysisStatus.INDEX, folderStatus)

        // File without explicit pattern should inherit from folder
        val fileStatus = patternMatchingService.determineFileAnalysisStatus(
            path = "/opt/work/project/src/unknown.xyz",
            filePatterns = effectivePatterns.filePatterns,
            parentFolderStatus = folderStatus
        )
        assertEquals(AnalysisStatus.INDEX, fileStatus,
            "Files should inherit status from parent folder when no explicit pattern matches")
    }

    @Test
    @DisplayName("Should cap ANALYZE inheritance for files")
    fun testAnalyzeInheritanceCapping() {
        val workCrawl = crawlConfigService.getCrawlByName("WORK")!!
        val effectivePatterns = crawlConfigService.getEffectivePatterns(workCrawl)

        // Even if parent folder is ANALYZE, files should be capped at INDEX unless explicitly matched
        val fileStatus = patternMatchingService.determineFileAnalysisStatus(
            path = "/opt/work/project/src/unknown.xyz",
            filePatterns = effectivePatterns.filePatterns,
            parentFolderStatus = AnalysisStatus.ANALYZE
        )
        assertEquals(AnalysisStatus.INDEX, fileStatus,
            "Files should be capped at INDEX when inheriting ANALYZE from parent folder")
    }

    @Test
    @DisplayName("Should validate all crawl definitions have required fields")
    fun testCrawlDefinitionValidation() {
        val allCrawls = crawlConfigService.getAllCrawls()

        allCrawls.forEach { crawl ->
            assertTrue(crawl.name.isNotEmpty(),
                "Crawl name should not be empty: $crawl")
            assertTrue(crawl.label.isNotEmpty(),
                "Crawl label should not be empty: ${crawl.name}")
            assertTrue(crawl.startPaths.isNotEmpty(),
                "Crawl start paths should not be empty: ${crawl.name}")
        }
    }

    @Test
    @DisplayName("Should handle complex regex patterns in configuration")
    fun testComplexRegexPatterns() {
        val workCrawl = crawlConfigService.getCrawlByName("WORK")!!
        val effectivePatterns = crawlConfigService.getEffectivePatterns(workCrawl)

        // Test Makefile pattern (no extension)
        val makefileStatus = patternMatchingService.determineFileAnalysisStatus(
            path = "/opt/work/project/Makefile",
            filePatterns = effectivePatterns.filePatterns,
            parentFolderStatus = AnalysisStatus.INDEX
        )
        assertEquals(AnalysisStatus.INDEX, makefileStatus,
            "Makefile should be indexed based on pattern match")

        // Test build.gradle.kts pattern
        val gradleStatus = patternMatchingService.determineFileAnalysisStatus(
            path = "/opt/work/project/build.gradle.kts",
            filePatterns = effectivePatterns.filePatterns,
            parentFolderStatus = AnalysisStatus.INDEX
        )
        assertEquals(AnalysisStatus.INDEX, gradleStatus,
            "build.gradle.kts should be indexed based on pattern match")
    }
}
