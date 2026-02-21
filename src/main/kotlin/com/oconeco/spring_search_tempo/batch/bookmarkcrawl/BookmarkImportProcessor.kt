package com.oconeco.spring_search_tempo.batch.bookmarkcrawl

import com.oconeco.spring_search_tempo.base.BookmarkTagService
import com.oconeco.spring_search_tempo.base.BrowserBookmarkService
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.BookmarkTag
import com.oconeco.spring_search_tempo.base.domain.Status
import com.oconeco.spring_search_tempo.base.model.BrowserBookmarkDTO
import com.oconeco.spring_search_tempo.base.service.FirefoxPlacesService.FirefoxBookmarkData
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemProcessor
import java.net.URI


/**
 * ItemProcessor that converts Firefox bookmark data to BrowserBookmarkDTO.
 *
 * - Skips existing bookmarks (by URL)
 * - Extracts domain and scheme from URL
 * - Creates/finds tags via BookmarkTagService
 * - Sets analysisStatus = SEMANTIC for all bookmarks (valuable by definition)
 */
class BookmarkImportProcessor(
    private val browserBookmarkService: BrowserBookmarkService,
    private val bookmarkTagService: BookmarkTagService,
    private val browserProfileId: Long
) : ItemProcessor<FirefoxBookmarkData, BookmarkProcessorResult> {

    companion object {
        private val log = LoggerFactory.getLogger(BookmarkImportProcessor::class.java)
    }

    private var processedCount = 0
    private var skippedCount = 0
    private var errorCount = 0

    override fun process(item: FirefoxBookmarkData): BookmarkProcessorResult? {
        processedCount++

        // Skip if bookmark already exists
        if (browserBookmarkService.urlExists(item.url)) {
            skippedCount++
            log.trace("Skipping existing bookmark: {}", item.url)
            return null
        }

        try {
            // Extract domain and scheme
            val (domain, scheme) = extractDomainAndScheme(item.url)

            // Create/find tags
            val tags = if (item.tags.isNotEmpty()) {
                val tagPairs = item.tags.map { it.name to it.displayName }
                bookmarkTagService.findOrCreateAll(tagPairs, "FIREFOX")
            } else {
                emptySet()
            }

            // Create DTO
            val dto = BrowserBookmarkDTO().apply {
                this.uri = "firefox:bookmark:${item.bookmarkId}"
                this.url = item.url
                this.title = item.title
                this.domain = domain
                this.scheme = scheme
                this.firefoxPlaceId = item.placeId
                this.firefoxBookmarkId = item.bookmarkId
                this.visitCount = item.visitCount
                this.lastVisitDate = item.lastVisitDate
                this.frecency = item.frecency
                this.dateAdded = item.dateAdded
                this.folderPath = item.folderPath
                this.browserProfileId = this@BookmarkImportProcessor.browserProfileId
                this.status = Status.NEW
                this.analysisStatus = AnalysisStatus.SEMANTIC
                this.label = item.title ?: extractTitleFromUrl(item.url)
                this.version = 0L
            }

            return BookmarkProcessorResult(dto, tags)

        } catch (e: Exception) {
            errorCount++
            log.warn("Error processing bookmark {}: {}", item.url, e.message)
            return null
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

    fun getStats(): Triple<Int, Int, Int> = Triple(processedCount, skippedCount, errorCount)

}


/**
 * Result from processor containing the DTO and associated tags.
 */
data class BookmarkProcessorResult(
    val dto: BrowserBookmarkDTO,
    val tags: Set<BookmarkTag>
)
