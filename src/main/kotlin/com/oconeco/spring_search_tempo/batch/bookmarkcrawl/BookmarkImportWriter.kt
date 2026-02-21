package com.oconeco.spring_search_tempo.batch.bookmarkcrawl

import com.oconeco.spring_search_tempo.base.BookmarkTagService
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
 * ItemWriter that saves browser bookmarks with their tag relationships.
 *
 * Uses repository directly (instead of service) to set up the many-to-many
 * tag relationship after creating the entity.
 */
class BookmarkImportWriter(
    private val browserBookmarkRepository: BrowserBookmarkRepository,
    private val browserProfileRepository: BrowserProfileRepository,
    private val browserBookmarkMapper: BrowserBookmarkMapper,
    private val bookmarkTagService: BookmarkTagService,
    private val browserProfileId: Long
) : ItemWriter<BookmarkProcessorResult> {

    companion object {
        private val log = LoggerFactory.getLogger(BookmarkImportWriter::class.java)
    }

    private var writtenCount = 0

    @Transactional
    override fun write(chunk: Chunk<out BookmarkProcessorResult>) {
        val results = chunk.items
        if (results.isEmpty()) return

        val profile = browserProfileRepository.findById(browserProfileId)
            .orElseThrow { NotFoundException("BrowserProfile not found: $browserProfileId") }

        val tagIdsToIncrement = mutableSetOf<Long>()

        for (result in results) {
            val bookmark = BrowserBookmark()
            browserBookmarkMapper.updateBrowserBookmark(result.dto, bookmark)
            bookmark.browserProfile = profile

            // Save to get ID
            val saved = browserBookmarkRepository.save(bookmark)

            // Add tags
            if (result.tags.isNotEmpty()) {
                saved.tags.addAll(result.tags)
                browserBookmarkRepository.save(saved)
                tagIdsToIncrement.addAll(result.tags.mapNotNull { it.id })
            }

            writtenCount++
        }

        // Increment tag usage counts
        if (tagIdsToIncrement.isNotEmpty()) {
            bookmarkTagService.incrementUsageCounts(tagIdsToIncrement)
        }

        log.debug("Wrote {} bookmarks (total: {})", results.size, writtenCount)
    }

    fun getWrittenCount(): Int = writtenCount

}
