package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.batch.nlp.NLPJobLauncher
import org.slf4j.LoggerFactory
import org.springframework.batch.core.BatchStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * Web controller for NLP processing operations.
 * Provides UI actions for triggering NLP jobs.
 */
@Controller
@RequestMapping("/nlp")
class NLPController(
    private val nlpJobLauncher: NLPJobLauncher
) {
    companion object {
        private val log = LoggerFactory.getLogger(NLPController::class.java)
    }

    /**
     * Trigger NLP processing job from UI.
     *
     * POST /nlp/process
     *
     * @param redirectTo Where to redirect after triggering (default: home)
     */
    @PostMapping("/process")
    fun triggerNLPProcessing(
        @RequestParam(name = "redirectTo", defaultValue = "/") redirectTo: String,
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
}
