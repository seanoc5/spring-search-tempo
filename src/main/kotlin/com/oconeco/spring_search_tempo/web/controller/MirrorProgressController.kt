package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.MirrorConfigService
import com.oconeco.spring_search_tempo.base.repos.MirrorErrorRepository
import com.oconeco.spring_search_tempo.base.service.MirrorProgressService
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import jakarta.servlet.http.HttpServletResponse
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.time.format.DateTimeFormatter

/**
 * Mirror progress dashboard (#26): per-run status, per-folder progress,
 * error log, and a CSV export of the run's errors.
 *
 *  - `GET /emailMirrors/{id}/runs/{jobRunId}` — full page.
 *  - `GET /emailMirrors/{id}/runs/{jobRunId}/progress` — HTMX fragment
 *    refreshed every 5s while the run is `RUNNING`; the fragment stops
 *    emitting an `hx-trigger` once it completes, which is how the
 *    auto-refresh stops on its own.
 *  - `GET /emailMirrors/{id}/runs/{jobRunId}/errors.csv` — streamed
 *    CSV download of the error log for that run.
 */
@Controller
@RequestMapping("/emailMirrors")
class MirrorProgressController(
    private val mirrorConfigService: MirrorConfigService,
    private val emailAccountService: EmailAccountService,
    private val mirrorProgressService: MirrorProgressService,
    private val mirrorErrorRepository: MirrorErrorRepository
) {

    @GetMapping("/{id}/runs/{jobRunId}")
    fun runDashboard(
        @PathVariable id: Long,
        @PathVariable jobRunId: Long,
        @RequestHeader(value = "HX-Request", required = false) hxRequest: String?,
        @RequestHeader(value = "HX-Boosted", required = false) hxBoosted: String?,
        model: Model
    ): String {
        val view = mirrorProgressService.loadProgress(id, jobRunId)
        val mirror = mirrorConfigService.get(id)
        val sourceAccount = mirror.sourceAccountId?.let { runCatching { emailAccountService.get(it) }.getOrNull() }
        val destAccount = mirror.destAccountId?.let { runCatching { emailAccountService.get(it) }.getOrNull() }

        model.addAttribute("progress", view)
        model.addAttribute("mirror", mirror)
        model.addAttribute("sourceAccount", sourceAccount)
        model.addAttribute("destAccount", destAccount)

        // Non-boosted HTMX request asks for just the progress fragment;
        // every other request gets the full page.
        return if (!hxRequest.isNullOrBlank() && hxBoosted.isNullOrBlank()) {
            "emailMirror/runProgress :: progress"
        } else {
            "emailMirror/run"
        }
    }

    @GetMapping("/{id}/runs/{jobRunId}/progress")
    fun runProgressFragment(
        @PathVariable id: Long,
        @PathVariable jobRunId: Long,
        model: Model
    ): String {
        val view = mirrorProgressService.loadProgress(id, jobRunId)
        model.addAttribute("progress", view)
        return "emailMirror/runProgress :: progress"
    }

    @GetMapping("/{id}/runs/{jobRunId}/errors.csv", produces = ["text/csv"])
    fun errorsCsv(
        @PathVariable id: Long,
        @PathVariable jobRunId: Long,
        response: HttpServletResponse
    ): ResponseEntity<StreamingResponseBody> {
        // Validate ownership early so a bad URL fails fast without
        // opening the streaming cursor.
        val jobRun = try {
            mirrorProgressService.getJobRunOrThrow(jobRunId)
        } catch (e: NotFoundException) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        }
        if (jobRun.mirrorConfig?.id != id) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        }

        response.setHeader(
            "Content-Disposition",
            "attachment; filename=\"mirror-${id}-run-${jobRunId}-errors.csv\""
        )

        val body = StreamingResponseBody { out ->
            out.writer(Charsets.UTF_8).buffered().use { w ->
                w.append("occurred_at,source_folder,source_uid,dest_folder,message_id,retryable,reason\n")
                // Page-based iteration keeps each batch inside its own
                // transaction (StreamingResponseBody runs after the
                // controller method returns, so a JPA `Stream` couldn't
                // hold its cursor open). Page size matches the dashboard
                // window — small enough to keep memory bounded, large
                // enough to amortize round-trips.
                var pageIdx = 0
                val pageSize = 500
                val sort = Sort.by("occurredAt").ascending().and(Sort.by("id").ascending())
                while (true) {
                    val page = mirrorErrorRepository
                        .findByMirrorConfigIdAndJobRunIdOrderByOccurredAtAsc(
                            id, jobRunId, PageRequest.of(pageIdx, pageSize, sort)
                        )
                    if (page.isEmpty) break
                    for (err in page.content) {
                        w.append(csvCell(err.occurredAt?.format(ISO_TS))).append(',')
                            .append(csvCell(err.sourceFolder)).append(',')
                            .append(csvCell(err.sourceUid?.toString())).append(',')
                            .append(csvCell(err.destFolder)).append(',')
                            .append(csvCell(err.messageId)).append(',')
                            .append(err.retryable.toString()).append(',')
                            .append(csvCell(err.reason)).append('\n')
                    }
                    w.flush()
                    if (!page.hasNext()) break
                    pageIdx++
                }
            }
        }
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(body)
    }

    companion object {
        private val ISO_TS: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

        private fun csvCell(value: String?): String {
            val raw = value ?: return ""
            val needsQuote = raw.contains(',') || raw.contains('"') || raw.contains('\n') || raw.contains('\r')
            return if (needsQuote) "\"" + raw.replace("\"", "\"\"") + "\"" else raw
        }
    }
}
