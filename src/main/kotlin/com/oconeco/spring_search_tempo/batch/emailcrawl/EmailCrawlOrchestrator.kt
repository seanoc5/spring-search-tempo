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
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Service
import java.time.Duration
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
    private val imapConnectionService: ImapConnectionService,
    private val jobExplorer: JobExplorer
) {
    companion object {
        private val log = LoggerFactory.getLogger(EmailCrawlOrchestrator::class.java)

        /** Job name shape used by [EmailQuickSyncJobBuilder.buildJob]. */
        internal fun quickSyncJobName(accountId: Long) = "emailQuickSync_$accountId"
    }

    /**
     * Returns the in-flight `emailQuickSync_<accountId>` execution if one
     * exists, else `null`. Used to de-dup overlapping dispatches between
     * the per-account cron scheduler (every ~30s) and manual UI/REST
     * triggers — see issue #57.
     */
    private fun findRunningQuickSync(accountId: Long): JobExecution? {
        return jobExplorer.findRunningJobExecutions(quickSyncJobName(accountId)).firstOrNull()
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
            // Issue #57: skip if a quick sync for this account is already in flight.
            findRunningQuickSync(accountId)?.let { alreadyRunning ->
                log.info(
                    "Skipping: quick sync already in flight for account {} (executionId={})",
                    account.email, alreadyRunning.id
                )
                results[account.email ?: "id=$accountId"] =
                    "SKIPPED (already running, executionId=${alreadyRunning.id})"
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
        // Issue #57: refuse if a quick sync for this account is already in flight.
        // The Spring Batch timestamp parameter would otherwise let two concurrent
        // executions race against the same mailbox.
        findRunningQuickSync(accountId)?.let { running ->
            throw JobExecutionAlreadyRunningException(
                "Quick sync already in flight for accountId=$accountId (executionId=${running.id})"
            )
        }

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
     * Pre-flight sync estimate for the EmailAccount view page (issue #83).
     *
     * Renders the "Last sync: 2 days ago · ~47 messages waiting" string the operator
     * sees next to the "Sync Now" button. Touches IMAP read-only so the operator can
     * tell at a glance whether the click will fetch a handful of new messages or
     * trigger a huge initial pull.
     *
     * Behavior:
     *  - **Account disabled or no password set** → reachable=false, the view renders
     *    a short "Set a password / enable the account" hint.
     *  - **Never synced** (`lastQuickSyncAt == null`) → `estimatedNew` equals the
     *    total messageCount of resolved target folders.
     *  - **Previously synced** → per-folder UID search for messages above
     *    `lastSyncUid` (no header fetch — just UID list size).
     *  - **IMAP unreachable** → reachable=false, the view degrades to "Estimate
     *    unavailable" without breaking the page.
     */
    fun getSyncEstimate(accountId: Long): SyncEstimate {
        val account = try {
            emailAccountService.get(accountId)
        } catch (e: Exception) {
            log.debug("Could not load account {} for estimate: {}", accountId, e.message)
            return SyncEstimate(
                neverSynced = true, lastSyncAt = null, estimatedNew = 0L,
                totalMessages = 0L, reachable = false,
                message = "Account not found"
            )
        }

        val neverSynced = account.lastQuickSyncAt == null
        val lastSyncAt = account.lastQuickSyncAt

        if (account.enabled != true) {
            return SyncEstimate(
                neverSynced = neverSynced, lastSyncAt = lastSyncAt,
                estimatedNew = 0L, totalMessages = 0L, reachable = false,
                message = "Account disabled"
            )
        }
        if (!emailAccountService.hasPassword(accountId)) {
            return SyncEstimate(
                neverSynced = neverSynced, lastSyncAt = lastSyncAt,
                estimatedNew = 0L, totalMessages = 0L, reachable = false,
                message = "Password not set — edit the account to add one"
            )
        }

        val folders = try {
            resolveFolders(account)
        } catch (e: Exception) {
            log.debug("Folder resolution failed for {}: {}", account.email, e.message)
            return SyncEstimate(
                neverSynced = neverSynced, lastSyncAt = lastSyncAt,
                estimatedNew = 0L, totalMessages = 0L, reachable = false,
                message = "Estimate unavailable (folder resolution failed)"
            )
        }

        // Per-folder lastSyncUid lookup so we can compute "new since last sync"
        // without re-reading the whole mailbox.
        val folderTargetsByName: Map<String, Long> = try {
            emailFolderService.findByAccount(accountId)
                .associate { (it.folderName ?: "") to (it.lastSyncUid ?: 0L) }
        } catch (e: Exception) {
            log.debug("findByAccount({}) failed: {}", accountId, e.message)
            emptyMap()
        }

        return try {
            imapConnectionService.withConnection(account) { store ->
                var totalAcc = 0L
                var newAcc = 0L
                for (folderName in folders) {
                    try {
                        val folder = store.getFolder(folderName) as? com.sun.mail.imap.IMAPFolder
                            ?: continue
                        folder.open(jakarta.mail.Folder.READ_ONLY)
                        try {
                            val total = folder.messageCount.toLong()
                            totalAcc += total
                            val lastUid = folderTargetsByName[folderName] ?: 0L
                            newAcc += if (neverSynced || lastUid == 0L) {
                                total
                            } else {
                                // UID-range fetch returns the message handles, no header fetch.
                                // size() is cheap; we do not iterate the array.
                                val arr = folder.getMessagesByUID(lastUid + 1, jakarta.mail.UIDFolder.LASTUID)
                                arr.filterNotNull().size.toLong()
                            }
                        } finally {
                            folder.close(false)
                        }
                    } catch (e: Exception) {
                        log.debug("Estimate skip for folder {}: {}", folderName, e.message)
                    }
                }
                SyncEstimate(
                    neverSynced = neverSynced,
                    lastSyncAt = lastSyncAt,
                    estimatedNew = newAcc,
                    totalMessages = totalAcc,
                    reachable = true,
                    message = null
                )
            }
        } catch (e: Exception) {
            log.warn("IMAP estimate unavailable for {}: {}", account.email, e.message)
            SyncEstimate(
                neverSynced = neverSynced, lastSyncAt = lastSyncAt,
                estimatedNew = 0L, totalMessages = 0L, reachable = false,
                message = "Estimate unavailable (IMAP unreachable)"
            )
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
     *  1. If the account has never been enumerated OR its enumeration is older
     *     than `app.email.folder-enum-max-age-hours` (issue #84), re-enumerate
     *     so newly-created server-side folders surface without a manual UI
     *     action. New folders land with `syncEnabled=false` so the operator
     *     opts in (matches issue #3 semantics).
     *  2. Enumerated `EmailFolder` rows with `syncEnabled=true` and not
     *     `\Noselect`. This is the path the user controls from the UI.
     *  3. If enumeration fails or surfaces no selectable folders, fall back to
     *     the legacy `application.yml` `quickSyncFolders` list so misconfigured
     *     servers still get a best-effort sync.
     */
    private fun resolveFolders(account: EmailAccountDTO): List<String> {
        val id = account.id
            ?: return emailConfiguration.quickSyncFolders

        maybeRefreshFolderEnumeration(account, id)

        val targets = emailFolderService.fetchTargets(id)
        if (targets.isNotEmpty()) {
            return targets
        }

        // Fall through: enumeration may have run but turned up nothing
        // selectable, OR a transient IMAP error prevented it. Fall back so
        // misconfigured servers still get a best-effort sync against the
        // YAML-listed folders.
        return emailConfiguration.quickSyncFolders
    }

    /**
     * Issue #84: enumerate (or re-enumerate) folders when stale. Best-effort —
     * we never let a LIST * failure kill the sync; the legacy fallback in
     * [resolveFolders] keeps the account moving even if we couldn't refresh.
     */
    private fun maybeRefreshFolderEnumeration(account: EmailAccountDTO, accountId: Long) {
        val maxAge = Duration.ofHours(emailConfiguration.folderEnumMaxAgeHours.coerceAtLeast(1L))
        val last = account.lastFolderEnumeratedAt
        val needsRefresh = last == null ||
            Duration.between(last, OffsetDateTime.now()) > maxAge

        if (!needsRefresh) return

        try {
            if (last == null) {
                log.info("[{}] First-time folder enumeration", account.email)
                emailFolderSyncService.enumerateFolders(accountId)
            } else {
                log.info(
                    "[{}] Folder enumeration is stale (last={}, max-age={}h); re-enumerating",
                    account.email, last, maxAge.toHours(),
                )
                val result = emailFolderSyncService.reEnumerateFolders(accountId)
                if (result.newlyDiscoveredFolderIds.isNotEmpty()) {
                    log.info(
                        "[{}] Re-enumeration discovered {} new folder(s) — persisted with syncEnabled=false " +
                            "(operator opts in from /emailAccounts/{}/folders)",
                        account.email, result.newlyDiscoveredFolderIds.size, accountId,
                    )
                }
            }
        } catch (e: Exception) {
            log.warn(
                "Folder enumeration failed for {}: {}. Falling back to existing folder rows / configured quick-sync list.",
                account.email, e.message,
            )
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

                // Issue #57: de-dup against an in-flight quick sync (manual click
                // moments before the scheduler tick is the common case). Without
                // this gate the unique-timestamp job parameter lets two concurrent
                // executions race against the same mailbox.
                val running = findRunningQuickSync(accountId)
                if (running !== null) {
                    log.info(
                        "Skipping: quick sync already in flight for account {} (executionId={})",
                        account.email, running.id
                    )
                    results += AccountDispatchResult(
                        accountId, account.email, DispatchOutcome.SKIPPED_IN_PROGRESS,
                        "executionId=${running.id}"
                    )
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
    /**
     * A quick sync job for this account is already running (issue #57).
     * Common case: user clicked "Run Quick Sync" moments before the scheduler tick.
     */
    SKIPPED_IN_PROGRESS,
    /** Exception during dispatch; the loop continues for sibling accounts. */
    ERROR
}

/**
 * Pre-flight estimate displayed next to the "Sync Now" button (issue #83).
 *
 * @property neverSynced True when the account has no recorded quick-sync run yet.
 * @property lastSyncAt Timestamp of the last quick sync, or null if never synced.
 * @property estimatedNew Approximate count of new messages waiting since [lastSyncAt].
 *   For never-synced accounts this equals [totalMessages].
 * @property totalMessages Total messages across resolved target folders.
 * @property reachable True when IMAP returned a usable count. False when disabled,
 *   missing-password, or IMAP unreachable — the view uses [message] in that case.
 * @property message Human-readable note when [reachable] is false; null otherwise.
 */
data class SyncEstimate(
    val neverSynced: Boolean,
    val lastSyncAt: OffsetDateTime?,
    val estimatedNew: Long,
    val totalMessages: Long,
    val reachable: Boolean,
    val message: String?,
)

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
