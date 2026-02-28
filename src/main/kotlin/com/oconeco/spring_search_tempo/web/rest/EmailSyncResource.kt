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
     * POST /api/email/sync
     * POST /api/email/sync?forceFullSync=true  (full recrawl)
     *
     * Parallelization parameters for benchmarking:
     * - stepThreads: Number of threads for chunk-level parallelism (default: 1)
     * - itemAsync: Whether to use AsyncItemProcessor (default: false)
     * - asyncThreads: Number of threads for async item processing (default: 4)
     * - chunkSize: Number of items per chunk (default: 20)
     *
     * Example configurations:
     * - Serial (baseline): stepThreads=1, itemAsync=false
     * - TaskExecutor only: stepThreads=4, itemAsync=false
     * - AsyncItemProcessor only: stepThreads=1, itemAsync=true, asyncThreads=4
     * - Combined: stepThreads=2, itemAsync=true, asyncThreads=4
     *
     * @param forceFullSync If true, ignore lastSyncUid and fetch all messages
     * @param stepThreads Number of threads for step-level parallelism
     * @param itemAsync Whether to use AsyncItemProcessor
     * @param asyncThreads Number of threads for AsyncItemProcessor
     * @param chunkSize Number of items per chunk
     * @return Map of account email to job execution status with timing info
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

        val startTime = System.currentTimeMillis()

        return try {
            val results = emailCrawlOrchestrator.runQuickSync(
                forceFullSync = forceFullSync,
                stepThreads = stepThreads,
                itemAsync = itemAsync,
                asyncThreads = asyncThreads,
                chunkSize = chunkSize
            )
            val elapsed = System.currentTimeMillis() - startTime
            val syncType = if (forceFullSync) "Full sync" else "Sync"

            ResponseEntity.ok(
                EmailSyncResponse(
                    status = "COMPLETED",
                    message = "$syncType completed in ${elapsed}ms",
                    results = results + mapOf(
                        "elapsedMs" to elapsed.toString(),
                        "parallelConfig" to parallelConfig.modeName
                    )
                )
            )
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            log.error("Failed to sync email accounts via API after {}ms", elapsed, e)
            ResponseEntity.internalServerError().body(
                EmailSyncResponse(
                    status = "FAILED",
                    message = "Failed to sync email accounts: ${e.message}",
                    results = mapOf("elapsedMs" to elapsed.toString())
                )
            )
        }
    }

    /**
     * Sync a specific email account.
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
     * @return Job execution status
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

        val startTime = System.currentTimeMillis()

        return try {
            val status = emailCrawlOrchestrator.runQuickSyncForAccount(accountId, forceFullSync, parallelConfig)
            val elapsed = System.currentTimeMillis() - startTime
            val syncType = if (forceFullSync) "Full sync" else "Sync"

            ResponseEntity.ok(
                EmailSyncResponse(
                    status = status,
                    message = "$syncType completed in ${elapsed}ms",
                    results = mapOf(
                        "accountId" to accountId.toString(),
                        "status" to status,
                        "syncType" to if (forceFullSync) "full" else "incremental",
                        "elapsedMs" to elapsed.toString(),
                        "parallelConfig" to parallelConfig.modeName
                    )
                )
            )
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            log.error("Failed to sync email account {} via API after {}ms", accountId, elapsed, e)
            ResponseEntity.internalServerError().body(
                EmailSyncResponse(
                    status = "FAILED",
                    message = "Failed to sync email account $accountId: ${e.message}",
                    results = mapOf("elapsedMs" to elapsed.toString())
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
