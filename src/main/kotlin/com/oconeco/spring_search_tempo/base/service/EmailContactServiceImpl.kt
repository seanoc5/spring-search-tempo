package com.oconeco.spring_search_tempo.base.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.oconeco.spring_search_tempo.base.EmailContactService
import com.oconeco.spring_search_tempo.base.domain.EmailContact
import com.oconeco.spring_search_tempo.base.domain.EmailMessage
import com.oconeco.spring_search_tempo.base.model.EmailContactDTO
import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
import com.oconeco.spring_search_tempo.base.repos.EmailContactRepository
import com.oconeco.spring_search_tempo.base.repos.EmailMessageRepository
import com.oconeco.spring_search_tempo.base.util.EmailAddressNormalizer
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime


/**
 * Per-account email-contact aggregator (issue #146 Phase 1).
 *
 * Counter semantics, with the account's own address treated as "us":
 *  - **sentToCount** — messages whose `fromAddress` is "us" and the contact
 *    appears in `toAddresses` / `ccAddresses` / `bccAddresses`. Counts each
 *    message once per recipient contact.
 *  - **receivedFromCount** — messages whose `fromAddress` is the contact (and
 *    not "us"). One per message.
 *  - **repliedToCount** — sentTo messages with a non-null `inReplyTo`. The
 *    heuristic: "we sent to them and the message is a reply, therefore we
 *    replied to them." Doesn't try to confirm the parent is actually theirs;
 *    a noisy thread where we reply to ourselves while CC'ing them will count.
 *    Trade-off accepted for Phase 1.
 *  - **repliedFromCount** — receivedFrom messages with a non-null `inReplyTo`.
 *    "They sent us a message that is itself a reply." Same caveat as above.
 *
 * **Timestamps**: `firstSeen` / `lastSeen` use `receivedDate ?: sentDate`,
 * falling back to `dateCreated` so a message with no envelope dates still
 * contributes a marker. `threadsAppearedIn` counts distinct non-null
 * `threadId` values; messages without a thread are excluded from that count
 * but still update everything else.
 *
 * The aggregation runs in-memory per account. For Phase 1 corpora (tens of
 * thousands of messages) the cost is negligible vs the SQL-side alternative;
 * Phase 2+ may push this into a window-function rollup if it becomes a
 * bottleneck.
 */
@Service
class EmailContactServiceImpl(
    private val emailContactRepository: EmailContactRepository,
    private val emailMessageRepository: EmailMessageRepository,
    private val emailAccountRepository: EmailAccountRepository,
    private val emailContactMapper: EmailContactMapper,
    private val objectMapper: ObjectMapper
) : EmailContactService {

    companion object {
        private val log = LoggerFactory.getLogger(EmailContactServiceImpl::class.java)
        private const val PAGE_SIZE = 500
    }

    @Transactional(readOnly = true)
    override fun findContacts(accountId: Long?, pageable: Pageable): Page<EmailContactDTO> {
        val page = if (accountId != null) {
            emailContactRepository.findByEmailAccountId(accountId, pageable)
        } else {
            emailContactRepository.findAll(pageable)
        }
        return page.map { entity ->
            emailContactMapper.updateEmailContactDTO(entity, EmailContactDTO())
        }
    }

    @Transactional
    override fun recomputeForAccount(accountId: Long): Int {
        val account = emailAccountRepository.findById(accountId)
            .orElseThrow { NotFoundException("EmailAccount $accountId not found") }

        val ourAddress = EmailAddressNormalizer.normalize(account.email)
        if (ourAddress == null) {
            log.warn("Account {} has no parseable email address ({}); skipping contact aggregation",
                accountId, account.email)
            return 0
        }

        val accumulators = HashMap<String, ContactAccumulator>()

        var pageNumber = 0
        var totalMessages = 0L
        while (true) {
            val page = emailMessageRepository.findByEmailAccountId(
                accountId,
                PageRequest.of(pageNumber, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "id"))
            )
            for (message in page.content) {
                ingest(message, ourAddress, accumulators)
            }
            totalMessages += page.numberOfElements
            if (!page.hasNext()) break
            pageNumber++
        }

        val now = OffsetDateTime.now()
        var touched = 0
        for ((normalizedAddress, acc) in accumulators) {
            val existing = emailContactRepository
                .findByEmailAccountIdAndNormalizedAddress(accountId, normalizedAddress)

            val entity = existing ?: EmailContact().apply {
                this.normalizedAddress = normalizedAddress
                this.emailAccount = account
                this.uri = "email-contact://${accountId}/${normalizedAddress}"
                this.version = 0L
            }

            entity.displayNameLatest = acc.latestDisplayName ?: entity.displayNameLatest
            entity.sentToCount = acc.sentTo
            entity.receivedFromCount = acc.receivedFrom
            entity.repliedToCount = acc.repliedTo
            entity.repliedFromCount = acc.repliedFrom
            entity.firstSeen = acc.firstSeen
            entity.lastSeen = acc.lastSeen
            entity.threadsAppearedIn = acc.threadIds.size.toLong()
            entity.lastRecomputedAt = now
            if (entity.label.isNullOrBlank()) {
                entity.label = entity.displayNameLatest ?: normalizedAddress
            }

            emailContactRepository.save(entity)
            touched++
        }

        log.info(
            "EmailContact aggregation for account {} ({}): scanned {} messages, upserted {} contact rows",
            accountId, account.email, totalMessages, touched
        )
        return touched
    }

    private fun ingest(
        message: EmailMessage,
        ourAddress: String,
        accumulators: MutableMap<String, ContactAccumulator>
    ) {
        val fromParsed = EmailAddressNormalizer.parse(message.fromAddress)
        val fromIsUs = fromParsed?.normalizedAddress == ourAddress
        val timestamp = message.receivedDate ?: message.sentDate ?: message.dateCreated
        val isReply = !message.inReplyTo.isNullOrBlank()

        if (fromIsUs) {
            // Outbound — sent to every distinct recipient (excluding ourselves).
            val recipients = collectRecipients(message)
            for (recipient in recipients) {
                if (recipient.normalizedAddress == ourAddress) continue
                val acc = accumulators.getOrPut(recipient.normalizedAddress) { ContactAccumulator() }
                acc.sentTo += 1
                if (isReply) acc.repliedTo += 1
                acc.touch(recipient.displayName, timestamp, message.threadId)
            }
        } else if (fromParsed != null) {
            // Inbound — count once for the sender.
            val acc = accumulators.getOrPut(fromParsed.normalizedAddress) { ContactAccumulator() }
            acc.receivedFrom += 1
            if (isReply) acc.repliedFrom += 1
            acc.touch(fromParsed.displayName, timestamp, message.threadId)
        }
    }

    private fun collectRecipients(message: EmailMessage): List<EmailAddressNormalizer.ParsedAddress> {
        val raw = mutableListOf<String>()
        raw += parseJsonArray(message.toAddresses)
        raw += parseJsonArray(message.ccAddresses)
        raw += parseJsonArray(message.bccAddresses)
        if (raw.isEmpty()) return emptyList()

        val seen = HashSet<String>()
        val out = ArrayList<EmailAddressNormalizer.ParsedAddress>()
        for (entry in raw) {
            val parsed = EmailAddressNormalizer.parse(entry) ?: continue
            if (seen.add(parsed.normalizedAddress)) {
                out += parsed
            }
        }
        return out
    }

    private fun parseJsonArray(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            objectMapper.readValue(json, Array<String>::class.java).toList()
        } catch (e: Exception) {
            log.debug("Could not parse address JSON array '{}': {}", json, e.message)
            emptyList()
        }
    }

    private class ContactAccumulator {
        var sentTo: Long = 0
        var receivedFrom: Long = 0
        var repliedTo: Long = 0
        var repliedFrom: Long = 0
        var firstSeen: OffsetDateTime? = null
        var lastSeen: OffsetDateTime? = null
        var latestDisplayName: String? = null
        var latestDisplayNameAt: OffsetDateTime? = null
        val threadIds: MutableSet<String> = HashSet()

        fun touch(displayName: String?, at: OffsetDateTime?, threadId: String?) {
            if (at != null) {
                if (firstSeen == null || at.isBefore(firstSeen)) firstSeen = at
                if (lastSeen == null || at.isAfter(lastSeen)) lastSeen = at
                if (!displayName.isNullOrBlank() &&
                    (latestDisplayNameAt == null || at.isAfter(latestDisplayNameAt))) {
                    latestDisplayName = displayName
                    latestDisplayNameAt = at
                }
            } else if (latestDisplayName == null && !displayName.isNullOrBlank()) {
                latestDisplayName = displayName
            }
            if (!threadId.isNullOrBlank()) threadIds += threadId
        }
    }
}
