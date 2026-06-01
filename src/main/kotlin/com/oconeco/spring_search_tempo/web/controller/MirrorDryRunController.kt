package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.MirrorConfigService
import com.oconeco.spring_search_tempo.base.service.MirrorDryRunResult
import com.oconeco.spring_search_tempo.base.service.MirrorDryRunService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping

/**
 * HTMX-aware web entry-point for the IMAP mirror dry-run preview (#25).
 * Lives alongside `MirrorConfigController` (#22) which owns the CRUD UI;
 * dry-run is read-only and orthogonal to CRUD, so it gets its own
 * controller rather than expanding the foundation controller's surface.
 *
 * With `HX-Request`, the inline result fragment is returned for swap;
 * without HTMX, the full mirror list page is re-rendered with the result
 * panel populated (per CLAUDE.md HTMX response-shape rules).
 */
@Controller
@RequestMapping("/emailMirrors")
class MirrorDryRunController(
    private val mirrorConfigService: MirrorConfigService,
    private val dryRunService: MirrorDryRunService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/{id}/dry-run")
    fun dryRun(
        @PathVariable id: Long,
        @RequestHeader(value = "HX-Request", required = false) hxRequest: String?,
        model: Model
    ): String {
        log.info("Dry-run requested for mirror config {} (htmx={})", id, hxRequest != null)
        val result = try {
            dryRunService.dryRun(id)
        } catch (e: IllegalArgumentException) {
            log.warn("Dry-run rejected: {}", e.message)
            // Populate a minimal NOT_FOUND view so the HTMX fragment can
            // dereference dryRun.mirrorConfigId without throwing.
            model.addAttribute("dryRun", MirrorDryRunView(
                mirrorConfigId = id,
                outcome = "NOT_FOUND",
                severity = "danger",
                heading = "Mirror config not found",
                message = e.message ?: "no such mirror config"
            ))
            return renderResponse(hxRequest, model)
        }

        model.addAttribute("dryRun", toViewModel(id, result))
        return renderResponse(hxRequest, model)
    }

    private fun renderResponse(hxRequest: String?, model: Model): String {
        return if (!hxRequest.isNullOrBlank()) {
            "emailMirror/dryRun :: result"
        } else {
            // Re-render the list with the dry-run panel inline below the table.
            model.addAttribute("mirrors", mirrorConfigService.findAll())
            "emailMirror/list"
        }
    }

    private fun toViewModel(configId: Long, result: MirrorDryRunResult): MirrorDryRunView {
        return when (result) {
            is MirrorDryRunResult.Ok -> MirrorDryRunView(
                mirrorConfigId = configId,
                outcome = "OK",
                severity = "success",
                heading = "Dry-run complete",
                message = "${result.totals.messages} message(s) to copy " +
                    "(~${humanBytes(result.totals.bytesEstimate)}); " +
                    "${result.totals.alreadyMirrored} already mirrored; " +
                    "estimated ${humanDuration(result.totals.estimatedSeconds)}.",
                perFolder = result.perFolder.map { folder ->
                    FolderDryRunView(
                        sourceFolder = folder.sourceFolder,
                        destFolder = folder.destFolder,
                        sourceMessageCount = folder.sourceMessageCount,
                        sourceBytesEstimate = folder.sourceBytesEstimate,
                        sourceBytesHuman = humanBytes(folder.sourceBytesEstimate),
                        destMessageCount = folder.destMessageCount,
                        destBytesEstimate = folder.destBytesEstimate,
                        destBytesHuman = humanBytes(folder.destBytesEstimate),
                        alreadyMirroredCount = folder.alreadyMirroredCount
                    )
                },
                totals = TotalsView(
                    messages = result.totals.messages,
                    bytesEstimate = result.totals.bytesEstimate,
                    bytesHuman = humanBytes(result.totals.bytesEstimate),
                    alreadyMirrored = result.totals.alreadyMirrored,
                    estimatedSeconds = result.totals.estimatedSeconds,
                    estimatedHuman = humanDuration(result.totals.estimatedSeconds)
                )
            )
            is MirrorDryRunResult.SourceUnreachable -> MirrorDryRunView(
                mirrorConfigId = configId,
                outcome = "SOURCE_UNREACHABLE",
                severity = "warning",
                heading = "Source unreachable",
                message = result.reason
            )
            is MirrorDryRunResult.DestUnreachable -> MirrorDryRunView(
                mirrorConfigId = configId,
                outcome = "DEST_UNREACHABLE",
                severity = "warning",
                heading = "Destination unreachable",
                message = result.reason
            )
            is MirrorDryRunResult.AuthFailed -> MirrorDryRunView(
                mirrorConfigId = configId,
                outcome = "AUTH_FAILED",
                severity = "danger",
                heading = "Authentication failed (${result.side})",
                message = result.reason
            )
        }
    }
}

data class MirrorDryRunView(
    val mirrorConfigId: Long,
    val outcome: String,
    val severity: String,
    val heading: String,
    val message: String,
    val perFolder: List<FolderDryRunView> = emptyList(),
    val totals: TotalsView? = null
)

data class FolderDryRunView(
    val sourceFolder: String,
    val destFolder: String,
    val sourceMessageCount: Long,
    val sourceBytesEstimate: Long,
    val sourceBytesHuman: String,
    val destMessageCount: Long,
    val destBytesEstimate: Long,
    val destBytesHuman: String,
    val alreadyMirroredCount: Long
)

data class TotalsView(
    val messages: Long,
    val bytesEstimate: Long,
    val bytesHuman: String,
    val alreadyMirrored: Long,
    val estimatedSeconds: Long,
    val estimatedHuman: String
)

private fun humanBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var b = bytes.toDouble() / 1024
    var i = 0
    while (b >= 1024 && i < units.lastIndex) {
        b /= 1024
        i++
    }
    return "%.1f %s".format(b, units[i])
}

private fun humanDuration(seconds: Long): String {
    if (seconds <= 0) return "<1s (no rate limit configured)"
    if (seconds < 60) return "${seconds}s"
    val m = seconds / 60
    val s = seconds % 60
    if (m < 60) return if (s == 0L) "${m}m" else "${m}m ${s}s"
    val h = m / 60
    val mm = m % 60
    return if (mm == 0L) "${h}h" else "${h}h ${mm}m"
}
