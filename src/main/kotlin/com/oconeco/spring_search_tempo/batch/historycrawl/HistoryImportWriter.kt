package com.oconeco.spring_search_tempo.batch.historycrawl

import com.oconeco.spring_search_tempo.base.domain.BrowserBookmark
import com.oconeco.spring_search_tempo.base.repos.BrowserBookmarkRepository
import com.oconeco.spring_search_tempo.base.repos.BrowserProfileRepository
import com.oconeco.spring_search_tempo.base.service.BrowserBookmarkMapper
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter
import org.springframework.transaction.annotation.Transactional


/**
 * Persists history rows produced by [HistoryImportProcessor].
 *
 * Creates new BrowserBookmark rows with `sourceType=HISTORY`; for URLs
 * that already exist (bookmarks or older history rows) only the
 * visit-count, last-visit-date and frecency fields are refreshed — the
 * row's source type and tag membership are preserved.
 */
class HistoryImportWriter(
    private val browserBookmarkRepository: BrowserBookmarkRepository,
    private val browserProfileRepository: BrowserProfileRepository,
    private val browserBookmarkMapper: BrowserBookmarkMapper,
    private val browserProfileId: Long
) : ItemWriter<HistoryProcessorResult> {

    companion object {
        private val log = LoggerFactory.getLogger(HistoryImportWriter::class.java)
    }

    private var createdCount = 0
    private var updatedCount = 0

    @Transactional
    override fun write(chunk: Chunk<out HistoryProcessorResult>) {
        val results = chunk.items
        if (results.isEmpty()) return

        val profile = browserProfileRepository.findById(browserProfileId)
            .orElseThrow { NotFoundException("BrowserProfile not found: $browserProfileId") }

        for (result in results) {
            when (result) {
                is HistoryProcessorResult.Create -> {
                    val bookmark = BrowserBookmark()
                    browserBookmarkMapper.updateBrowserBookmark(result.dto, bookmark)
                    bookmark.browserProfile = profile
                    browserBookmarkRepository.save(bookmark)
                    createdCount++
                }
                is HistoryProcessorResult.Update -> {
                    val existing = browserBookmarkRepository.findById(result.existingId).orElse(null)
                    if (existing != null) {
                        existing.visitCount = result.visitCount
                        existing.lastVisitDate = result.lastVisitDate
                        existing.frecency = result.frecency
                        browserBookmarkRepository.save(existing)
                        updatedCount++
                    }
                }
            }
        }

        log.debug("History chunk written: created={}, updated={}", createdCount, updatedCount)
    }

    fun getCreatedCount(): Int = createdCount
    fun getUpdatedCount(): Int = updatedCount

}
