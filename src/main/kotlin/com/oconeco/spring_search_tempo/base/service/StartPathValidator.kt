package com.oconeco.spring_search_tempo.base.service

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Result of validating a start path.
 */
data class PathValidationResult(
    val path: Path,
    val isValid: Boolean,
    val issue: PathIssue?
)

/**
 * Types of path validation issues.
 */
enum class PathIssue {
    NOT_EXISTS,
    NOT_A_DIRECTORY,
    NOT_READABLE
}

/**
 * Validates start paths for crawl jobs.
 * Checks that paths exist, are directories, and are readable.
 */
object StartPathValidator {
    private val log = LoggerFactory.getLogger(StartPathValidator::class.java)

    /**
     * Validate a single path.
     */
    fun validatePath(path: Path): PathValidationResult {
        return when {
            !Files.exists(path) -> {
                log.warn("Start path does not exist: {}", path)
                PathValidationResult(path, false, PathIssue.NOT_EXISTS)
            }
            !Files.isDirectory(path) -> {
                log.warn("Start path is not a directory: {}", path)
                PathValidationResult(path, false, PathIssue.NOT_A_DIRECTORY)
            }
            !Files.isReadable(path) -> {
                log.warn("Start path is not readable: {}", path)
                PathValidationResult(path, false, PathIssue.NOT_READABLE)
            }
            else -> {
                log.debug("Start path is valid: {}", path)
                PathValidationResult(path, true, null)
            }
        }
    }

    /**
     * Validate multiple paths and return results.
     */
    fun validatePaths(paths: List<Path>): List<PathValidationResult> {
        return paths.map { validatePath(it) }
    }

    /**
     * Validate paths and return only valid ones, logging warnings for invalid paths.
     * Returns a pair of (validPaths, warningMessages).
     */
    fun validateAndFilter(paths: List<Path>): Pair<List<Path>, List<String>> {
        val results = validatePaths(paths)
        val validPaths = results.filter { it.isValid }.map { it.path }
        val warnings = results.filter { !it.isValid }.map { result ->
            val issueDescription = when (result.issue) {
                PathIssue.NOT_EXISTS -> "does not exist"
                PathIssue.NOT_A_DIRECTORY -> "is not a directory"
                PathIssue.NOT_READABLE -> "is not readable"
                null -> "unknown issue"
            }
            "Start path '${result.path}' $issueDescription"
        }

        if (warnings.isNotEmpty()) {
            log.warn("Path validation found {} invalid start path(s):", warnings.size)
            warnings.forEach { log.warn("  - {}", it) }
        }

        if (validPaths.isEmpty() && paths.isNotEmpty()) {
            log.error("ALL start paths are invalid! Crawl will process nothing.")
        }

        return Pair(validPaths, warnings)
    }
}
