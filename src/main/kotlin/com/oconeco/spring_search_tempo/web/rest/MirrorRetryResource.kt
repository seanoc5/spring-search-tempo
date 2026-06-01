package com.oconeco.spring_search_tempo.web.rest

import com.oconeco.spring_search_tempo.base.MirrorConfigService
import com.oconeco.spring_search_tempo.base.service.mirror.MirrorRetryService
import com.oconeco.spring_search_tempo.base.service.mirror.RetrySummary
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Re-queues only the retryable failures recorded against a mirror
 * config (issue #26). Bounded to `MirrorError(retryable=true)` rows so
 * the operator never accidentally resurrects a permanent failure path.
 */
@RestController
@RequestMapping("/api/email/mirrors")
class MirrorRetryResource(
    private val mirrorConfigService: MirrorConfigService,
    private val mirrorRetryService: MirrorRetryService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/{id}/retry-failed")
    fun retryFailed(@PathVariable id: Long): ResponseEntity<RetrySummary> {
        try {
            mirrorConfigService.get(id)
        } catch (e: NotFoundException) {
            return ResponseEntity.status(404).build()
        }
        return try {
            val summary = mirrorRetryService.retryFailed(id)
            ResponseEntity.ok(summary)
        } catch (e: Exception) {
            log.error("retry-failed for mirrorConfigId={} failed", id, e)
            ResponseEntity.internalServerError().build()
        }
    }
}
