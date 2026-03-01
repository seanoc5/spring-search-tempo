package com.oconeco.spring_search_tempo.web.rest

import com.oconeco.spring_search_tempo.batch.emailcrawl.AccountSyncStatus
import com.oconeco.spring_search_tempo.batch.emailcrawl.EmailCrawlOrchestrator
import com.oconeco.spring_search_tempo.batch.emailcrawl.ParallelizationConfig
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * REST API for email sync operations.
 */
@RestController
@RequestMapping("/api/email")
class EmailSyncResource(
    private val emailCrawlOrchestrator: EmailCrawlOrchestrator
) {
    companion object {
        private val log = LoggerFactory.getLogger(EmailSyncResource::class.java)
    }

    /**
     * Sync all enabled email accounts.
     *
     * Jobs are launched asynchronously -- this endpoint returns immediately
     * with STARTED status. Check /batch page or /api/email/status for progress.
     *
     * POST /api/email/sync
     * POST /api/email/sync?forceFullSync=true  (full recrawl)
     *
     * Parallelization parameters for benchmarking:
     * - stepThreads: Number of threads for chunk-level parallelism (default: 1)
     * - itemAsync: Whether to use AsyncItemProcessor (default: false)
     * - asyncThreads: Number of threads for async item processing (default: 4)
     * - chunkSize: Number of items per chunk (default: 20)
     *
     * @param forceFullSync If true, ignore lastSyncUid and fetch all messages
     * @param stepThreads Number of threads for step-level parallelism
     * @param itemAsync Whether to use AsyncItemProcessor
     * @param asyncThreads Number of threads for AsyncItemProcessor
     * @param chunkSize Number of items per chunk
     * @return Map of account email to job launch status (STARTED with execution IDs)
     */
    @PostMapping("/sync")
    fun syncAllAccounts(
        @RequestParam(name = "forceFullSync", required = false, defaultValue = "false") forceFullSync: Boolean,
        @RequestParam(name = "stepThreads", required = false, defaultValue = "1") stepThreads: Int,
        @RequestParam(name = "itemAsync", required = false, defaultValue = "false") itemAsync: Boolean,
        @RequestParam(name = "asyncThreads", required = false, defaultValue = "4") asyncThreads: Int,
        @RequestParam(name = "chunkSize", required = false, defaultValue = "20") chunkSize: Int
    ): ResponseEntity<EmailSyncResponse> {
        val parallelConfig = ParallelizationConfig(stepThreads, itemAsync, asyncThreads, chunkSize)
        log.info("REST API request to {} all email accounts with {}",
            if (forceFullSync) "FULL sync" else "sync", parallelConfig)

        return try {
            val results = emailCrawlOrchestrator.runQuickSync(
                forceFullSync = forceFullSync,
                stepThreads = stepThreads,
                itemAsync = itemAsync,
                asyncThreads = asyncThreads,
                chunkSize = chunkSize
            )
            val syncType = if (forceFullSync) "Full sync" else "Sync"

            ResponseEntity.accepted().body(
                EmailSyncResponse(
                    status = "STARTED",
                    message = "$syncType jobs launched for all enabled accounts",
                    results = results + mapOf("parallelConfig" to parallelConfig.modeName)
                )
            )
        } catch (e: Exception) {
            log.error("Failed to launch email sync jobs via API", e)
            ResponseEntity.internalServerError().body(
                EmailSyncResponse(
                    status = "FAILED",
                    message = "Failed to launch email sync: ${e.message}",
                    results = emptyMap()
                )
            )
        }
    }

    /**
     * Sync a specific email account.
     *
     * The job is launched asynchronously -- this endpoint returns immediately
     * with STARTED status and the execution ID for tracking.
     *
     * POST /api/email/sync/{accountId}
     * POST /api/email/sync/{accountId}?forceFullSync=true  (full recrawl)
     *
     * @param accountId The account ID to sync
     * @param forceFullSync If true, ignore lastSyncUid and fetch all messages
     * @param stepThreads Number of threads for step-level parallelism
     * @param itemAsync Whether to use AsyncItemProcessor
     * @param asyncThreads Number of threads for AsyncItemProcessor
     * @param chunkSize Number of items per chunk
     * @return Job launch status with execution ID
     */
    @PostMapping("/sync/{accountId}")
    fun syncAccount(
        @PathVariable accountId: Long,
        @RequestParam(name = "forceFullSync", required = false, defaultValue = "false") forceFullSync: Boolean,
        @RequestParam(name = "stepThreads", required = false, defaultValue = "1") stepThreads: Int,
        @RequestParam(name = "itemAsync", required = false, defaultValue = "false") itemAsync: Boolean,
        @RequestParam(name = "asyncThreads", required = false, defaultValue = "4") asyncThreads: Int,
        @RequestParam(name = "chunkSize", required = false, defaultValue = "20") chunkSize: Int
    ): ResponseEntity<EmailSyncResponse> {
        val parallelConfig = ParallelizationConfig(stepThreads, itemAsync, asyncThreads, chunkSize)
        log.info("REST API request to {} email account: {} with {}",
            if (forceFullSync) "FULL sync" else "sync", accountId, parallelConfig)

        return try {
            val execution = emailCrawlOrchestrator.runQuickSyncForAccount(
                    accountId = accountId,
                    forceFullSync = forceFullSync,
                    parallelConfig = parallelConfig
                )
            val syncType = if (forceFullSync) "Full sync" else "Sync"

            ResponseEntity.accepted().body(
                EmailSyncResponse(
                    status = "STARTED",
                    message = "$syncType job launched for account $accountId",
                    results = mapOf(
                        "accountId" to accountId.toString(),
                        "executionId" to execution.id.toString(),
                        "status" to execution.status.toString(),
                        "syncType" to if (forceFullSync) "full" else "incremental",
                        "parallelConfig" to parallelConfig.modeName
                    )
                )
            )
        } catch (e: Exception) {
            log.error("Failed to launch email sync for account {} via API", accountId, e)
            ResponseEntity.internalServerError().body(
                EmailSyncResponse(
                    status = "FAILED",
                    message = "Failed to launch email sync for account $accountId: ${e.message}",
                    results = emptyMap()
                )
            )
        }
    }

    /**
     * Test IMAP connection for a specific account.
     *
     * POST /api/email/test/{accountId}
     *
     * @param accountId The account ID to test
     * @return Connection test result
     */
    @PostMapping("/test/{accountId}")
    fun testConnection(@PathVariable accountId: Long): ResponseEntity<ConnectionTestResponse> {
        log.info("REST API request to test connection for account: {}", accountId)

        return try {
            val success = emailCrawlOrchestrator.testConnection(accountId)
            if (success) {
                ResponseEntity.ok(
                    ConnectionTestResponse(
                        accountId = accountId,
                        success = true,
                        message = "Connection test successful"
                    )
                )
            } else {
                ResponseEntity.ok(
                    ConnectionTestResponse(
                        accountId = accountId,
                        success = false,
                        message = "Connection test failed"
                    )
                )
            }
        } catch (e: Exception) {
            log.error("Connection test failed for account {} via API", accountId, e)
            ResponseEntity.internalServerError().body(
                ConnectionTestResponse(
                    accountId = accountId,
                    success = false,
                    message = "Connection test error: ${e.message}"
                )
            )
        }
    }

    /**
     * Get sync status for all email accounts.
     *
     * GET /api/email/status
     *
     * @return List of account sync statuses
     */
    @GetMapping("/status")
    fun getSyncStatus(): ResponseEntity<List<AccountSyncStatus>> {
        log.debug("REST API request to get email sync status")
        return ResponseEntity.ok(emailCrawlOrchestrator.getSyncStatus())
    }
}

/**
 * Response DTO for email sync operations.
 */
data class EmailSyncResponse(
    val status: String,
    val message: String,
    val results: Map<String, String>
)

/**
 * Response DTO for connection test operations.
 */
data class ConnectionTestResponse(
    val accountId: Long,
    val success: Boolean,
    val message: String
)
