package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.service.NLPStatusViewService
import com.oconeco.spring_search_tempo.batch.nlp.NLPChunkReprocessor
import com.oconeco.spring_search_tempo.batch.nlp.NLPJobLauncher
import org.slf4j.LoggerFactory
import org.springframework.batch.core.BatchStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * Web controller for NLP processing operations.
 *
 * - `GET /nlp/status` renders the operator-facing NLP coverage dashboard
 *   (HTML counterpart to `GET /api/nlp/status`).
 * - `POST /nlp/process` triggers the NLP batch job and redirects to a
 *   caller-provided URL (defaults to `/nlp/status`).
 * - `POST /nlp/process/chunk/{id}` re-runs NLP on a single chunk synchronously,
 *   used by the "Re-process chunk N" form on the dashboard.
 */
@Controller
@RequestMapping("/nlp")
class NLPController(
    private val nlpJobLauncher: NLPJobLauncher,
    private val nlpStatusViewService: NLPStatusViewService,
    private val nlpChunkReprocessor: NLPChunkReprocessor
) {
    companion object {
        private val log = LoggerFactory.getLogger(NLPController::class.java)
    }

    @GetMapping("/status")
    fun statusPage(model: Model): String {
        model.addAttribute("nlpStatus", nlpStatusViewService.loadStatus())
        return "nlp/status"
    }

    @PostMapping("/process")
    fun triggerNLPProcessing(
        @RequestParam(name = "redirectTo", defaultValue = "/nlp/status") redirectTo: String,
        redirectAttributes: RedirectAttributes
    ): String {
        log.info("UI request to trigger NLP processing")

        try {
            val execution = nlpJobLauncher.launchNLPJob(triggeredBy = "ui")

            val statusMessage = when (execution.status) {
                BatchStatus.COMPLETED -> "NLP processing completed successfully"
                BatchStatus.STARTED, BatchStatus.STARTING -> "NLP processing job started (execution ID: ${execution.id})"
                else -> "NLP processing job status: ${execution.status}"
            }

            redirectAttributes.addFlashAttribute("message", statusMessage)
            log.info("NLP processing triggered from UI: status={}, executionId={}",
                execution.status, execution.id)

        } catch (e: Exception) {
            log.error("Failed to trigger NLP processing from UI", e)
            redirectAttributes.addFlashAttribute("error",
                "Failed to start NLP processing: ${e.message}")
        }

        return "redirect:$redirectTo"
    }

    @PostMapping("/process/chunk/{id}")
    fun reprocessChunk(
        @PathVariable id: Long,
        redirectAttributes: RedirectAttributes
    ): String {
        log.info("UI request to re-process chunk {}", id)
        try {
            val success = nlpChunkReprocessor.reprocess(id)
            if (success) {
                redirectAttributes.addFlashAttribute("message", "Re-processed NLP for chunk $id")
            } else {
                redirectAttributes.addFlashAttribute("error",
                    "Could not re-process chunk $id (missing or has no text)")
            }
        } catch (e: Exception) {
            log.error("Failed to re-process chunk {}", id, e)
            redirectAttributes.addFlashAttribute("error",
                "Failed to re-process chunk $id: ${e.message}")
        }
        return "redirect:/nlp/status"
    }
}
