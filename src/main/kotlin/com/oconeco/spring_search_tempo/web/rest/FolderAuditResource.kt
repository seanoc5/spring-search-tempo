package com.oconeco.spring_search_tempo.web.rest

import com.oconeco.spring_search_tempo.base.domain.FolderAuditRun
import com.oconeco.spring_search_tempo.base.repos.FolderAuditRunRepository
import com.oconeco.spring_search_tempo.batch.audit.FolderAuditService
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

/**
 * REST API for the folder audit (issue #103).
 *
 *   POST /api/audit/folders/run        — kick off async; returns { runId }
 *   GET  /api/audit/folders/runs       — list recent runs (limit, default 50)
 *   GET  /api/audit/folders/runs/{id}  — one run with aggregates + status
 *
 * The audit pass is the reconciliation surface for "did SKIP eat the
 * tree?" — see the issue body for the architectural framing.
 */
@RestController
@RequestMapping("/api/audit/folders")
class FolderAuditResource(
    private val folderAuditService: FolderAuditService,
    private val folderAuditRunRepository: FolderAuditRunRepository
) {

    companion object {
        private val log = LoggerFactory.getLogger(FolderAuditResource::class.java)
    }

    @PostMapping("/run")
    fun startRun(): ResponseEntity<FolderAuditRunStartResponse> {
        log.info("REST API request to start folder audit run")
        val runId = folderAuditService.startFilesystemAuditRun()
        return ResponseEntity.accepted().body(FolderAuditRunStartResponse(runId = runId))
    }

    @GetMapping("/runs")
    fun listRuns(
        @RequestParam(name = "limit", required = false, defaultValue = "50") rawLimit: Int
    ): ResponseEntity<List<FolderAuditRunResponse>> {
        val limit = rawLimit.coerceIn(1, 500)
        val runs = folderAuditRunRepository.findAllByOrderByStartedDesc(PageRequest.of(0, limit))
        return ResponseEntity.ok(runs.map { it.toResponse() })
    }

    @GetMapping("/runs/{id}")
    fun getRun(@PathVariable id: Long): ResponseEntity<FolderAuditRunResponse> {
        val run = folderAuditRunRepository.findById(id).orElse(null)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(run.toResponse())
    }

    private fun FolderAuditRun.toResponse() = FolderAuditRunResponse(
        id = id ?: 0,
        started = started,
        finished = finished,
        sourceType = sourceType.name,
        sourceRef = sourceRef,
        totalFolders = totalFolders,
        skipSubtreeCount = skipSubtreeCount,
        hiddenGemCount = hiddenGemCount,
        status = status.name,
        errorMessage = errorMessage
    )
}

data class FolderAuditRunStartResponse(val runId: Long)

data class FolderAuditRunResponse(
    val id: Long,
    val started: OffsetDateTime?,
    val finished: OffsetDateTime?,
    val sourceType: String,
    val sourceRef: String?,
    val totalFolders: Long,
    val skipSubtreeCount: Long,
    val hiddenGemCount: Long,
    val status: String,
    val errorMessage: String?
)
