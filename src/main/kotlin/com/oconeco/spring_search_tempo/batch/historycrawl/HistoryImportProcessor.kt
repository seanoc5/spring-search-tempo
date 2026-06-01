package com.oconeco.spring_search_tempo.batch.historycrawl

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.BrowserSourceType
import com.oconeco.spring_search_tempo.base.domain.Status
import com.oconeco.spring_search_tempo.base.model.BrowserBookmarkDTO
import com.oconeco.spring_search_tempo.base.repos.BrowserBookmarkRepository
import com.oconeco.spring_search_tempo.base.service.FirefoxPlacesService.FirefoxHistoryData
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemProcessor
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong


/**
 * Processes a Firefox history entry into a [HistoryProcessorResult].
 *
 * - If the URL already exists (as bookmark or older history row) the
 *   processor emits an UPDATE result with refreshed `visit_count` and
 *   `last_visit_date`; tags / folderPath / sourceType are left alone.
 * - Otherwise it emits a CREATE result with `sourceType=HISTORY`.
 *
 * The processor also tracks the maximum `last_visit_date` (Firefox PRTime)
 * seen across all entries so the listener can persist it as the
 * incremental-sync watermark for the next run.
 */
class HistoryImportProcessor(
    private val browserBookmarkRepository: BrowserBookmarkRepository,
    private val browserProfileId: Long
) : ItemProcessor<FirefoxHistoryData, HistoryProcessorResult> {

    companion object {
        private val log = LoggerFactory.getLogger(HistoryImportProcessor::class.java)
    }

    private val processedCount = AtomicInteger(0)
    private val createdCount = AtomicInteger(0)
    private val updatedCount = AtomicInteger(0)
    private val errorCount = AtomicInteger(0)
    private val maxVisitPrTime = AtomicLong(0L)

    override fun process(item: FirefoxHistoryData): HistoryProcessorResult? {
        processedCount.incrementAndGet()
        maxVisitPrTime.accumulateAndGet(item.lastVisitDatePrTime) { a, b -> maxOf(a, b) }

        return try {
            val existing = browserBookmarkRepository.findByUrl(item.url)
            if (existing != null) {
                updatedCount.incrementAndGet()
                HistoryProcessorResult.Update(
                    existingId = existing.id!!,
                    visitCount = item.visitCount,
                    lastVisitDate = item.lastVisitDate,
                    frecency = item.frecency
                )
            } else {
                val (domain, scheme) = extractDomainAndScheme(item.url)
                val dto = BrowserBookmarkDTO().apply {
                    this.uri = "firefox:history:${item.placeId}"
                    this.url = item.url
                    this.title = item.title
                    this.domain = domain
                    this.scheme = scheme
                    this.sourceType = BrowserSourceType.HISTORY
                    this.firefoxPlaceId = item.placeId
                    this.firefoxBookmarkId = null
                    this.visitCount = item.visitCount
                    this.lastVisitDate = item.lastVisitDate
                    this.frecency = item.frecency
                    this.browserProfileId = this@HistoryImportProcessor.browserProfileId
                    this.status = Status.NEW
                    // History entries are URL-only signals; no body to extract yet.
                    this.analysisStatus = AnalysisStatus.LOCATE
                    this.label = item.title ?: extractTitleFromUrl(item.url)
                    this.version = 0L
                }
                createdCount.incrementAndGet()
                HistoryProcessorResult.Create(dto)
            }
        } catch (e: Exception) {
            errorCount.incrementAndGet()
            log.warn("Error processing history entry {}: {}", item.url, e.message)
            null
        }
    }

    private fun extractDomainAndScheme(url: String): Pair<String?, String?> {
        return try {
            val uri = URI(url)
            val domain = uri.host?.lowercase()?.removePrefix("www.")
            val scheme = uri.scheme?.lowercase()
            domain to scheme
        } catch (e: Exception) {
            log.trace("Could not parse URL: {}", url)
            null to null
        }
    }

    private fun extractTitleFromUrl(url: String): String {
        return try {
            val uri = URI(url)
            uri.host ?: url.take(50)
        } catch (e: Exception) {
            url.take(50)
        }
    }

    fun getStats(): Stats = Stats(
        processed = processedCount.get(),
        created = createdCount.get(),
        updated = updatedCount.get(),
        errors = errorCount.get(),
        maxVisitPrTime = maxVisitPrTime.get().takeIf { it > 0L }
    )

    data class Stats(
        val processed: Int,
        val created: Int,
        val updated: Int,
        val errors: Int,
        val maxVisitPrTime: Long?
    )

}


/**
 * Output of [HistoryImportProcessor]: either a brand-new DTO to persist
 * or an in-place visit-count refresh for an existing row.
 */
sealed class HistoryProcessorResult {
    data class Create(val dto: BrowserBookmarkDTO) : HistoryProcessorResult()
    data class Update(
        val existingId: Long,
        val visitCount: Int,
        val lastVisitDate: java.time.OffsetDateTime?,
        val frecency: Int
    ) : HistoryProcessorResult()
}
