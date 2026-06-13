package com.oconeco.spring_search_tempo.batch.audit

import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.launch.support.RunIdIncrementer
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

/**
 * Spring Batch job definition for the filesystem folder audit (issue #103).
 *
 * Single-tasklet job — the audit walks each configured start path with
 * a [FolderAuditVisitor] and writes one `folder_snapshot` row per
 * directory. No item streaming/chunking is needed because the workload
 * per row is tiny (one INSERT) and the visitor batches saves internally.
 */
@Configuration
class FilesystemFolderAuditJobConfiguration(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val tasklet: FilesystemFolderAuditTasklet
) {

    @Bean("filesystemFolderAuditJob")
    fun filesystemFolderAuditJob(): Job = JobBuilder("filesystemFolderAuditJob", jobRepository)
        .incrementer(RunIdIncrementer())
        .start(filesystemFolderAuditStep())
        .build()

    @Bean
    fun filesystemFolderAuditStep(): Step = StepBuilder("filesystemFolderAuditStep", jobRepository)
        .tasklet(tasklet, transactionManager)
        .build()
}
