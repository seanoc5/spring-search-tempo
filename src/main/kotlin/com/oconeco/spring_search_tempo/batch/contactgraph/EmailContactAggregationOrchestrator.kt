package com.oconeco.spring_search_tempo.batch.contactgraph

import com.oconeco.spring_search_tempo.base.EmailAccountService
import org.slf4j.LoggerFactory
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.stereotype.Service
import java.time.OffsetDateTime


/**
 * Issue #146 Phase 1: launch [EmailContactAggregationJobBuilder] jobs.
 */
@Service
class EmailContactAggregationOrchestrator(
    private val emailAccountService: EmailAccountService,
    private val jobLauncher: JobLauncher,
    private val jobBuilder: EmailContactAggregationJobBuilder
) {

    companion object {
        private val log = LoggerFactory.getLogger(EmailContactAggregationOrchestrator::class.java)
    }

    fun runForAccount(accountId: Long): JobExecution {
        val account = emailAccountService.get(accountId)
        log.info("Dispatching email-contact aggregation for account {} ({})", accountId, account.email)

        val job = jobBuilder.buildJob(accountId)
        val params = JobParametersBuilder()
            .addString("accountId", accountId.toString())
            .addString("timestamp", OffsetDateTime.now().toString())
            .toJobParameters()
        return jobLauncher.run(job, params)
    }

    /**
     * Dispatch aggregation for every account the current caller can see.
     * Returns a map of account email → job-launch status string.
     */
    fun runForCurrentUser(): Map<String, String> {
        val accounts = emailAccountService.findAllForCurrentUser()
        if (accounts.isEmpty()) return emptyMap()

        val results = LinkedHashMap<String, String>()
        for (account in accounts) {
            val accountId = account.id ?: continue
            val key = account.email ?: "id=$accountId"
            try {
                val execution = runForAccount(accountId)
                results[key] = "STARTED (executionId=${execution.id})"
            } catch (e: Exception) {
                log.warn("EmailContact aggregation dispatch failed for {}: {}", key, e.message)
                results[key] = "ERROR: ${e.message}"
            }
        }
        return results
    }
}
