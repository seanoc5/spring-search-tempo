package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.config.EmailConfiguration
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import com.oconeco.spring_search_tempo.base.model.EmailAccountDTO
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.stereotype.Service
import java.time.OffsetDateTime


/**
 * Orchestrator for email crawling jobs.
 *
 * Manages execution of quick sync and full sync jobs across all enabled email accounts.
 * Can be called manually via REST API or scheduled.
 */
@Service
class EmailCrawlOrchestrator(
    private val emailConfiguration: EmailConfiguration,
    private val emailAccountService: EmailAccountService,
    private val emailQuickSyncJobBuilder: EmailQuickSyncJobBuilder,
    private val jobLauncher: JobLauncher
) {
    companion object {
        private val log = LoggerFactory.getLogger(EmailCrawlOrchestrator::class.java)
    }

    /**
     * Run quick sync for all enabled accounts.
     *
     * Quick sync fetches only new messages (since last UID) from configured folders.
     * This is the "daily" sync strategy that handles 98%+ of messages efficiently.
     *
     * @return Map of account email to job execution status
     */
    fun runQuickSync(): Map<String, String> {
        if (!emailConfiguration.enabled) {
            log.info("Email crawling is disabled in configuration")
            return mapOf("status" to "disabled")
        }

        log.info("Starting email quick sync for all enabled accounts")

        val results = mutableMapOf<String, String>()
        val folders = emailConfiguration.quickSyncFolders

        // Get or create accounts from configuration
        val accounts = getOrCreateAccounts()

        accounts.filter { it.enabled == true }.forEach { account ->
            try {
                log.info("Running quick sync for account: {} with folders: {}", account.email, folders)

                val job = emailQuickSyncJobBuilder.buildJob(account, folders)
                val jobParameters = JobParametersBuilder()
                    .addString("accountId", account.id.toString())
                    .addString("timestamp", OffsetDateTime.now().toString())
                    .toJobParameters()

                val execution = jobLauncher.run(job, jobParameters)

                results[account.email!!] = execution.status.toString()
                log.info("Quick sync for {} completed with status: {}", account.email, execution.status)

            } catch (e: Exception) {
                log.error("Error running quick sync for {}: {}", account.email, e.message, e)
                results[account.email!!] = "ERROR: ${e.message}"

                // Record error on account
                try {
                    emailAccountService.recordError(account.id!!, e.message ?: "Unknown error")
                } catch (recordError: Exception) {
                    log.warn("Failed to record error on account: {}", recordError.message)
                }
            }
        }

        log.info("Email quick sync completed. Results: {}", results)
        return results
    }

    /**
     * Run quick sync for a specific account.
     *
     * @param accountId The account ID to sync
     * @return Job execution status
     */
    fun runQuickSyncForAccount(accountId: Long): String {
        val account = emailAccountService.get(accountId)
        val folders = emailConfiguration.quickSyncFolders

        log.info("Running quick sync for account {} with folders: {}", account.email, folders)

        val job = emailQuickSyncJobBuilder.buildJob(account, folders)
        val jobParameters = JobParametersBuilder()
            .addString("accountId", accountId.toString())
            .addString("timestamp", OffsetDateTime.now().toString())
            .toJobParameters()

        val execution = jobLauncher.run(job, jobParameters)

        return execution.status.toString()
    }

    /**
     * Get or create email accounts from configuration.
     *
     * Synchronizes configuration with database - creates accounts if they don't exist.
     */
    private fun getOrCreateAccounts(): List<EmailAccountDTO> {
        val configAccounts = emailConfiguration.accounts.filter { it.enabled }

        return configAccounts.mapNotNull { config ->
            try {
                // Try to find existing account
                val existing = try {
                    emailAccountService.findByEmail(config.email)
                } catch (e: Exception) {
                    null
                }

                if (existing != null) {
                    // Update enabled status from config
                    existing.enabled = config.enabled
                    existing
                } else {
                    // Create new account
                    val dto = EmailAccountDTO().apply {
                        email = config.email
                        label = config.name
                        provider = EmailProvider.valueOf(config.provider)
                        imapHost = config.imapHost
                        imapPort = config.imapPort
                        enabled = config.enabled
                    }

                    val id = emailAccountService.create(dto)
                    emailAccountService.get(id)
                }
            } catch (e: Exception) {
                log.error("Error processing account config {}: {}", config.email, e.message, e)
                null
            }
        }
    }

    /**
     * Test connection for a specific account.
     *
     * @param accountId The account ID to test
     * @return true if connection successful
     */
    fun testConnection(accountId: Long): Boolean {
        val account = emailAccountService.get(accountId)
        log.info("Testing connection for account: {}", account.email)

        // This would call ImapConnectionService.testConnection
        // For now, just return true as a placeholder
        return true
    }

    /**
     * Get sync status for all accounts.
     */
    fun getSyncStatus(): List<AccountSyncStatus> {
        return emailAccountService.findEnabled().map { account ->
            AccountSyncStatus(
                accountId = account.id!!,
                email = account.email!!,
                lastQuickSync = account.lastQuickSyncAt,
                lastFullSync = account.lastFullSyncAt,
                enabled = account.enabled ?: true,
                lastError = account.lastError
            )
        }
    }
}

/**
 * Status information for an email account's sync state.
 */
data class AccountSyncStatus(
    val accountId: Long,
    val email: String,
    val lastQuickSync: OffsetDateTime?,
    val lastFullSync: OffsetDateTime?,
    val enabled: Boolean,
    val lastError: String?
)
