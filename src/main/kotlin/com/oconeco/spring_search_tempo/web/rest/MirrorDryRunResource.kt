package com.oconeco.spring_search_tempo.web.rest

import com.oconeco.spring_search_tempo.base.service.DryRunTotals
import com.oconeco.spring_search_tempo.base.service.FolderDryRun
import com.oconeco.spring_search_tempo.base.service.MirrorDryRunResult
import com.oconeco.spring_search_tempo.base.service.MirrorDryRunService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST endpoint that runs a read-only dry-run probe against a configured
 * `MirrorConfig`, returning per-folder counts + size estimate + the
 * projected runtime. Pairs with `MirrorDryRunService` — see that service
 * for probe semantics.
 *
 * Idempotent; writes nothing on either side.
 */
@RestController
@RequestMapping("/api/email/mirrors")
class MirrorDryRunResource(
    private val service: MirrorDryRunService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/{id}/dry-run")
    fun dryRun(@PathVariable id: Long): ResponseEntity<MirrorDryRunResponse> {
        log.info("Dry-run requested for mirror config {}", id)
        val result = try {
            service.dryRun(id)
        } catch (e: IllegalArgumentException) {
            return ResponseEntity.status(404).body(
                MirrorDryRunResponse(outcome = "NOT_FOUND", message = e.message ?: "mirror config not found")
            )
        }
        val body = MirrorDryRunResponse.from(result)
        // Source/dest unreachable + auth failure are upstream problems, not
        // client problems — surface them as 502 Bad Gateway. `Ok` returns 200.
        val status = when (result) {
            is MirrorDryRunResult.Ok -> 200
            else -> 502
        }
        return ResponseEntity.status(status).body(body)
    }
}

/**
 * Wire-shape for `MirrorDryRunResult`. `outcome` is the discriminator;
 * the remaining fields are populated based on the variant.
 */
data class MirrorDryRunResponse(
    val outcome: String,
    val message: String,
    val perFolder: List<FolderDryRun>? = null,
    val totals: DryRunTotals? = null,
    val side: String? = null
) {
    companion object {
        fun from(result: MirrorDryRunResult): MirrorDryRunResponse = when (result) {
            is MirrorDryRunResult.Ok -> MirrorDryRunResponse(
                outcome = "OK",
                message = "Dry-run complete: ${result.totals.messages} message(s) to copy, " +
                    "${result.totals.alreadyMirrored} already mirrored",
                perFolder = result.perFolder,
                totals = result.totals
            )
            is MirrorDryRunResult.SourceUnreachable -> MirrorDryRunResponse(
                outcome = "SOURCE_UNREACHABLE",
                message = "Source unreachable: ${result.reason}",
                side = "SOURCE"
            )
            is MirrorDryRunResult.DestUnreachable -> MirrorDryRunResponse(
                outcome = "DEST_UNREACHABLE",
                message = "Destination unreachable: ${result.reason}",
                side = "DEST"
            )
            is MirrorDryRunResult.AuthFailed -> MirrorDryRunResponse(
                outcome = "AUTH_FAILED",
                message = "Authentication failed (${result.side}): ${result.reason}",
                side = result.side.name
            )
        }
    }
}
