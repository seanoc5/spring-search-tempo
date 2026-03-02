package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.oconeco.spring_search_tempo.base.EmailMessageService
import com.oconeco.spring_search_tempo.base.model.EmailMessageDTO
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemReader
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort

/**
 * ItemReader for email categorization step.
 *
 * Reads email messages that have completed body enrichment (fetchStatus = COMPLETE)
 * but haven't been categorized yet (categorizedAt IS NULL).
 */
class EmailCategorizationReader(
    private val emailMessageService: EmailMessageService,
    private val accountId: Long,
    private val pageSize: Int = 100
) : ItemReader<EmailMessageDTO> {

    companion object {
        private val log = LoggerFactory.getLogger(EmailCategorizationReader::class.java)
    }

    private var messages: MutableList<EmailMessageDTO> = mutableListOf()
    private var pageNumber = 0
    private var exhausted = false
    private var totalRead = 0

    @Synchronized
    override fun read(): EmailMessageDTO? {
        // If we have buffered messages, return the next one
        if (messages.isNotEmpty()) {
            totalRead++
            return messages.removeAt(0)
        }

        // If exhausted, we're done
        if (exhausted) {
            log.debug("EmailCategorizationReader exhausted. Total read: {}", totalRead)
            return null
        }

        // Fetch next page
        val pageable = PageRequest.of(pageNumber, pageSize, Sort.by("receivedDate").descending())
        val page = emailMessageService.findUncategorizedByAccount(accountId, pageable)

        if (page.isEmpty) {
            exhausted = true
            log.debug("No more uncategorized messages. Total read: {}", totalRead)
            return null
        }

        messages.addAll(page.content)
        pageNumber++

        log.debug("Fetched page {} with {} uncategorized messages", pageNumber - 1, page.content.size)

        return if (messages.isNotEmpty()) {
            totalRead++
            messages.removeAt(0)
        } else {
            null
        }
    }
}
