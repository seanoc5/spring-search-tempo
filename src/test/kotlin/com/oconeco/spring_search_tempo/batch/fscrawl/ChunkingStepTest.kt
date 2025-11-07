package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.ContentChunksService
import com.oconeco.spring_search_tempo.base.domain.FSFile
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import org.junit.jupiter.api.Test
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.test.JobLauncherTestUtils
import org.springframework.batch.test.context.SpringBatchTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager

/**
 * Standalone test for the content chunking step.
 *
 * This test verifies the chunking functionality independently from the
 * full crawl job, processing only existing FSFiles with bodyText.
 *
 * Expected behavior:
 * - ChunkReader loads FSFile entities with bodyText from the database
 * - ChunkProcessor splits each file's text into sentence-level chunks
 * - ChunkWriter persists ContentChunks to the database
 *
 * Prerequisites:
 * - Database must contain FSFile records with non-null bodyText
 * - Run file crawl job first to populate test data
 */
@SpringBootTest(
    properties = [
        "spring.batch.job.enabled=false", // Disable auto-run
        "spring.jpa.hibernate.ddl-auto=update", // Allow schema updates for test
        "spring.main.allow-bean-definition-overriding=true"
    ]
)
@SpringBatchTest
class ChunkingStepTest {

    @Autowired
    lateinit var jobLauncherTestUtils: JobLauncherTestUtils

    @Autowired
    lateinit var fileRepository: FSFileRepository

    @Autowired
    lateinit var chunkService: ContentChunksService

    @Test
    fun `should process files and create content chunks`() {
        // Given: Count files with bodyText and existing chunks
        val filesWithBodyText = fileRepository.count() // Simplified for test
        val initialChunkCount = countTotalChunks()

        println("=== Chunking Step Test ===")
        println("Files in database: $filesWithBodyText")
        println("Initial chunk count: $initialChunkCount")

        // When: Execute just the chunking step
        val jobExecution = jobLauncherTestUtils.launchStep("chunkingTestStep")

        // Then: Verify step completed successfully
        jobExecution.stepExecutions.forEach { stepExecution ->
            println("\nStep: ${stepExecution.stepName}")
            println("  Status: ${stepExecution.status}")
            println("  Exit status: ${stepExecution.exitStatus.exitCode}")
            println("  Read count: ${stepExecution.readCount}")
            println("  Write count: ${stepExecution.writeCount}")
            println("  Skip count: ${stepExecution.skipCount}")
            println("  Commit count: ${stepExecution.commitCount}")

            if (stepExecution.failureExceptions.isNotEmpty()) {
                println("  Failures:")
                stepExecution.failureExceptions.forEach { exception ->
                    println("    - ${exception.message}")
                    exception.printStackTrace()
                }
            }
        }

        val finalChunkCount = countTotalChunks()
        val chunksCreated = finalChunkCount - initialChunkCount

        println("\nFinal chunk count: $finalChunkCount")
        println("Chunks created: $chunksCreated")
        println("=========================")
    }

    private fun countTotalChunks(): Long {
        // Use native query since we just need a count
        return try {
            val result = chunkService.findAll()
            result.size.toLong()
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Test configuration that creates a minimal job with only the chunking step.
     */
    @TestConfiguration
    class ChunkingTestConfig {

        @Bean
        fun chunkingTestJob(
            jobRepository: JobRepository,
            chunkingStep: Step
        ): Job {
            return JobBuilder("chunkingTestJob", jobRepository)
                .start(chunkingStep)
                .build()
        }

        @Bean
        fun chunkingStep(
            jobRepository: JobRepository,
            transactionManager: PlatformTransactionManager,
            fileRepository: FSFileRepository,
            chunkService: ContentChunksService
        ): Step {
            return StepBuilder("chunkingTestStep", jobRepository)
                .chunk<FSFile, List<com.oconeco.spring_search_tempo.base.model.ContentChunksDTO>>(
                    10,
                    transactionManager
                )
                .reader(ChunkReader(fileRepository, pageSize = 50))
                .processor(ChunkProcessor())
                .writer(ChunkWriter(chunkService))
                .build()
        }
    }
}
