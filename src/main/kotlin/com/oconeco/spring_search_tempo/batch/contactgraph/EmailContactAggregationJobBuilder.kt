package com.oconeco.spring_search_tempo.batch.contactgraph

import com.oconeco.spring_search_tempo.base.EmailContactService
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.launch.support.RunIdIncrementer
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.repeat.RepeatStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager


/**
 * Issue #146 Phase 1: contact-aggregation job builder.
 *
 * One step per job: recompute counters for a single email account. Idempotent —
 * see [EmailContactService.recomputeForAccount].
 */
@Component
class EmailContactAggregationJobBuilder(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val emailContactService: EmailContactService
) {

    companion object {
        private val log = LoggerFactory.getLogger(EmailContactAggregationJobBuilder::class.java)

        const val JOB_NAME_PREFIX = "emailContactAggregation_"

        fun jobName(accountId: Long) = "$JOB_NAME_PREFIX$accountId"
    }

    fun buildJob(accountId: Long): Job {
        val step = buildAggregationStep(accountId)
        return JobBuilder(jobName(accountId), jobRepository)
            .incrementer(RunIdIncrementer())
            .start(step)
            .build()
    }

    private fun buildAggregationStep(accountId: Long): Step {
        val tasklet = Tasklet { contribution, _ ->
            val touched = emailContactService.recomputeForAccount(accountId)
            contribution.incrementWriteCount(touched.toLong())
            log.info("EmailContact aggregation step finished for account {}: {} rows touched",
                accountId, touched)
            RepeatStatus.FINISHED
        }

        return StepBuilder("emailContactAggregationStep_$accountId", jobRepository)
            .tasklet(tasklet, transactionManager)
            .build()
    }
}
