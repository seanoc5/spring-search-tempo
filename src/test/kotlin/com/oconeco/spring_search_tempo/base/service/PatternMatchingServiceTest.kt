package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.config.PatternSet
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

@DisplayName("PatternMatchingService Tests")
class PatternMatchingServiceTest {

    private lateinit var service: PatternMatchingService

    @BeforeEach
    fun setup() {
        service = PatternMatchingService()
    }

    @Test
    @DisplayName("Should mark folder as IGNORE when matching skip pattern")
    fun testFolderSkipPattern() {
        val patterns = PatternSet(
            skip = listOf(".*/\\.git/.*", ".*/node_modules/.*"),
            index = listOf(".*")
        )

        val result = service.determineFolderAnalysisStatus(
            path = "/home/user/project/.git/objects",
            patterns = patterns,
            parentStatus = null
        )

        assertEquals(AnalysisStatus.IGNORE, result)
    }

    @Test
    @DisplayName("Should mark folder as INDEX when matching index pattern")
    fun testFolderIndexPattern() {
        val patterns = PatternSet(
            skip = listOf(".*/\\.git/.*"),
            index = listOf(".*/Documents/.*")
        )

        val result = service.determineFolderAnalysisStatus(
            path = "/home/user/Documents/work",
            patterns = patterns,
            parentStatus = null
        )

        assertEquals(AnalysisStatus.INDEX, result)
    }

    @Test
    @DisplayName("Should mark folder as ANALYZE when matching analyze pattern")
    fun testFolderAnalyzePattern() {
        val patterns = PatternSet(
            analyze = listOf(".*/important/.*"),
            index = listOf(".*")
        )

        val result = service.determineFolderAnalysisStatus(
            path = "/home/user/important/docs",
            patterns = patterns,
            parentStatus = null
        )

        assertEquals(AnalysisStatus.ANALYZE, result)
    }

    @Test
    @DisplayName("Should inherit parent status when no patterns match")
    fun testFolderInheritParentStatus() {
        val patterns = PatternSet(
            skip = listOf(".*/\\.git/.*"),
            index = listOf(".*/Documents/.*")
        )

        val result = service.determineFolderAnalysisStatus(
            path = "/home/user/other/folder",
            patterns = patterns,
            parentStatus = AnalysisStatus.LOCATE
        )

        assertEquals(AnalysisStatus.LOCATE, result)
    }

    @Test
    @DisplayName("Should default to LOCATE when no patterns match and no parent")
    fun testFolderDefaultToLocate() {
        val patterns = PatternSet(
            skip = listOf(".*/\\.git/.*"),
            index = listOf(".*/Documents/.*")
        )

        val result = service.determineFolderAnalysisStatus(
            path = "/home/user/other/folder",
            patterns = patterns,
            parentStatus = null
        )

        assertEquals(AnalysisStatus.LOCATE, result)
    }

    @Test
    @DisplayName("Should prioritize SKIP over other patterns for folders")
    fun testFolderSkipPriority() {
        val patterns = PatternSet(
            skip = listOf(".*/\\.git/.*"),
            analyze = listOf(".*/\\.git/.*"),  // Both match
            index = listOf(".*")
        )

        val result = service.determineFolderAnalysisStatus(
            path = "/home/user/project/.git/hooks",
            patterns = patterns,
            parentStatus = null
        )

        assertEquals(AnalysisStatus.IGNORE, result)
    }

    @Test
    @DisplayName("Should mark file as IGNORE when matching skip pattern")
    fun testFileSkipPattern() {
        val patterns = PatternSet(
            skip = listOf(".*\\.(tmp|bak)$"),
            index = listOf(".*")
        )

        val result = service.determineFileAnalysisStatus(
            path = "/home/user/file.tmp",
            filePatterns = patterns,
            parentFolderStatus = AnalysisStatus.INDEX
        )

        assertEquals(AnalysisStatus.IGNORE, result)
    }

    @Test
    @DisplayName("Should mark file as IGNORE when parent folder is IGNORE")
    fun testFileInheritIgnoreFromParent() {
        val patterns = PatternSet(
            index = listOf(".*\\.txt$")
        )

        val result = service.determineFileAnalysisStatus(
            path = "/home/user/.git/file.txt",
            filePatterns = patterns,
            parentFolderStatus = AnalysisStatus.IGNORE
        )

        assertEquals(AnalysisStatus.IGNORE, result)
    }

    @Test
    @DisplayName("Should mark file as INDEX when matching index pattern")
    fun testFileIndexPattern() {
        val patterns = PatternSet(
            index = listOf(".*\\.(txt|md|pdf)$")
        )

        val result = service.determineFileAnalysisStatus(
            path = "/home/user/document.pdf",
            filePatterns = patterns,
            parentFolderStatus = AnalysisStatus.INDEX
        )

        assertEquals(AnalysisStatus.INDEX, result)
    }

    @Test
    @DisplayName("Should mark file as ANALYZE when matching analyze pattern")
    fun testFileAnalyzePattern() {
        val patterns = PatternSet(
            analyze = listOf(".*\\.md$"),
            index = listOf(".*\\.(txt|md|pdf)$")
        )

        val result = service.determineFileAnalysisStatus(
            path = "/home/user/README.md",
            filePatterns = patterns,
            parentFolderStatus = AnalysisStatus.INDEX
        )

        assertEquals(AnalysisStatus.ANALYZE, result)
    }

    @Test
    @DisplayName("Should cap inherited ANALYZE status to INDEX for files")
    fun testFileCapAnalyzeInheritance() {
        val patterns = PatternSet()  // No explicit patterns

        val result = service.determineFileAnalysisStatus(
            path = "/home/user/file.txt",
            filePatterns = patterns,
            parentFolderStatus = AnalysisStatus.ANALYZE
        )

        // Files should not auto-inherit ANALYZE, capped at INDEX
        assertEquals(AnalysisStatus.INDEX, result)
    }

    @Test
    @DisplayName("Should inherit LOCATE from parent folder")
    fun testFileInheritLocate() {
        val patterns = PatternSet()  // No explicit patterns

        val result = service.determineFileAnalysisStatus(
            path = "/usr/bin/bash",
            filePatterns = patterns,
            parentFolderStatus = AnalysisStatus.LOCATE
        )

        assertEquals(AnalysisStatus.LOCATE, result)
    }

    @Test
    @DisplayName("Should inherit INDEX from parent folder")
    fun testFileInheritIndex() {
        val patterns = PatternSet()  // No explicit patterns

        val result = service.determineFileAnalysisStatus(
            path = "/home/user/file.txt",
            filePatterns = patterns,
            parentFolderStatus = AnalysisStatus.INDEX
        )

        assertEquals(AnalysisStatus.INDEX, result)
    }

    @Test
    @DisplayName("Should handle multiple file extension patterns")
    fun testMultipleFileExtensions() {
        val patterns = PatternSet(
            index = listOf(
                ".*\\.(kt|java|py)$",
                ".*\\.(xml|json|ya?ml)$"
            )
        )

        assertTrue(service.determineFileAnalysisStatus(
            "/src/Main.kt", patterns, AnalysisStatus.LOCATE
        ) == AnalysisStatus.INDEX)

        assertTrue(service.determineFileAnalysisStatus(
            "/config/app.yaml", patterns, AnalysisStatus.LOCATE
        ) == AnalysisStatus.INDEX)

        assertTrue(service.determineFileAnalysisStatus(
            "/config/app.yml", patterns, AnalysisStatus.LOCATE
        ) == AnalysisStatus.INDEX)
    }

    @Test
    @DisplayName("Should clear pattern cache")
    fun testClearCache() {
        val patterns = PatternSet(index = listOf(".*\\.txt$"))

        // First call compiles and caches
        service.determineFolderAnalysisStatus("/test.txt", patterns, null)
        assertTrue(service.getCacheSize() > 0)

        // Clear cache
        service.clearCache()
        assertEquals(0, service.getCacheSize())
    }

    @Test
    @DisplayName("Should handle invalid regex patterns gracefully")
    fun testInvalidRegexPattern() {
        val patterns = PatternSet(
            skip = listOf("[invalid(regex"),  // Invalid regex
            index = listOf(".*")
        )

        // Should not throw exception, should return default behavior
        val result = service.determineFolderAnalysisStatus(
            path = "/home/user/folder",
            patterns = patterns,
            parentStatus = null
        )

        // Should fall through to index pattern since skip pattern is invalid
        assertEquals(AnalysisStatus.INDEX, result)
    }

    @Test
    @DisplayName("Should match complex path patterns")
    fun testComplexPathPatterns() {
        val patterns = PatternSet(
            skip = listOf(
                ".*/\\.(gradle|idea|git)/.*",
                ".*/build/.*",
                ".*/target/.*"
            ),
            index = listOf(".*/src/.*")
        )

        assertEquals(AnalysisStatus.IGNORE,
            service.determineFolderAnalysisStatus(
                "/project/.gradle/wrapper", patterns, null
            ))

        assertEquals(AnalysisStatus.IGNORE,
            service.determineFolderAnalysisStatus(
                "/project/build/classes", patterns, null
            ))

        assertEquals(AnalysisStatus.INDEX,
            service.determineFolderAnalysisStatus(
                "/project/src/main", patterns, null
            ))
    }
}
