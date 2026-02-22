package com.oconeco.spring_search_tempo.base.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile

class StartPathValidatorTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `validatePath returns valid for existing readable directory`() {
        // Arrange
        val validDir = tempDir.resolve("valid-dir").createDirectory()

        // Act
        val result = StartPathValidator.validatePath(validDir)

        // Assert
        assertThat(result.isValid).isTrue()
        assertThat(result.issue).isNull()
        assertThat(result.path).isEqualTo(validDir)
    }

    @Test
    fun `validatePath returns NOT_EXISTS for missing path`() {
        // Arrange
        val missingPath = Path.of("/temp")  // Intentionally wrong path (not /tmp)

        // Act
        val result = StartPathValidator.validatePath(missingPath)

        // Assert
        assertThat(result.isValid).isFalse()
        assertThat(result.issue).isEqualTo(PathIssue.NOT_EXISTS)
    }

    @Test
    fun `validatePath returns NOT_A_DIRECTORY for file path`() {
        // Arrange
        val filePath = tempDir.resolve("a-file.txt").createFile()

        // Act
        val result = StartPathValidator.validatePath(filePath)

        // Assert
        assertThat(result.isValid).isFalse()
        assertThat(result.issue).isEqualTo(PathIssue.NOT_A_DIRECTORY)
    }

    @Test
    fun `validatePaths returns results for all paths`() {
        // Arrange
        val validDir = tempDir.resolve("valid").createDirectory()
        val missingDir = Path.of("/nonexistent/path/that/does/not/exist")
        val paths = listOf(validDir, missingDir)

        // Act
        val results = StartPathValidator.validatePaths(paths)

        // Assert
        assertThat(results).hasSize(2)
        assertThat(results[0].isValid).isTrue()
        assertThat(results[1].isValid).isFalse()
    }

    @Test
    fun `validateAndFilter separates valid paths from warnings`() {
        // Arrange
        val validDir1 = tempDir.resolve("valid1").createDirectory()
        val validDir2 = tempDir.resolve("valid2").createDirectory()
        val missingDir = Path.of("/temp")  // Missing path (not /tmp)
        val paths = listOf(validDir1, missingDir, validDir2)

        // Act
        val (validPaths, warnings) = StartPathValidator.validateAndFilter(paths)

        // Assert
        assertThat(validPaths).hasSize(2)
        assertThat(validPaths).containsExactly(validDir1, validDir2)

        assertThat(warnings).hasSize(1)
        assertThat(warnings[0]).contains("/temp")
        assertThat(warnings[0]).contains("does not exist")
    }

    @Test
    fun `validateAndFilter returns empty lists for empty input`() {
        // Arrange
        val paths = emptyList<Path>()

        // Act
        val (validPaths, warnings) = StartPathValidator.validateAndFilter(paths)

        // Assert
        assertThat(validPaths).isEmpty()
        assertThat(warnings).isEmpty()
    }

    @Test
    fun `validatePath works with actual tmp directory`() {
        // Arrange - /tmp should exist on most systems
        val tmpPath = Path.of("/tmp")

        // Act
        val result = StartPathValidator.validatePath(tmpPath)

        // Assert - /tmp should be a valid readable directory
        if (Files.exists(tmpPath)) {
            assertThat(result.isValid).isTrue()
            assertThat(result.issue).isNull()
        }
        // If /tmp doesn't exist (Windows?), just verify the result is well-formed
        assertThat(result.path).isEqualTo(tmpPath)
    }

    @Test
    fun `validatePath detects case-sensitive path issues like Downloads vs downloads`() {
        // Arrange - This tests the specific example from the user
        // /opt/Downloads doesn't exist (should be /opt/downloads or similar)
        val wrongCasePath = Path.of("/opt/Downloads")

        // Act
        val result = StartPathValidator.validatePath(wrongCasePath)

        // Assert - This path likely doesn't exist
        if (!Files.exists(wrongCasePath)) {
            assertThat(result.isValid).isFalse()
            assertThat(result.issue).isEqualTo(PathIssue.NOT_EXISTS)
        }
    }

    @Test
    fun `validateAndFilter generates meaningful warning messages`() {
        // Arrange
        val missingPath = Path.of("/some/path/that/definitely/does/not/exist")
        val filePath = tempDir.resolve("not-a-dir.txt").createFile()
        val paths = listOf(missingPath, filePath)

        // Act
        val (_, warnings) = StartPathValidator.validateAndFilter(paths)

        // Assert
        assertThat(warnings).hasSize(2)
        assertThat(warnings[0]).contains("does not exist")
        assertThat(warnings[1]).contains("is not a directory")
    }
}
