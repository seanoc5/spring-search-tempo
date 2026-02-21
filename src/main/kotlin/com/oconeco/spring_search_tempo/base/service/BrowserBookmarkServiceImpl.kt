package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.BrowserBookmarkService
import com.oconeco.spring_search_tempo.base.domain.BrowserBookmark
import com.oconeco.spring_search_tempo.base.model.BrowserBookmarkDTO
import com.oconeco.spring_search_tempo.base.repos.BrowserBookmarkRepository
import com.oconeco.spring_search_tempo.base.repos.BrowserProfileRepository
import com.oconeco.spring_search_tempo.base.util.CustomCollectors
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime


@Service
class BrowserBookmarkServiceImpl(
    private val browserBookmarkRepository: BrowserBookmarkRepository,
    private val browserProfileRepository: BrowserProfileRepository,
    private val browserBookmarkMapper: BrowserBookmarkMapper
) : BrowserBookmarkService {

    override fun count(): Long = browserBookmarkRepository.count()

    override fun findAll(pageable: Pageable): Page<BrowserBookmarkDTO> {
        return browserBookmarkRepository.findAll(pageable).map { bookmark ->
            mapToDTO(bookmark)
        }
    }

    override fun get(id: Long): BrowserBookmarkDTO = browserBookmarkRepository.findById(id)
        .map { bookmark -> mapToDTO(bookmark) }
        .orElseThrow { NotFoundException() }

    @Transactional
    override fun create(browserBookmarkDTO: BrowserBookmarkDTO): Long {
        val browserBookmark = BrowserBookmark()
        browserBookmarkMapper.updateBrowserBookmark(browserBookmarkDTO, browserBookmark)

        browserBookmarkDTO.browserProfileId?.let { profileId ->
            browserBookmark.browserProfile = browserProfileRepository.findById(profileId)
                .orElseThrow { NotFoundException("BrowserProfile not found: $profileId") }
        }

        return browserBookmarkRepository.save(browserBookmark).id!!
    }

    @Transactional
    override fun update(id: Long, browserBookmarkDTO: BrowserBookmarkDTO) {
        val browserBookmark = browserBookmarkRepository.findById(id)
            .orElseThrow { NotFoundException() }
        browserBookmarkMapper.updateBrowserBookmark(browserBookmarkDTO, browserBookmark)

        browserBookmarkDTO.browserProfileId?.let { profileId ->
            browserBookmark.browserProfile = browserProfileRepository.findById(profileId)
                .orElseThrow { NotFoundException("BrowserProfile not found: $profileId") }
        }

        browserBookmarkRepository.save(browserBookmark)
    }

    override fun delete(id: Long) {
        val browserBookmark = browserBookmarkRepository.findById(id)
            .orElseThrow { NotFoundException() }
        browserBookmarkRepository.delete(browserBookmark)
    }

    override fun urlExists(url: String): Boolean =
        browserBookmarkRepository.existsByUrl(url)

    override fun findByUrl(url: String): BrowserBookmarkDTO? {
        return browserBookmarkRepository.findByUrl(url)?.let { bookmark ->
            mapToDTO(bookmark)
        }
    }

    override fun findByDomain(domain: String, pageable: Pageable): Page<BrowserBookmarkDTO> {
        return browserBookmarkRepository.findByDomain(domain, pageable).map { bookmark ->
            mapToDTO(bookmark)
        }
    }

    override fun findDistinctDomains(): List<String> =
        browserBookmarkRepository.findDistinctDomains()

    override fun findDomainCounts(pageable: Pageable): List<Pair<String, Long>> {
        return browserBookmarkRepository.findDomainCounts(pageable).map { row ->
            (row[0] as String) to (row[1] as Long)
        }
    }

    override fun countByBrowserProfileId(browserProfileId: Long): Long =
        browserBookmarkRepository.countByBrowserProfileId(browserProfileId)

    override fun findBookmarksNeedingFetch(pageable: Pageable): Page<BrowserBookmarkDTO> {
        return browserBookmarkRepository.findBookmarksNeedingFetch(pageable).map { bookmark ->
            mapToDTO(bookmark)
        }
    }

    @Transactional
    override fun markAsFetched(bookmarkId: Long) {
        val bookmark = browserBookmarkRepository.findById(bookmarkId)
            .orElseThrow { NotFoundException() }
        bookmark.fetchedAt = OffsetDateTime.now()
        browserBookmarkRepository.save(bookmark)
    }

    @Transactional
    override fun markAsChunked(bookmarkId: Long) {
        val bookmark = browserBookmarkRepository.findById(bookmarkId)
            .orElseThrow { NotFoundException() }
        bookmark.chunkedAt = OffsetDateTime.now()
        browserBookmarkRepository.save(bookmark)
    }

    override fun searchByText(query: String, pageable: Pageable): Page<BrowserBookmarkDTO> {
        return browserBookmarkRepository.searchByText(query, pageable).map { bookmark ->
            mapToDTO(bookmark)
        }
    }

    override fun getBrowserBookmarkValues(): Map<Long, Long> =
        browserBookmarkRepository.findAll(Sort.by("id"))
            .stream()
            .collect(CustomCollectors.toSortedMap(BrowserBookmark::id, BrowserBookmark::id))

    private fun mapToDTO(bookmark: BrowserBookmark): BrowserBookmarkDTO {
        val dto = browserBookmarkMapper.updateBrowserBookmarkDTO(bookmark, BrowserBookmarkDTO())
        dto.tagNames = bookmark.tags.mapNotNull { it.displayName ?: it.name }
        return dto
    }

}
