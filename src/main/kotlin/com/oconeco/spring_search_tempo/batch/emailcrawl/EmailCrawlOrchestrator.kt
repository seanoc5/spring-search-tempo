package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.EmailFolderService
import com.oconeco.spring_search_tempo.base.config.EmailConfiguration
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import com.oconeco.spring_search_tempo.base.model.EmailAccountDTO
import com.oconeco.spring_search_tempo.base.service.EmailFolderSyncService
import com.oconeco.spring_search_tempo.base.service.ImapConnectionService
import org.slf4j.LoggerFactory
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId


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
    private val emailFolderService: EmailFolderService,
    private val emailFolderSyncService: EmailFolderSyncService,
    private val emailQuickSyncJobBuilder: EmailQuickSyncJobBuilder,
    private val jobLauncher: JobLauncher,
    private val imapConnectionService: ImapConnectionService
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
     * Jobs are launched asynchronously -- this method returns immediately after
     * submitting each account's job to the async JobLauncher.
     *
     * @param forceFullSync If true, ignore lastSyncUid and fetch all messages (full recrawl)
     * @param forceRefresh If true, re-process already-processed records (chunks, NLP)
     * @param interestingDays How far back to look for "interesting" messages (default 7)
     * @param stepThreads Number of threads for step-level parallelism (default: 1 = serial)
     * @param itemAsync Whether to use AsyncItemProcessor (default: false)
     * @param asyncThreads Number of threads for async item processing (default: 4)
     * @param chunkSize Number of items per chunk (default: 20)
     * @return Map of account email to job launch status (includes executionId)
     */
    fun runQuickSync(
        forceFullSync: Boolean = false,
        forceRefresh: Boolean = false,
        interestingDays: Int = 7,
        stepThreads: Int = 1,
        itemAsync: Boolean = false,
        asyncThreads: Int = 4,
        chunkSize: Int = 20
    ): Map<String, String> {
        if (!emailConfiguration.enabled) {
            log.info("Email crawling is disabled in configuration")
            return mapOf("status" to "disabled")
        }

        val parallelConfig = ParallelizationConfig(
            stepThreads = stepThreads,
            itemAsync = itemAsync,
            asyncThreads = asyncThreads,
            chunkSize = chunkSize
        )

        log.info("Launching email {} for all enabled accounts with {}",
            if (forceFullSync) "FULL sync" else "quick sync",
            parallelConfig)

        val results = mutableMapOf<String, String>()

        // Get or create accounts from configuration
        val accounts = getOrCreateAccounts()

        accounts.filter { it.enabled == true }.forEach { account ->
            // Pre-flight password guard (issue #55) — skip accounts without a DB-encrypted
            // password so we don't dispatch a job guaranteed to fail at credential resolution.
            val accountId = account.id
            if (accountId == null || !emailAccountService.hasPassword(accountId)) {
                log.info("Skipping {} — no password set; edit the account to add one", account.email)
                results[account.email ?: "id=${account.id}"] = "SKIPPED (no password set)"
                return@forEach
            }
            val folders = resolveFolders(account)
            try {
                log.info("Launching {} for account: {} with folders: {} ({})",
                    if (forceFullSync) "FULL sync" else "quick sync",
                    account.email, folders, parallelConfig.modeName)

                // Pre-fetch expected message count for progress tracking
                val expectedTotal = getExpectedMessageCount(account, folders)
                log.debug("Expected total messages for {}: {}", account.email, expectedTotal)

                val job = emailQuickSyncJobBuilder.buildJob(
                    account, folders, forceFullSync, forceRefresh, interestingDays, parallelConfig
                )
                val jobParameters = JobParametersBuilder()
                    .addString("accountId", account.id.toString())
                    .addString("accountEmail", account.email ?: "unknown")  // For job run tracking
                    .addString("forceFullSync", forceFullSync.toString())
                    .addString("forceRefresh", forceRefresh.toString())
                    .addLong("interestingDays", interestingDays.toLong())
                    .addString("parallelMode", parallelConfig.modeName)
                    .addLong("expectedTotal", expectedTotal)  // For progress tracking
                    .addString("timestamp", OffsetDateTime.now().toString())
                    .toJobParameters()

                val execution = jobLauncher.run(job, jobParameters)

                results[account.email!!] = "STARTED (executionId=${execution.id})"
                log.info("{} for {} launched: executionId={}, status={} ({})",
                    if (forceFullSync) "Full sync" else "Quick sync",
                    account.email, execution.id, execution.status, parallelConfig.modeName)

            } catch (e: Exception) {
                log.error("Error launching sync for {}: {}", account.email, e.message, e)
                results[account.email!!] = "ERROR: ${e.message}"

                // Record error on account
                try {
                    emailAccountService.recordError(account.id!!, e.message ?: "Unknown error")
                } catch (recordError: Exception) {
                    log.warn("Failed to record error on account: {}", recordError.message)
                }
            }
        }

        log.info("Email sync jobs launched. Results: {}", results)
        return results
    }

    /**
     * Run quick sync for a specific account.
     *
     * The job is launched asynchronously -- this method returns immediately
     * after submitting the job to the async JobLauncher.
     *
     * @param accountId The account ID to sync
     * @param forceFullSync If true, ignore lastSyncUid and fetch all messages (full recrawl)
     * @param forceRefresh If true, re-process already-processed records (chunks, NLP)
     * @param interestingDays How far back to look for "interesting" messages (default 7)
     * @param parallelConfig Configuration for parallel processing (default: serial)
     * @return The job execution (status will be STARTING since launch is async)
     */
    fun runQuickSyncForAccount(
        accountId: Long,
        forceFullSync: Boolean = false,
        forceRefresh: Boolean = false,
        interestingDays: Int = 7,
        parallelConfig: ParallelizationConfig = ParallelizationConfig()
    ): JobExecution {
        val account = emailAccountService.get(accountId)
        val folders = resolveFolders(account)

        log.info("Launching {} for account {} with folders: {} ({})",
            if (forceFullSync) "FULL sync" else "quick sync",
            account.email, folders, parallelConfig.modeName)

        // Pre-fetch expected message count for progress tracking
        val expectedTotal = getExpectedMessageCount(account, folders)
        log.debug("Expected total messages for {}: {}", account.email, expectedTotal)

        val job = emailQuickSyncJobBuilder.buildJob(
            account, folders, forceFullSync, forceRefresh, interestingDays, parallelConfig
        )
        val jobParameters = JobParametersBuilder()
            .addString("accountId", accountId.toString())
            .addString("accountEmail", account.email ?: "unknown")  // For job run tracking
            .addString("forceFullSync", forceFullSync.toString())
            .addString("forceRefresh", forceRefresh.toString())
            .addLong("interestingDays", interestingDays.toLong())
            .addString("parallelMode", parallelConfig.modeName)
            .addLong("expectedTotal", expectedTotal)  // For progress tracking
            .addString("timestamp", OffsetDateTime.now().toString())
            .toJobParameters()

        val execution = jobLauncher.run(job, jobParameters)
        log.info("{} for {} launched: executionId={}, status={}",
            if (forceFullSync) "Full sync" else "Quick sync",
            account.email, execution.id, execution.status)

        return execution
    }

    /**
     * Get expected message count for progress tracking.
     * Queries IMAP for message counts in each folder.
     * Returns 0 if count cannot be determined (connection failure, etc.).
     */
    private fun getExpectedMessageCount(account: EmailAccountDTO, folders: List<String>): Long {
        return try {
            imapConnectionService.withConnection(account) { store ->
                folders.sumOf { folderName ->
                    try {
                        val folder = store.getFolder(folderName)
                        folder.open(jakarta.mail.Folder.READ_ONLY)
                        val count = folder.messageCount.toLong()
                        folder.close(false)
                        count
                    } catch (e: Exception) {
                        log.debug("Could not get message count for folder {}: {}", folderName, e.message)
                        0L
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("Could not get expected message count for {}: {}", account.email, e.message)
            0L
        }
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
     * Resolve which folders to sync for an account.
     *
     * Order of preference:
     *  1. Enumerated `EmailFolder` rows with `syncEnabled=true` and not `\Noselect`.
     *     This is the path the user controls from the UI (issue #3).
     *  2. If no folder rows exist yet (account never enumerated), enumerate now
     *     so first-run accounts work without an explicit user action.
     *  3. If enumeration fails or surfaces no selectable folders, fall back to
     *     the legacy `application.yml` `quickSyncFolders` list so misconfigured
     *     servers still get a best-effort sync.
     */
    private fun resolveFolders(account: EmailAccountDTO): List<String> {
        val id = account.id
            ?: return emailConfiguration.quickSyncFolders

        val targets = emailFolderService.fetchTargets(id)
        if (targets.isNotEmpty()) {
            return targets
        }

        // First-run: try to enumerate. Best-effort — IMAP errors fall through to config.
        return try {
            log.info("[{}] No enumerated folders found; running first-time enumeration", account.email)
            emailFolderSyncService.enumerateFolders(id)
            val enumerated = emailFolderService.fetchTargets(id)
            if (enumerated.isNotEmpty()) enumerated else emailConfiguration.quickSyncFolders
        } catch (e: Exception) {
            log.warn("Folder enumeration failed for {}: {}. Falling back to configured quick-sync folders.",
                account.email, e.message)
            emailConfiguration.quickSyncFolders
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
        return try {
            imapConnectionService.testConnection(account)
        } catch (e: Exception) {
            log.error("Connection test failed for {}: {}", account.email, e.message, e)
            false
        }
    }

    /**
     * Per-account cron dispatch (issue #2, ADR-004).
     *
     * Enumerates enabled accounts and, for each, evaluates the stored
     * `cronSchedule` against `now`. Dispatches one [EmailQuickSyncJob] per
     * account whose next cron boundary (after `lastDispatchedAt`) has
     * elapsed. Failure to dispatch one account does NOT halt the loop —
     * each per-account dispatch is wrapped in its own try/catch and the
     * error is recorded on the account.
     *
     * Returns one [AccountDispatchResult] per enabled account so callers
     * (the minute-tick scheduler, tests) can see what fired and what was
     * skipped.
     */
    fun runDueAccounts(now: Instant): List<AccountDispatchResult> {
        if (!emailConfiguration.enabled) {
            log.debug("Email crawling disabled; runDueAccounts is a no-op")
            return emptyList()
        }

        val accounts = emailAccountService.findEnabled()
        if (accounts.isEmpty()) {
            log.debug("No enabled email accounts; runDueAccounts is a no-op")
            return emptyList()
        }

        // Pre-flight password guard (issue #55): drop accounts with no DB-encrypted
        // password before evaluating cron boundaries. Without this, the scheduler
        // would dispatch a job per tick for each unconfigured account and the
        // credential-resolution layer would throw — recorded as a per-account
        // ERROR log entry every minute (the symptom that motivated #55).
        val (eligible, noPassword) = accounts.partition { acc ->
            acc.id != null && emailAccountService.hasPassword(acc.id!!)
        }
        if (noPassword.isNotEmpty()) {
            log.info(
                "Skipping {} enabled email account(s) with no password set: {} — set via the account edit page",
                noPassword.size,
                noPassword.joinToString(", ") { it.email ?: "id=${it.id}" }
            )
        }

        val zone = ZoneId.systemDefault()
        val results = mutableListOf<AccountDispatchResult>()

        // Surface the no-password skips in the result list so callers (tests, UI) can see them.
        for (account in noPassword) {
            val accountId = account.id ?: continue
            results += AccountDispatchResult(
                accountId, account.email, DispatchOutcome.NO_PASSWORD,
                "no DB-encrypted password — set via the account edit page"
            )
        }

        for (account in eligible) {
            val accountId = account.id ?: continue
            try {
                val cron = try {
                    CronExpression.parse(account.cronSchedule)
                } catch (e: Exception) {
                    val reason = "invalid cron '${account.cronSchedule}': ${e.message}"
                    log.warn("Skipping account {} — {}", account.email, reason)
                    emailAccountService.recordError(accountId, reason)
                    results += AccountDispatchResult(accountId, account.email, DispatchOutcome.INVALID_CRON, reason)
                    continue
                }

                // Anchor point for next-cron-boundary lookup. If never dispatched, anchor
                // at epoch so the first eligible boundary fires immediately.
                val anchor = (account.lastDispatchedAt
                    ?: OffsetDateTime.ofInstant(Instant.EPOCH, zone))
                    .atZoneSameInstant(zone)

                val nextBoundary = cron.next(anchor)
                val nowZdt = now.atZone(zone)

                if (nextBoundary == null || nextBoundary.isAfter(nowZdt)) {
                    results += AccountDispatchResult(accountId, account.email, DispatchOutcome.NOT_DUE, null)
                    continue
                }

                log.info("Dispatching quick sync for account {} (cron='{}', last={}, due={})",
                    account.email, account.cronSchedule, account.lastDispatchedAt, nextBoundary)

                val execution = runQuickSyncForAccount(accountId = accountId)

                // Record dispatch time AFTER successful launch so a failed dispatch
                // is retried on the next tick.
                emailAccountService.recordDispatched(accountId, OffsetDateTime.ofInstant(now, zone))

                results += AccountDispatchResult(
                    accountId, account.email, DispatchOutcome.DISPATCHED,
                    "executionId=${execution.id}"
                )
            } catch (e: Exception) {
                log.error("Failed to dispatch sync for account {}: {}", account.email, e.message, e)
                try {
                    emailAccountService.recordError(accountId, e.message ?: "Unknown dispatch error")
                } catch (recordError: Exception) {
                    log.warn("Failed to record dispatch error on account {}: {}", account.email, recordError.message)
                }
                results += AccountDispatchResult(
                    accountId, account.email, DispatchOutcome.ERROR, e.message
                )
                // Continue iterating — sibling accounts must not be blocked.
            }
        }

        return results
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
 * Outcome for one account in a [EmailCrawlOrchestrator.runDueAccounts] sweep.
 */
data class AccountDispatchResult(
    val accountId: Long,
    val email: String?,
    val outcome: DispatchOutcome,
    val detail: String?
)

enum class DispatchOutcome {
    /** Account's cron boundary elapsed and the job launched successfully. */
    DISPATCHED,
    /** Account's next cron boundary is still in the future. */
    NOT_DUE,
    /** Account's cronSchedule string is invalid; recorded as account error. */
    INVALID_CRON,
    /** Account has no DB-encrypted password set; pre-flight skip (issue #55). */
    NO_PASSWORD,
    /** Exception during dispatch; the loop continues for sibling accounts. */
    ERROR
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
