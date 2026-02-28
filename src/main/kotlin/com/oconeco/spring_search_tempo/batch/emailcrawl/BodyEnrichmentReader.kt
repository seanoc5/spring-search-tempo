package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.oconeco.spring_search_tempo.base.EmailMessageService
import com.oconeco.spring_search_tempo.base.model.EmailMessageDTO
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemReader
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort


/**
 * Pass 2 Reader: Reads messages needing body fetch from database.
 *
 * Queries for messages with fetchStatus = HEADERS_ONLY and returns them
 * for body enrichment processing.
 */
class BodyEnrichmentReader(
    private val emailMessageService: EmailMessageService,
    private val accountId: Long,
    private val pageSize: Int = 50
) : ItemReader<EmailMessageDTO> {

    companion object {
        private val log = LoggerFactory.getLogger(BodyEnrichmentReader::class.java)
    }

    private var messages: MutableList<EmailMessageDTO>? = null
    private var currentIndex = 0
    private var initialized = false

    override fun read(): EmailMessageDTO? {
        if (!initialized) {
            initialize()
        }

        val msgs = messages ?: return null
        if (currentIndex >= msgs.size) {
            return null
        }

        return msgs[currentIndex++]
    }

    private fun initialize() {
        initialized = true

        val count = emailMessageService.countHeadersOnlyByAccount(accountId)
        log.info("Found {} messages needing body fetch for account {}", count, accountId)

        if (count == 0L) {
            messages = mutableListOf()
            return
        }

        // Load all messages needing body fetch
        // For large mailboxes, we could paginate, but loading all IDs upfront is simpler
        val allMessages = mutableListOf<EmailMessageDTO>()
        var page = 0
        var hasMore = true

        while (hasMore) {
            val pageable = PageRequest.of(page, pageSize, Sort.by("imapUid"))
            val pageResult = emailMessageService.findHeadersOnlyByAccount(accountId, pageable)
            allMessages.addAll(pageResult.content)
            hasMore = pageResult.hasNext()
            page++
        }

        messages = allMessages
        log.info("Loaded {} messages for body enrichment", allMessages.size)
    }
}
