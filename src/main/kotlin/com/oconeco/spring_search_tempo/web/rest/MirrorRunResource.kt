package com.oconeco.spring_search_tempo.web.rest

import com.oconeco.spring_search_tempo.base.MirrorConfigService
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import com.oconeco.spring_search_tempo.batch.mirror.MirrorJobLauncher
import org.slf4j.LoggerFactory
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException
import org.springframework.batch.core.repository.JobRestartException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Enqueues a `MirrorJob` run for a given `MirrorConfig` (issue #24).
 * Pairs with [MirrorDryRunResource]: dry-run probes, this kicks off the
 * real one-way copy. Idempotent in the sense that `ImapMirrorService`
 * dedups on `Message-ID` — pressing the button twice in a row produces
 * one job execution and zero duplicate messages on the destination.
 */
@RestController
@RequestMapping("/api/email/mirrors")
class MirrorRunResource(
    private val mirrorConfigService: MirrorConfigService,
    private val mirrorJobLauncher: MirrorJobLauncher
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/{id}/run")
    fun run(@PathVariable id: Long): ResponseEntity<MirrorRunResponse> {
        try {
            mirrorConfigService.get(id)
        } catch (e: NotFoundException) {
            return ResponseEntity.status(404).body(
                MirrorRunResponse(status = "NOT_FOUND", message = "MirrorConfig $id not found")
            )
        }

        return try {
            val exec = mirrorJobLauncher.launch(id, triggeredBy = "api")
            ResponseEntity.accepted().body(
                MirrorRunResponse(
                    status = exec.status.name,
                    message = "MirrorJob enqueued (executionId=${exec.id})",
                    executionId = exec.id,
                    jobInstanceId = exec.jobInstance.instanceId
                )
            )
        } catch (e: JobExecutionAlreadyRunningException) {
            ResponseEntity.status(409).body(
                MirrorRunResponse(status = "ALREADY_RUNNING", message = e.message ?: "already running")
            )
        } catch (e: JobInstanceAlreadyCompleteException) {
            ResponseEntity.status(409).body(
                MirrorRunResponse(status = "ALREADY_COMPLETE", message = e.message ?: "already complete")
            )
        } catch (e: JobRestartException) {
            ResponseEntity.status(409).body(
                MirrorRunResponse(status = "CANNOT_RESTART", message = e.message ?: "cannot restart")
            )
        } catch (e: Exception) {
            log.error("Failed to launch MirrorJob for mirrorConfigId={}", id, e)
            ResponseEntity.internalServerError().body(
                MirrorRunResponse(status = "ERROR", message = e.message ?: "internal error")
            )
        }
    }
}

data class MirrorRunResponse(
    val status: String,
    val message: String,
    val executionId: Long? = null,
    val jobInstanceId: Long? = null
)
