package com.oconeco.spring_search_tempo.batch.audit

import com.oconeco.spring_search_tempo.base.domain.FolderAuditRun
import com.oconeco.spring_search_tempo.base.domain.FolderSnapshot
import com.oconeco.spring_search_tempo.base.repos.FolderSnapshotRepository
import com.oconeco.spring_search_tempo.base.service.PatternMatchingService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit test for the audit visitor (issue #103, acceptance criterion `e`).
 *
 * Builds a fixture tree under a `@TempDir` containing:
 *   fixture/
 *     docs                     - normal folder (not under SKIP)
 *     node_modules             - SKIP root (the audit's synthetic-child probe matches the pattern)
 *       internal-tool          - depth 1 under SKIP, legit-looking, matches INDEX pattern
 *         src                  - depth 2, should NOT be in snapshot (peek-depth=1)
 *       transitive-dep         - depth 1, recorded but not a hidden gem
 *         node_modules
 *           deeper             - depth 3, should NOT be in snapshot
 *
 * With `peekDepth=1` the visitor must record `node_modules`, `internal-tool`
 * and `transitive-dep`, but must NOT record `src`, the inner
 * `node_modules`, or `deeper`. `internal-tool` must have
 * `matchedIndexPattern` populated.
 */
class FolderAuditVisitorTest {

    @TempDir
    lateinit var tempDir: Path

    private val patternMatchingService = PatternMatchingService()

    @Test
    @DisplayName("peek-depth=1 records SKIP root + one level below, populates matchedIndexPattern on hidden-gem candidate")
    fun peekDepthOneRecordsHiddenGemCandidate() {
        // ── Build fixture tree ──────────────────────────────────────────
        val fixture = Files.createDirectory(tempDir.resolve("fixture"))
        Files.createDirectory(fixture.resolve("docs"))
        val nodeModules = Files.createDirectory(fixture.resolve("node_modules"))
        val internalTool = Files.createDirectory(nodeModules.resolve("internal-tool"))
        Files.createDirectory(internalTool.resolve("src"))
        val transitiveDep = Files.createDirectory(nodeModules.resolve("transitive-dep"))
        val innerNodeModules = Files.createDirectory(transitiveDep.resolve("node_modules"))
        Files.createDirectory(innerNodeModules.resolve("deeper"))
        Files.createFile(fixture.resolve("readme.txt"))
        Files.createFile(nodeModules.resolve("package-lock.json"))

        // ── Wire visitor with a capturing repository stub ───────────────
        val captured = mutableListOf<FolderSnapshot>()
        val repository = capturingSnapshotRepository(captured)

        val run = FolderAuditRun().apply { id = 1L }

        val skipPatterns = listOf(".*/node_modules/.*")
        val indexPatterns = listOf(".*/internal-tool$")

        val visitor = FolderAuditVisitor(
            run = run,
            startPath = fixture,
            skipPatterns = skipPatterns,
            indexPatterns = indexPatterns,
            peekDepth = 1,
            patternMatchingService = patternMatchingService,
            folderSnapshotRepository = repository,
            batchSize = 1000
        )

        Files.walkFileTree(fixture, visitor)
        visitor.flush()

        val byName = captured.associateBy { Path.of(it.path!!).fileName.toString() }

        // ── Assertions ──────────────────────────────────────────────────
        assertThat(byName).containsKeys("fixture", "docs", "node_modules", "internal-tool", "transitive-dep")
        // Peek-depth=1: do NOT descend below level 1 inside the SKIP root
        assertThat(byName).doesNotContainKeys("src", "deeper")
        // The inner `node_modules` is itself at depth 2 under the SKIP root
        // and must not be recorded.
        assertThat(captured.count { it.path!!.endsWith("transitive-dep/node_modules") })
            .isEqualTo(0)

        val internalToolSnap = byName["internal-tool"]!!
        assertThat(internalToolSnap.underSkipPattern)
            .describedAs("internal-tool lives under the node_modules SKIP root")
            .isEqualTo(".*/node_modules/.*")
        assertThat(internalToolSnap.matchedIndexPattern)
            .describedAs("internal-tool name matches an INDEX pattern — this is the hidden-gem signal")
            .isEqualTo(".*/internal-tool$")

        val transitiveSnap = byName["transitive-dep"]!!
        assertThat(transitiveSnap.underSkipPattern).isEqualTo(".*/node_modules/.*")
        assertThat(transitiveSnap.matchedIndexPattern)
            .describedAs("transitive-dep does not look legit — no INDEX-pattern match")
            .isNull()

        val docsSnap = byName["docs"]!!
        assertThat(docsSnap.underSkipPattern)
            .describedAs("docs is not under any SKIP root")
            .isNull()

        val nodeModulesSnap = byName["node_modules"]!!
        assertThat(nodeModulesSnap.underSkipPattern)
            .describedAs("node_modules is itself the SKIP root — flagged as 'under SKIP' with its own pattern")
            .isEqualTo(".*/node_modules/.*")

        assertThat(visitor.skipSubtreeCount)
            .describedAs("exactly one top-level SKIP root encountered")
            .isEqualTo(1)
        assertThat(visitor.hiddenGemCount)
            .describedAs("internal-tool is the sole hidden-gem candidate")
            .isEqualTo(1)
    }

    @Test
    @DisplayName("reconciles snapshot total against Files.walk() ground truth on a fixture tree (no SKIP)")
    fun reconcilesAgainstFilesWalk() {
        // Build a moderately nested tree with no SKIP patterns — total
        // folder count must match a straightforward Files.walk().
        val fixture = Files.createDirectory(tempDir.resolve("recon"))
        Files.createDirectory(fixture.resolve("a"))
        Files.createDirectory(fixture.resolve("a/aa"))
        Files.createDirectory(fixture.resolve("a/ab"))
        Files.createDirectory(fixture.resolve("b"))
        Files.createDirectory(fixture.resolve("b/ba"))
        Files.createDirectory(fixture.resolve("b/ba/baa"))
        Files.createFile(fixture.resolve("a/aa/x.txt"))

        val captured = mutableListOf<FolderSnapshot>()
        val repository = capturingSnapshotRepository(captured)
        val run = FolderAuditRun().apply { id = 2L }

        val visitor = FolderAuditVisitor(
            run = run,
            startPath = fixture,
            skipPatterns = emptyList(),
            indexPatterns = emptyList(),
            peekDepth = 1,
            patternMatchingService = patternMatchingService,
            folderSnapshotRepository = repository,
            batchSize = 1000
        )

        Files.walkFileTree(fixture, visitor)
        visitor.flush()

        val groundTruth = Files.walk(fixture).use { stream ->
            stream.filter { Files.isDirectory(it) }.count()
        }

        assertThat(captured.size.toLong())
            .describedAs("audit visitor records exactly the directories Files.walk() sees when no SKIP applies")
            .isEqualTo(groundTruth)
        assertThat(visitor.totalFolders).isEqualTo(groundTruth)
        assertThat(visitor.skipSubtreeCount).isZero()
        assertThat(visitor.hiddenGemCount).isZero()
    }

    /**
     * Stub repository whose `saveAll(...)` appends every batch into the
     * provided list. All other JpaRepository methods throw — they should
     * never be reached by the visitor.
     */
    private fun capturingSnapshotRepository(
        sink: MutableList<FolderSnapshot>
    ): FolderSnapshotRepository {
        val mock = Mockito.mock(FolderSnapshotRepository::class.java)
        Mockito.`when`(mock.saveAll(Mockito.anyIterable<FolderSnapshot>()))
            .thenAnswer { invocation ->
                @Suppress("UNCHECKED_CAST")
                val batch = invocation.arguments[0] as Iterable<FolderSnapshot>
                val list = batch.toList()
                sink.addAll(list)
                list
            }
        return mock
    }
}
