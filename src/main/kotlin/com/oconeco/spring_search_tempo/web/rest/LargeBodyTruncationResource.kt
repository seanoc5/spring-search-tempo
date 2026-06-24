package com.oconeco.spring_search_tempo.web.rest

import com.oconeco.spring_search_tempo.base.EmailMessageService
import com.oconeco.spring_search_tempo.base.FSFileService
import com.oconeco.spring_search_tempo.base.config.CrawlConfiguration
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Backfill endpoints for ADR-006 (issues #147 and #161).
 *
 * `POST /api/admin/truncate-large-bodies` walks already-chunked FSFile rows
 * whose `body_text` exceeds the configured threshold and truncates them.
 * `POST /api/admin/truncate-large-email-bodies` does the same for
 * EmailMessage rows that already have ContentChunk children.
 *
 * Rows without chunks are intentionally left alone — their full content
 * isn't represented in `ContentChunk` yet, so the normal chunking pipeline
 * needs to run first.
 *
 * Threshold defaults to `app.crawl.large-body-threshold-chars`; callers
 * can override via `?threshold=N` (handy for one-off tighter sweeps
 * without changing the config).
 */
@RestController
@RequestMapping("/api/admin")
class LargeBodyTruncationResource(
    private val fileService: FSFileService,
    private val emailMessageService: EmailMessageService,
    private val crawlConfiguration: CrawlConfiguration
) {
    companion object {
        private val log = LoggerFactory.getLogger(LargeBodyTruncationResource::class.java)
    }

    @PostMapping("/truncate-large-bodies")
    fun truncateLargeBodies(
        @RequestParam(name = "threshold", required = false) threshold: Long?,
        @RequestParam(name = "batchSize", defaultValue = "100") batchSize: Int
    ): ResponseEntity<TruncationResponse> {
        val effectiveThreshold = threshold ?: crawlConfiguration.largeBodyThresholdChars
        log.info(
            "FSFile backfill truncation requested: threshold={} chars, batchSize={}",
            effectiveThreshold, batchSize
        )

        if (effectiveThreshold <= 0) {
            return ResponseEntity.badRequest().body(
                TruncationResponse(
                    thresholdChars = effectiveThreshold,
                    rowsTruncated = 0,
                    message = "Threshold must be > 0; configured default disables truncation."
                )
            )
        }

        val touched = fileService.truncateLargeBodyTextBackfill(effectiveThreshold, batchSize)
        log.info("FSFile backfill truncation done: {} files truncated", touched)
        return ResponseEntity.ok(
            TruncationResponse(
                thresholdChars = effectiveThreshold,
                rowsTruncated = touched,
                message = "Truncated $touched chunked files whose body_text exceeded $effectiveThreshold chars."
            )
        )
    }

    @PostMapping("/truncate-large-email-bodies")
    fun truncateLargeEmailBodies(
        @RequestParam(name = "threshold", required = false) threshold: Long?,
        @RequestParam(name = "batchSize", defaultValue = "100") batchSize: Int
    ): ResponseEntity<TruncationResponse> {
        val effectiveThreshold = threshold ?: crawlConfiguration.largeBodyThresholdChars
        log.info(
            "EmailMessage backfill truncation requested: threshold={} chars, batchSize={}",
            effectiveThreshold, batchSize
        )

        if (effectiveThreshold <= 0) {
            return ResponseEntity.badRequest().body(
                TruncationResponse(
                    thresholdChars = effectiveThreshold,
                    rowsTruncated = 0,
                    message = "Threshold must be > 0; configured default disables truncation."
                )
            )
        }

        val touched = emailMessageService.truncateLargeBodyTextBackfill(effectiveThreshold, batchSize)
        log.info("EmailMessage backfill truncation done: {} messages truncated", touched)
        return ResponseEntity.ok(
            TruncationResponse(
                thresholdChars = effectiveThreshold,
                rowsTruncated = touched,
                message = "Truncated $touched chunked email messages whose body_text exceeded $effectiveThreshold chars."
            )
        )
    }
}

data class TruncationResponse(
    val thresholdChars: Long,
    val rowsTruncated: Int,
    val message: String
)
