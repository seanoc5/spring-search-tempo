package com.oconeco.spring_search_tempo.web.rest

import com.oconeco.spring_search_tempo.batch.onedrivesync.OneDriveSyncOrchestrator
import com.oconeco.spring_search_tempo.batch.onedrivesync.OneDriveSyncStatus
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*


/**
 * REST API for OneDrive sync operations.
 */
@RestController
@RequestMapping("/api/onedrive")
class OneDriveSyncResource(
    private val syncOrchestrator: OneDriveSyncOrchestrator
) {
    companion object {
        private val log = LoggerFactory.getLogger(OneDriveSyncResource::class.java)
    }

    /**
     * Sync all enabled OneDrive accounts.
     *
     * POST /api/onedrive/sync
     * POST /api/onedrive/sync?forceFullSync=true
     */
    @PostMapping("/sync")
    fun syncAll(
        @RequestParam(name = "forceFullSync", required = false, defaultValue = "false") forceFullSync: Boolean
    ): ResponseEntity<OneDriveSyncResponse> {
        log.info("REST API request to {} all OneDrive accounts",
            if (forceFullSync) "FULL sync" else "sync")

        val startTime = System.currentTimeMillis()

        return try {
            val results = syncOrchestrator.runSync(forceFullSync)
            val elapsed = System.currentTimeMillis() - startTime

            ResponseEntity.ok(OneDriveSyncResponse(
                status = "COMPLETED",
                message = "Sync completed in ${elapsed}ms",
                results = results + mapOf("elapsedMs" to elapsed.toString())
            ))
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            log.error("Failed to sync OneDrive accounts via API after {}ms", elapsed, e)
            ResponseEntity.internalServerError().body(OneDriveSyncResponse(
                status = "FAILED",
                message = "Failed to sync: ${e.message}",
                results = mapOf("elapsedMs" to elapsed.toString())
            ))
        }
    }

    /**
     * Sync a specific OneDrive account.
     *
     * POST /api/onedrive/sync/{accountId}
     */
    @PostMapping("/sync/{accountId}")
    fun syncAccount(
        @PathVariable accountId: Long,
        @RequestParam(name = "forceFullSync", required = false, defaultValue = "false") forceFullSync: Boolean
    ): ResponseEntity<OneDriveSyncResponse> {
        log.info("REST API request to {} OneDrive account: {}",
            if (forceFullSync) "FULL sync" else "sync", accountId)

        val startTime = System.currentTimeMillis()

        return try {
            val status = syncOrchestrator.runSyncForAccount(accountId, forceFullSync)
            val elapsed = System.currentTimeMillis() - startTime

            ResponseEntity.ok(OneDriveSyncResponse(
                status = status,
                message = "Sync completed in ${elapsed}ms",
                results = mapOf(
                    "accountId" to accountId.toString(),
                    "status" to status,
                    "elapsedMs" to elapsed.toString()
                )
            ))
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            log.error("Failed to sync OneDrive account {} via API after {}ms", accountId, elapsed, e)
            ResponseEntity.internalServerError().body(OneDriveSyncResponse(
                status = "FAILED",
                message = "Failed to sync account $accountId: ${e.message}",
                results = mapOf("elapsedMs" to elapsed.toString())
            ))
        }
    }

    /**
     * Get sync status for all OneDrive accounts.
     *
     * GET /api/onedrive/status
     */
    @GetMapping("/status")
    fun getSyncStatus(): ResponseEntity<List<OneDriveSyncStatus>> {
        log.debug("REST API request to get OneDrive sync status")
        return ResponseEntity.ok(syncOrchestrator.getSyncStatus())
    }
}


data class OneDriveSyncResponse(
    val status: String,
    val message: String,
    val results: Map<String, String>
)
