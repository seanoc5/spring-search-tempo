package com.oconeco.spring_search_tempo.batch.fscrawl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobInstance
import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.StepExecution
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies the resume-from-checkpoint behavior of CombinedCrawlReader for issue #8.
 *
 * Simulates a crashed crawl by running the reader, "crashing" after a partial drain,
 * then re-instantiating with a checkpoint URI to confirm the second run skips
 * everything up to and including the checkpoint.
 */
class CombinedCrawlReaderCheckpointTest {

    @Test
    fun `walk order is deterministic across reader instances`(@TempDir tempDir: Path) {
        seedTree(tempDir)

        val first = newReader(tempDir).also { it.runBeforeStep(resumeFromUri = null) }
        val second = newReader(tempDir).also { it.runBeforeStep(resumeFromUri = null) }

        val firstOrder = first.drainAll().map { it.directory.toString() }
        val secondOrder = second.drainAll().map { it.directory.toString() }

        assertEquals(firstOrder, secondOrder, "Walk order must be reproducible across reader instances")
    }

    @Test
    fun `resume from checkpoint skips directories already processed`(@TempDir tempDir: Path) {
        seedTree(tempDir)

        // Full walk to establish expected order.
        val fullOrder = newReader(tempDir).also { it.runBeforeStep(resumeFromUri = null) }
            .drainAll()
            .map { it.directory.toString() }

        assertTrue(fullOrder.size >= 4, "Expected at least 4 directories from seed tree; got $fullOrder")

        // Simulate crash after persisting the first two items. Pick the SECOND item's URI
        // as the checkpoint — the next run must resume *after* that one.
        val crashCheckpoint = fullOrder[1]
        val expectedAfterResume = fullOrder.drop(2)

        val resumed = newReader(tempDir).also { it.runBeforeStep(resumeFromUri = crashCheckpoint) }
        val resumedOrder = resumed.drainAll().map { it.directory.toString() }

        assertEquals(expectedAfterResume, resumedOrder,
            "After resuming from '$crashCheckpoint', reader must emit only directories that sort strictly after it")
    }

    @Test
    fun `resume from final checkpoint emits nothing`(@TempDir tempDir: Path) {
        seedTree(tempDir)

        val fullOrder = newReader(tempDir).also { it.runBeforeStep(resumeFromUri = null) }
            .drainAll()
            .map { it.directory.toString() }
        val finalUri = fullOrder.last()

        val resumed = newReader(tempDir).also { it.runBeforeStep(resumeFromUri = finalUri) }
        assertNull(resumed.read(), "Resuming past the final URI should yield no items")
    }

    // --- helpers ---------------------------------------------------------------

    /**
     * Build a small, predictable directory tree:
     *
     *   tempDir/
     *     a/      (with two files)
     *     b/
     *       b1/   (with one file)
     *       b2/   (empty)
     *     c/      (with one file)
     */
    private fun seedTree(root: Path) {
        val a = Files.createDirectory(root.resolve("a"))
        Files.createFile(a.resolve("a1.txt"))
        Files.createFile(a.resolve("a2.txt"))

        val b = Files.createDirectory(root.resolve("b"))
        val b1 = Files.createDirectory(b.resolve("b1"))
        Files.createFile(b1.resolve("b1a.txt"))
        Files.createDirectory(b.resolve("b2"))

        val c = Files.createDirectory(root.resolve("c"))
        Files.createFile(c.resolve("c1.txt"))
    }

    private fun newReader(root: Path) = CombinedCrawlReader(
        startPaths = listOf(root),
        maxDepth = 20,
        followLinks = false,
        folderMatcher = null,
        recentCrawlChecker = null
    )

    private fun CombinedCrawlReader.drainAll(): List<CombinedCrawlItem> {
        val out = mutableListOf<CombinedCrawlItem>()
        while (true) {
            val item = read() ?: break
            out.add(item)
        }
        return out
    }

    /**
     * Invoke the reader's StepExecutionListener.beforeStep with the given resume URI,
     * mirroring what Spring Batch does at runtime.
     */
    private fun CombinedCrawlReader.runBeforeStep(resumeFromUri: String?) {
        val jobInstance = JobInstance(1L, "test-job")
        val jobExecution = JobExecution(jobInstance, JobParameters())
        val stepExecution = StepExecution("test-step", jobExecution)
        if (resumeFromUri != null) {
            jobExecution.executionContext.putString(
                CombinedCrawlReader.RESUME_FROM_URI_KEY, resumeFromUri
            )
        }
        beforeStep(stepExecution)
    }
}
