package com.oconeco.spring_search_tempo.batch.audit

import com.oconeco.spring_search_tempo.base.domain.FolderAuditRun
import com.oconeco.spring_search_tempo.base.domain.FolderSnapshot
import com.oconeco.spring_search_tempo.base.repos.FolderSnapshotRepository
import com.oconeco.spring_search_tempo.base.service.PatternMatchingService
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.isRegularFile

/**
 * `FileVisitor` for the folder audit (issue #103).
 *
 * Distinct from the discovery visitor: this one **walks into** SKIP
 * subtrees so we can reconcile counts and surface "hidden gem" folders
 * (e.g. node_modules/internal-tool/). To keep the cost bounded, it
 * descends only [peekDepth] levels below a detected SKIP root before
 * returning `SKIP_SUBTREE` — preventing node_modules/foo/node_modules/...
 * from exploding into millions of rows.
 *
 * SKIP-root detection differs subtly from the live crawl. Production
 * SKIP patterns are written to match a child path — for example the
 * regex that matches `node_modules` content does not match the
 * `node_modules` folder itself, because the pattern requires something
 * after the trailing slash. The live crawl only fires `SKIP_SUBTREE` on
 * the first child of such a folder. For the audit, we want to identify
 * the SKIP root one level up so the [peekDepth] window is measured from
 * the directory the user would call "the SKIP folder" (e.g.
 * `node_modules`), not from its first child. We do that by testing a
 * synthetic child path (`dir/x`) against the patterns — if a direct
 * child of `dir` would be SKIP, `dir` itself is the SKIP root from the
 * audit's perspective.
 *
 * Pattern matching for the "hidden gem" flag ([FolderSnapshot.matchedIndexPattern])
 * uses the same synthetic-child probe as SKIP detection (in addition to
 * the literal path). Production INDEX/ANALYZE/SEMANTIC patterns are
 * written in both styles — some match the folder itself (anchored on
 * the folder name), others match content within it. Probing both ways
 * makes the hidden-gem signal robust regardless of which style the
 * operator used.
 */
class FolderAuditVisitor(
    private val run: FolderAuditRun,
    private val startPath: Path,
    private val skipPatterns: List<String>,
    private val indexPatterns: List<String>,
    private val peekDepth: Int,
    private val patternMatchingService: PatternMatchingService,
    private val folderSnapshotRepository: FolderSnapshotRepository,
    private val batchSize: Int = 200
) : SimpleFileVisitor<Path>() {

    companion object {
        private val log = LoggerFactory.getLogger(FolderAuditVisitor::class.java)
    }

    private var currentSkipRoot: Path? = null
    private var currentSkipPattern: String? = null

    var totalFolders: Long = 0
        private set
    var skipSubtreeCount: Long = 0
        private set
    var hiddenGemCount: Long = 0
        private set

    private val buffer = mutableListOf<FolderSnapshot>()

    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
        val isAlreadyUnderSkip = currentSkipRoot != null && dir.startsWith(currentSkipRoot!!)

        if (!isAlreadyUnderSkip) {
            // Not under an active SKIP root — clear any leftover state and
            // check whether this folder is itself the entry of a new SKIP
            // subtree. Test against a synthetic child path so production
            // patterns like ".*/node_modules/.*" identify `node_modules`
            // itself (not its first child) as the SKIP root.
            currentSkipRoot = null
            currentSkipPattern = null
            val syntheticChild = if (dir.toString().endsWith("/")) "${dir}x" else "$dir/x"
            val skipResult = patternMatchingService.matchesSkipPatternOnly(syntheticChild, skipPatterns)
            if (skipResult.isSkip) {
                currentSkipRoot = dir
                currentSkipPattern = skipResult.matchedPattern
                skipSubtreeCount++
                log.debug("SKIP root detected: {} (pattern={})", dir, skipResult.matchedPattern)
            }
        }

        val underSkip = currentSkipRoot != null
        val depthBelowSkip = if (underSkip) dir.nameCount - currentSkipRoot!!.nameCount else 0

        val matchedSkipPattern = if (underSkip) currentSkipPattern else null

        // INDEX/ANALYZE/SEMANTIC patterns: try the literal folder path
        // first, then the synthetic-child probe. The dual check catches
        // both pattern styles (`.*/Documents$` matching the folder vs.
        // `.*/Documents/.*` matching its contents).
        val matchedIndexPattern = if (indexPatterns.isNotEmpty()) {
            val literal = patternMatchingService.matchesSkipPatternOnly(dir.toString(), indexPatterns)
            if (literal.isSkip) {
                literal.matchedPattern
            } else {
                val syntheticChildForIndex = if (dir.toString().endsWith("/")) "${dir}x" else "$dir/x"
                patternMatchingService.matchesSkipPatternOnly(syntheticChildForIndex, indexPatterns)
                    .takeIf { it.isSkip }
                    ?.matchedPattern
            }
        } else null

        if (matchedSkipPattern != null && matchedIndexPattern != null) {
            hiddenGemCount++
        }

        val immediateFileCount = countImmediateFiles(dir)

        val snapshot = FolderSnapshot().apply {
            auditRun = run
            path = dir.toString()
            parentPath = dir.parent?.toString()
            depth = (dir.nameCount - startPath.nameCount).coerceAtLeast(0)
            underSkipPattern = matchedSkipPattern
            this.matchedIndexPattern = matchedIndexPattern
            fileCount = immediateFileCount
        }
        buffer.add(snapshot)
        totalFolders++

        if (buffer.size >= batchSize) flushBatch()

        return if (underSkip && depthBelowSkip >= peekDepth) {
            FileVisitResult.SKIP_SUBTREE
        } else {
            FileVisitResult.CONTINUE
        }
    }

    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
        if (currentSkipRoot == dir) {
            currentSkipRoot = null
            currentSkipPattern = null
        }
        return FileVisitResult.CONTINUE
    }

    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
        // Audit is read-only; tolerate per-file errors. SKIP_SUBTREE on a
        // file is treated as CONTINUE by Files.walkFileTree, so this is
        // safe even when `file` is a directory we couldn't open.
        if (exc is AccessDeniedException) {
            log.debug("Access denied (skipping): {}", file)
        } else {
            log.debug("Visit failed (skipping): {} — {}", file, exc.message)
        }
        return FileVisitResult.CONTINUE
    }

    private fun countImmediateFiles(dir: Path): Int = try {
        Files.list(dir).use { stream ->
            stream.filter { it.isRegularFile() }.count().toInt()
        }
    } catch (e: Exception) {
        log.debug("Cannot list files in {}: {}", dir, e.message)
        0
    }

    fun flush() {
        flushBatch()
    }

    private fun flushBatch() {
        if (buffer.isNotEmpty()) {
            folderSnapshotRepository.saveAll(buffer)
            buffer.clear()
        }
    }
}
