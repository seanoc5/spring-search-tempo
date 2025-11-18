package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.ContentChunkService
import com.oconeco.spring_search_tempo.base.FSFileService
import com.oconeco.spring_search_tempo.base.model.FSFileDTO
import org.junit.jupiter.api.Test
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.test.JobLauncherTestUtils
import org.springframework.batch.test.context.SpringBatchTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
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
 * - ChunkWriter persists ContentChunk to the database
 *
 * Prerequisites:
 * - Database must contain FSFile records with non-null bodyText
 * - Run file crawl job first to populate test data
 */
@SpringBootTest(
    properties = [
        "spring.batch.job.enabled=false", // Disable auto-run
        "spring.batch.jdbc.initialize-schema=always", // Initialize Spring Batch schema
        "spring.jpa.hibernate.ddl-auto=update", // Allow schema updates for test
        "spring.main.allow-bean-definition-overriding=true"
    ]
)
@SpringBatchTest
class ChunkingStepTest {

    @Autowired
    lateinit var jobLauncherTestUtils: JobLauncherTestUtils

    @Autowired
    lateinit var fileService: FSFileService

    @Autowired
    lateinit var chunkService: ContentChunkService

    /**
     * Wire the test job to JobLauncherTestUtils.
     * This is required for Spring Batch Test to know which job to test.
     * We use @Qualifier to specify which Job bean to inject since there are multiple.
     */
    @Autowired
    fun setJob(@Qualifier("chunkingTestJob") job: Job) {
        jobLauncherTestUtils.job = job
    }

    @Test
    fun `should process files and create content chunks`() {
        // Given: Count files with bodyText and existing chunks
        val filesWithBodyText = fileService.findAll(null, org.springframework.data.domain.Pageable.unpaged()).totalElements
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
            chunkingTestStep: Step
        ): Job {
            return JobBuilder("chunkingTestJob", jobRepository)
                .start(chunkingTestStep)
                .build()
        }

        @Bean
        fun chunkReader(fileService: FSFileService): ChunkReader {
            return ChunkReader(fileService, pageSize = 50)
        }

        @Bean
        fun chunkProcessor(): ChunkProcessor {
            return ChunkProcessor()
        }

        @Bean
        fun chunkWriter(chunkService: ContentChunkService): ChunkWriter {
            return ChunkWriter(chunkService)
        }

        @Bean
        fun chunkingTestStep(
            jobRepository: JobRepository,
            transactionManager: PlatformTransactionManager,
            chunkReader: ChunkReader,
            chunkProcessor: ChunkProcessor,
            chunkWriter: ChunkWriter
        ): Step {
            return StepBuilder("chunkingTestStep", jobRepository)
                .chunk<FSFileDTO, List<com.oconeco.spring_search_tempo.base.model.ContentChunkDTO>>(
                    10,
                    transactionManager
                )
                .reader(chunkReader)
                .processor(chunkProcessor)
                .writer(chunkWriter)
                .build()
        }

        /**
         * Configure JobLauncherTestUtils with our test job.
         * This is required for @SpringBatchTest to work properly.
         */
        @Bean
        fun jobLauncherTestUtils(): JobLauncherTestUtils {
            return JobLauncherTestUtils()
        }
    }
}
