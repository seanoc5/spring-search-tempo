package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.BookmarkTagService
import com.oconeco.spring_search_tempo.base.domain.BookmarkTag
import com.oconeco.spring_search_tempo.base.model.BookmarkTagDTO
import com.oconeco.spring_search_tempo.base.repos.BookmarkTagRepository
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional


@Service
class BookmarkTagServiceImpl(
    private val bookmarkTagRepository: BookmarkTagRepository,
    private val bookmarkTagMapper: BookmarkTagMapper
) : BookmarkTagService {

    companion object {
        private val log = LoggerFactory.getLogger(BookmarkTagServiceImpl::class.java)
    }

    override fun count(): Long = bookmarkTagRepository.count()

    override fun findAll(): List<BookmarkTagDTO> {
        val tags = bookmarkTagRepository.findAll(Sort.by("name"))
        return tags.map { tag ->
            bookmarkTagMapper.updateBookmarkTagDTO(tag, BookmarkTagDTO())
        }
    }

    override fun get(id: Long): BookmarkTagDTO = bookmarkTagRepository.findById(id)
        .map { tag -> bookmarkTagMapper.updateBookmarkTagDTO(tag, BookmarkTagDTO()) }
        .orElseThrow { NotFoundException() }

    override fun create(bookmarkTagDTO: BookmarkTagDTO): Long {
        val bookmarkTag = BookmarkTag()
        bookmarkTagMapper.updateBookmarkTag(bookmarkTagDTO, bookmarkTag)
        return bookmarkTagRepository.save(bookmarkTag).id!!
    }

    override fun update(id: Long, bookmarkTagDTO: BookmarkTagDTO) {
        val bookmarkTag = bookmarkTagRepository.findById(id)
            .orElseThrow { NotFoundException() }
        bookmarkTagMapper.updateBookmarkTag(bookmarkTagDTO, bookmarkTag)
        bookmarkTagRepository.save(bookmarkTag)
    }

    override fun delete(id: Long) {
        val bookmarkTag = bookmarkTagRepository.findById(id)
            .orElseThrow { NotFoundException() }
        bookmarkTagRepository.delete(bookmarkTag)
    }

    override fun tagNameExists(name: String): Boolean =
        bookmarkTagRepository.existsByName(name.lowercase())

    @Transactional
    override fun findOrCreate(name: String, displayName: String, source: String): BookmarkTag {
        val normalizedName = name.lowercase()

        return bookmarkTagRepository.findByName(normalizedName) ?: run {
            log.debug("Creating new tag: {} ({})", normalizedName, displayName)
            val newTag = BookmarkTag().apply {
                this.name = normalizedName
                this.displayName = displayName
                this.source = source
                this.usageCount = 0
            }
            bookmarkTagRepository.save(newTag)
        }
    }

    @Transactional
    override fun findOrCreateAll(tagPairs: List<Pair<String, String>>, source: String): Set<BookmarkTag> {
        if (tagPairs.isEmpty()) return emptySet()

        val normalizedNames = tagPairs.map { it.first.lowercase() }

        // Find existing tags
        val existingTags = bookmarkTagRepository.findByNameIn(normalizedNames)
        val existingNameMap = existingTags.associateBy { it.name }

        // Create missing tags
        val result = mutableSetOf<BookmarkTag>()
        result.addAll(existingTags)

        val tagsToCreate = tagPairs
            .filter { existingNameMap[it.first.lowercase()] == null }
            .map { (name, displayName) ->
                BookmarkTag().apply {
                    this.name = name.lowercase()
                    this.displayName = displayName
                    this.source = source
                    this.usageCount = 0
                }
            }

        if (tagsToCreate.isNotEmpty()) {
            log.debug("Creating {} new tags", tagsToCreate.size)
            result.addAll(bookmarkTagRepository.saveAll(tagsToCreate))
        }

        return result
    }

    override fun findPopular(limit: Int): List<BookmarkTagDTO> {
        val tags = bookmarkTagRepository.findPopular(PageRequest.of(0, limit))
        return tags.map { tag ->
            bookmarkTagMapper.updateBookmarkTagDTO(tag, BookmarkTagDTO())
        }
    }

    @Transactional
    override fun updateUsageCounts() {
        log.info("Updating usage counts for all tags")
        val tags = bookmarkTagRepository.findAll()
        for (tag in tags) {
            tag.usageCount = tag.bookmarks.size
        }
        bookmarkTagRepository.saveAll(tags)
        log.info("Updated usage counts for {} tags", tags.size)
    }

    @Transactional
    override fun incrementUsageCounts(tagIds: Collection<Long>) {
        if (tagIds.isEmpty()) return

        val tags = bookmarkTagRepository.findAllById(tagIds)
        for (tag in tags) {
            tag.usageCount = tag.usageCount + 1
        }
        bookmarkTagRepository.saveAll(tags)
    }

}
