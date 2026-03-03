package com.oconeco.spring_search_tempo.batch.onedrivesync

import com.oconeco.spring_search_tempo.base.OneDriveAccountService
import com.oconeco.spring_search_tempo.base.config.OneDriveConfiguration
import com.oconeco.spring_search_tempo.base.service.OneDriveConnectionService
import org.springframework.batch.core.JobExecution
import org.slf4j.LoggerFactory
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.stereotype.Service
import java.time.OffsetDateTime


/**
 * Orchestrator for OneDrive sync jobs.
 *
 * Manages execution of sync jobs across all enabled OneDrive accounts.
 * Can be called via REST API or web UI.
 */
@Service
class OneDriveSyncOrchestrator(
    private val config: OneDriveConfiguration,
    private val accountService: OneDriveAccountService,
    private val connectionService: OneDriveConnectionService,
    private val jobBuilder: OneDriveSyncJobBuilder,
    private val jobLauncher: JobLauncher
) {
    companion object {
        private val log = LoggerFactory.getLogger(OneDriveSyncOrchestrator::class.java)
    }

    /**
     * Run sync for all enabled OneDrive accounts.
     *
     * @param forceFullSync If true, ignore delta tokens and do full enumeration
     * @return Map of account email to job execution status
     */
    fun runSync(forceFullSync: Boolean = false): Map<String, String> {
        if (!config.enabled) {
            log.info("OneDrive integration is disabled in configuration")
            return mapOf("status" to "disabled")
        }

        log.info("Starting OneDrive {} for all enabled accounts",
            if (forceFullSync) "FULL sync" else "sync")

        val results = mutableMapOf<String, String>()
        val accounts = accountService.findEnabled()

        accounts.forEach { account ->
            try {
                val execution = runSyncExecutionForAccount(account.id!!, forceFullSync)
                results[account.email ?: "unknown"] = execution.status.toString()
            } catch (e: Exception) {
                log.error("Error running OneDrive sync for {}: {}", account.email, e.message, e)
                results[account.email ?: "unknown"] = "ERROR: ${e.message}"

                try {
                    accountService.recordError(account.id!!, e.message ?: "Unknown error")
                } catch (re: Exception) {
                    log.warn("Failed to record error on account: {}", re.message)
                }
            }
        }

        log.info("OneDrive sync completed. Results: {}", results)
        return results
    }

    /**
     * Run sync for a specific OneDrive account.
     *
     * @param accountId The account ID to sync
     * @param forceFullSync If true, ignore delta token and do full enumeration
     * @return Job execution status string
     */
    fun runSyncForAccount(accountId: Long, forceFullSync: Boolean = false): String {
        val execution = runSyncExecutionForAccount(accountId, forceFullSync)
        return execution.status.toString()
    }

    /**
     * Run sync for all enabled OneDrive accounts and return executions.
     */
    fun runSyncExecutions(forceFullSync: Boolean = false): Map<Long, JobExecution> {
        if (!config.enabled) {
            log.info("OneDrive integration is disabled in configuration")
            return emptyMap()
        }

        val accounts = accountService.findEnabled()
        val results = mutableMapOf<Long, JobExecution>()

        accounts.forEach { account ->
            try {
                val accountId = account.id ?: throw IllegalStateException("OneDrive account ID is required")
                results[accountId] = runSyncExecutionForAccount(accountId, forceFullSync)
            } catch (e: Exception) {
                log.error("Failed to launch OneDrive sync for account {}: {}", account.email, e.message, e)
            }
        }

        return results
    }

    /**
     * Run sync for a specific account and return the JobExecution.
     */
    fun runSyncExecutionForAccount(accountId: Long, forceFullSync: Boolean = false): JobExecution {
        val account = accountService.get(accountId)

        log.info("Running OneDrive {} for account: {}",
            if (forceFullSync) "FULL sync" else "sync", account.email)

        val job = jobBuilder.buildJob(accountId, forceFullSync)
        val jobParameters = JobParametersBuilder()
            .addString("accountId", accountId.toString())
            .addString("accountEmail", account.email ?: "unknown")
            .addString("forceFullSync", forceFullSync.toString())
            .addString("timestamp", OffsetDateTime.now().toString())
            .toJobParameters()

        val execution = jobLauncher.run(job, jobParameters)

        log.info("OneDrive sync for {} completed with status: {}", account.email, execution.status)
        return execution
    }

    /**
     * Get sync status for all OneDrive accounts.
     */
    fun getSyncStatus(): List<OneDriveSyncStatus> {
        return accountService.findEnabled().map { account ->
            OneDriveSyncStatus(
                accountId = account.id!!,
                email = account.email,
                displayName = account.displayName,
                lastDeltaSyncAt = account.lastDeltaSyncAt,
                lastFullSyncAt = account.lastFullSyncAt,
                enabled = account.enabled ?: true,
                lastError = account.lastError,
                totalItems = account.totalItems,
                totalSize = account.totalSize
            )
        }
    }

    /**
     * Test connection for a specific OneDrive account.
     *
     * @param accountId The account ID to test
     * @return true if connection successful
     */
    fun testConnection(accountId: Long): Boolean {
        log.info("Testing OneDrive connection for account {}", accountId)
        return try {
            connectionService.testConnection(accountId)
            true
        } catch (e: Exception) {
            log.error("OneDrive connection test failed for account {}: {}", accountId, e.message, e)
            false
        }
    }
}


/**
 * Status information for a OneDrive account's sync state.
 */
data class OneDriveSyncStatus(
    val accountId: Long,
    val email: String?,
    val displayName: String?,
    val lastDeltaSyncAt: OffsetDateTime?,
    val lastFullSyncAt: OffsetDateTime?,
    val enabled: Boolean,
    val lastError: String?,
    val totalItems: Long?,
    val totalSize: Long?
)
