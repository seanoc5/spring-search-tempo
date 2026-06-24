package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.EmailMessageService
import com.oconeco.spring_search_tempo.base.domain.EmailCategory
import com.oconeco.spring_search_tempo.base.domain.EmailMessage
import com.oconeco.spring_search_tempo.base.domain.FetchStatus
import java.time.OffsetDateTime
import com.oconeco.spring_search_tempo.base.model.EmailMessageDTO
import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
import com.oconeco.spring_search_tempo.base.repos.EmailFolderRepository
import com.oconeco.spring_search_tempo.base.repos.EmailMessageRepository
import com.oconeco.spring_search_tempo.base.config.CrawlConfiguration
import com.oconeco.spring_search_tempo.base.util.CustomCollectors
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional


@Service
class EmailMessageServiceImpl(
    private val emailMessageRepository: EmailMessageRepository,
    private val emailAccountRepository: EmailAccountRepository,
    private val emailFolderRepository: EmailFolderRepository,
    private val smartDeleteService: SmartDeleteService,
    private val emailMessageMapper: EmailMessageMapper,
    private val crawlConfiguration: CrawlConfiguration
) : EmailMessageService {

    companion object {
        private val log = LoggerFactory.getLogger(EmailMessageServiceImpl::class.java)

        /**
         * Multiplier over `app.crawl.large-body-threshold-chars` above
         * which the Pass-2 write-time guard kicks in (issue #161 / item f).
         *
         * Normal flow: Pass 2 writes body_text intact; Pass 3 chunking
         * truncates after consuming the full text. The guard only fires
         * when something is unambiguously pathological — anything larger
         * than `threshold * SAFETY_MULTIPLIER` is past every reasonable
         * ceiling (1 MB threshold → 5 MB guard, which is well below the
         * 10 MB Tika extraction cap and well above any legitimate
         * plaintext email). The guard preserves enough text to keep
         * chunking meaningful while heading off `tsvector` failures
         * (defense-in-depth on top of the `substring(body_text, 1, 250000)`
         * cap baked into `email_message.fts_vector`).
         */
        private const val WRITE_GUARD_SAFETY_MULTIPLIER = 5L

        private const val TRUNCATION_MARKER_FMT =
            "\n…[truncated: %d chars; full content in chunks]"
    }

    override fun count(): Long = emailMessageRepository.count()

    override fun findAll(pageable: Pageable): Page<EmailMessageDTO> {
        val page = emailMessageRepository.findAll(pageable)
        return PageImpl(
            page.content.map { message ->
                emailMessageMapper.updateEmailMessageDTO(message, EmailMessageDTO())
            },
            pageable,
            page.totalElements
        )
    }

    override fun get(id: Long): EmailMessageDTO = emailMessageRepository.findById(id)
        .map { message -> emailMessageMapper.updateEmailMessageDTO(message, EmailMessageDTO()) }
        .orElseThrow { NotFoundException() }

    override fun create(emailMessageDTO: EmailMessageDTO): Long {
        val emailMessage = EmailMessage()
        emailMessageMapper.updateEmailMessage(
            emailMessageDTO,
            emailMessage,
            emailAccountRepository,
            emailFolderRepository
        )
        return emailMessageRepository.save(emailMessage).id!!
    }

    override fun createBulk(dtos: List<EmailMessageDTO>): List<Long> {
        if (dtos.isEmpty()) return emptyList()

        val entities = dtos.map { dto ->
            val entity = EmailMessage()
            emailMessageMapper.updateEmailMessage(
                dto,
                entity,
                emailAccountRepository,
                emailFolderRepository
            )
            entity
        }
        return emailMessageRepository.saveAll(entities).mapNotNull { it.id }
    }

    override fun update(id: Long, emailMessageDTO: EmailMessageDTO) {
        val emailMessage = emailMessageRepository.findById(id)
            .orElseThrow { NotFoundException() }
        emailMessageMapper.updateEmailMessage(
            emailMessageDTO,
            emailMessage,
            emailAccountRepository,
            emailFolderRepository
        )
        emailMessageRepository.save(emailMessage)
    }

    override fun delete(id: Long) {
        smartDeleteService.deleteEmailMessage(id)
    }

    override fun existsByMessageId(messageId: String): Boolean =
        emailMessageRepository.existsByMessageId(messageId)

    override fun existsByUri(uri: String): Boolean =
        emailMessageRepository.findByUri(uri) != null

    override fun findExistingMessageIds(messageIds: Collection<String>): Set<String> =
        if (messageIds.isEmpty()) emptySet()
        else emailMessageRepository.findExistingMessageIds(messageIds).toSet()

    override fun getEmailMessageValues(): Map<Long, Long> =
        emailMessageRepository.findAll(Sort.by("id"))
            .stream()
            .collect(CustomCollectors.toSortedMap(EmailMessage::id, EmailMessage::id))

    override fun findMessagesWithBodyText(pageable: Pageable): Page<EmailMessageDTO> {
        val page = emailMessageRepository.findByBodyTextIsNotNull(pageable)
        return PageImpl(
            page.content.map { message ->
                emailMessageMapper.updateEmailMessageDTO(message, EmailMessageDTO())
            },
            pageable,
            page.totalElements
        )
    }

    override fun findMessagesWithBodyTextByAccount(accountId: Long, pageable: Pageable): Page<EmailMessageDTO> {
        val page = emailMessageRepository.findByBodyTextIsNotNullAndEmailAccountId(accountId, pageable)
        return PageImpl(
            page.content.map { message ->
                emailMessageMapper.updateEmailMessageDTO(message, EmailMessageDTO())
            },
            pageable,
            page.totalElements
        )
    }

    override fun countByAccount(accountId: Long): Long =
        emailMessageRepository.countByEmailAccountId(accountId)

    override fun findHeadersOnlyByAccount(accountId: Long, pageable: Pageable): Page<EmailMessageDTO> {
        val page = emailMessageRepository.findByEmailAccountIdAndFetchStatus(
            accountId,
            FetchStatus.HEADERS_ONLY,
            pageable
        )
        return PageImpl(
            page.content.map { message ->
                emailMessageMapper.updateEmailMessageDTO(message, EmailMessageDTO())
            },
            pageable,
            page.totalElements
        )
    }

    override fun countHeadersOnlyByAccount(accountId: Long): Long =
        emailMessageRepository.countByEmailAccountIdAndFetchStatus(accountId, FetchStatus.HEADERS_ONLY)

    override fun search(filter: String, pageable: Pageable): Page<EmailMessageDTO> {
        val page = emailMessageRepository.search(filter, pageable)
        return PageImpl(
            page.content.map { message ->
                emailMessageMapper.updateEmailMessageDTO(message, EmailMessageDTO())
            },
            pageable,
            page.totalElements
        )
    }

    @Transactional
    override fun updateBodyAndComplete(
        id: Long,
        bodyText: String?,
        bodySize: Long?,
        hasAttachments: Boolean,
        attachmentCount: Int,
        attachmentNames: String?
    ) {
        // Issue #161 (f): defensive write-time guard. If body_text dwarfs
        // the configured threshold, cap it here so a single rogue message
        // can't sink the whole `emailBodyEnrich` chunk with a tsvector
        // failure. Logged at WARN so a regression surfaces in dashboards
        // rather than silently dropping data.
        val safeBody = guardLargeBody(id, bodyText)

        // PERFORMANCE: Direct UPDATE query avoids SELECT+UPDATE pattern
        emailMessageRepository.updateBodyDirect(
            id = id,
            bodyText = safeBody,
            bodySize = bodySize,
            hasAttachments = hasAttachments,
            attachmentCount = attachmentCount,
            attachmentNames = attachmentNames
        )
    }

    private fun guardLargeBody(id: Long, bodyText: String?): String? {
        if (bodyText == null) return null
        val threshold = crawlConfiguration.largeBodyThresholdChars
        if (threshold <= 0) return bodyText
        val safetyLimit = threshold * WRITE_GUARD_SAFETY_MULTIPLIER
        if (bodyText.length.toLong() <= safetyLimit) return bodyText

        val keep = safetyLimit.toInt()
        val dropped = bodyText.length - keep
        log.warn(
            "Email {} body_text length {} chars exceeds safety limit {} chars " +
                "(threshold={} x {}); truncating at write time (issue #161 guard).",
            id, bodyText.length, safetyLimit, threshold, WRITE_GUARD_SAFETY_MULTIPLIER
        )
        return bodyText.substring(0, keep) + TRUNCATION_MARKER_FMT.format(dropped)
    }

    @Transactional
    override fun truncateBodyTextToThreshold(emailId: Long, thresholdChars: Long): Int {
        if (thresholdChars <= 0) return 0
        val message = emailMessageRepository.findById(emailId).orElse(null) ?: return 0
        val body = message.bodyText ?: return 0
        if (body.length.toLong() <= thresholdChars) return 0
        val keep = thresholdChars.toInt()
        val dropped = body.length - keep
        message.bodyText = body.substring(0, keep) + TRUNCATION_MARKER_FMT.format(dropped)
        emailMessageRepository.save(message)
        return dropped
    }

    @Transactional
    override fun truncateLargeBodyTextBackfill(thresholdChars: Long, batchSize: Int): Int {
        if (thresholdChars <= 0) return 0
        var touched = 0
        while (true) {
            // Re-querying page 0 each round is intentional: rows we
            // truncate drop out of the filter so the next query surfaces
            // the next batch of un-truncated rows. Mirrors the FSFile
            // backfill (issue #147).
            val pageable = PageRequest.of(0, batchSize, Sort.by("id"))
            val page = emailMessageRepository.findChunkedEmailsWithLargeBodyText(thresholdChars, pageable)
            if (page.isEmpty) break
            page.content.forEach { message ->
                val body = message.bodyText ?: return@forEach
                if (body.length.toLong() <= thresholdChars) return@forEach
                val keep = thresholdChars.toInt()
                val dropped = body.length - keep
                message.bodyText = body.substring(0, keep) + TRUNCATION_MARKER_FMT.format(dropped)
                touched++
            }
            emailMessageRepository.saveAll(page.content)
            if (!page.hasNext()) break
        }
        return touched
    }

    override fun findUncategorizedByAccount(accountId: Long, pageable: Pageable): Page<EmailMessageDTO> {
        val page = emailMessageRepository.findByEmailAccountIdAndFetchStatusAndCategorizedAtIsNull(
            accountId,
            FetchStatus.COMPLETE,
            pageable
        )
        return PageImpl(
            page.content.map { message ->
                emailMessageMapper.updateEmailMessageDTO(message, EmailMessageDTO())
            },
            pageable,
            page.totalElements
        )
    }

    override fun countUncategorizedByAccount(accountId: Long): Long =
        emailMessageRepository.countByEmailAccountIdAndFetchStatusAndCategorizedAtIsNull(
            accountId,
            FetchStatus.COMPLETE
        )

    @Transactional
    override fun updateCategorization(
        id: Long,
        category: EmailCategory,
        confidence: Double?,
        categorizedAt: OffsetDateTime?
    ) {
        emailMessageRepository.updateCategorization(id, category, confidence, categorizedAt)
    }

    @Transactional
    override fun markAsRead(id: Long) {
        emailMessageRepository.updateReadStatus(id, true)
    }

    @Transactional
    override fun markAsUnread(id: Long) {
        emailMessageRepository.updateReadStatus(id, false)
    }

    @Transactional
    override fun toggleReadStatus(id: Long): Boolean {
        val message = emailMessageRepository.findById(id)
            .orElseThrow { NotFoundException() }
        val newStatus = !message.isRead
        emailMessageRepository.updateReadStatus(id, newStatus)
        return newStatus
    }

    override fun countUnreadByAccount(accountId: Long): Long =
        emailMessageRepository.countByEmailAccountIdAndIsRead(accountId, false)

    override fun findInterestingForChunking(
        accountId: Long,
        cutoffDate: OffsetDateTime,
        forceRefresh: Boolean,
        pageable: Pageable
    ): Page<EmailMessageDTO> {
        val page = emailMessageRepository.findInterestingForChunking(
            accountId, cutoffDate, forceRefresh, pageable
        )
        return PageImpl(
            page.content.map { message ->
                emailMessageMapper.updateEmailMessageDTO(message, EmailMessageDTO())
            },
            pageable,
            page.totalElements
        )
    }

    override fun getWithTags(id: Long): EmailMessageDTO {
        val message = emailMessageRepository.findByIdWithTags(id)
            ?: throw NotFoundException()
        val dto = emailMessageMapper.updateEmailMessageDTO(message, EmailMessageDTO())
        dto.tagIds = message.tags.mapNotNull { it.id }.toMutableList()
        return dto
    }

}
