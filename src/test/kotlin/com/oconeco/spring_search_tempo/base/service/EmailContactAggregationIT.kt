package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.EmailContactService
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.EmailAccount
import com.oconeco.spring_search_tempo.base.domain.EmailMessage
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import com.oconeco.spring_search_tempo.base.domain.FetchStatus
import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
import com.oconeco.spring_search_tempo.base.repos.EmailContactRepository
import com.oconeco.spring_search_tempo.base.repos.EmailMessageRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import java.time.OffsetDateTime
import java.time.ZoneOffset


/**
 * Issue #146 Phase 1 — aggregation correctness.
 *
 * Seeds ~50 messages across 10 contacts on a single account and asserts that
 * recompute produces the expected per-contact counters, including:
 *  - Plus-suffix collapsing (`recipient3+sale@example.com` ≡ `recipient3@example.com`).
 *  - Reply detection via `inReplyTo`.
 *  - Thread-distinct counting via `threadId`.
 *  - Idempotency under repeated invocation.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@DisplayName("EmailContact aggregation — Phase 1 counter correctness (issue #146)")
class EmailContactAggregationIT : BaseIT() {

    @Autowired
    lateinit var emailAccountRepository: EmailAccountRepository

    @Autowired
    lateinit var emailMessageRepository: EmailMessageRepository

    @Autowired
    lateinit var emailContactRepository: EmailContactRepository

    @Autowired
    lateinit var emailContactService: EmailContactService

    @Test
    @DisplayName("recompute populates counters across 10 contacts and 50 messages")
    fun recomputesCountersAndIsIdempotent() {
        val account = saveAccount("me@oconeco.com")
        val baseTime = OffsetDateTime.of(2026, 1, 1, 9, 0, 0, 0, ZoneOffset.UTC)

        // -- Build a deterministic fixture: 50 messages, 10 distinct contacts. --
        //
        // recipient1..recipient5 — outbound (we sent to them).
        // recipient6..recipient10 — inbound (they sent to us).
        // recipient3 also uses a plus-suffix variant in some messages.
        // recipient1 has 2 reply-to messages, recipient6 has 1 reply-from.

        // 5 outbound messages to recipient1 (2 are replies, 2 share a thread).
        var uid = 100L
        var msgSeq = 1
        repeat(5) { i ->
            val isReply = i < 2
            saveMessage(
                account,
                uid = uid++,
                msgSeq = msgSeq++,
                from = "me@oconeco.com",
                to = listOf("recipient1@example.com"),
                receivedAt = baseTime.plusHours((msgSeq - 1).toLong()),
                inReplyTo = if (isReply) "<parent-$i@example.com>" else null,
                threadId = if (i < 3) "thread-r1" else "thread-r1-extra-$i"
            )
        }

        // 4 outbound to recipient2 (none replies).
        repeat(4) {
            saveMessage(
                account, uid = uid++, msgSeq = msgSeq++,
                from = "me@oconeco.com",
                to = listOf("recipient2@example.com"),
                receivedAt = baseTime.plusHours((msgSeq - 1).toLong()),
                inReplyTo = null,
                threadId = "thread-r2"
            )
        }

        // 6 outbound to recipient3 — half use the plus-suffix variant.
        repeat(6) { i ->
            val toAddr = if (i % 2 == 0) "recipient3@example.com" else "RECIPIENT3+sale@example.com"
            saveMessage(
                account, uid = uid++, msgSeq = msgSeq++,
                from = "me@oconeco.com",
                to = listOf(toAddr),
                receivedAt = baseTime.plusHours((msgSeq - 1).toLong()),
                inReplyTo = null,
                threadId = "thread-r3-$i"
            )
        }

        // 3 outbound where recipient4 + recipient5 are co-recipients on a single message
        // — counted once per contact per message (5 + 5 + 5 wouldn't be right).
        repeat(5) {
            saveMessage(
                account, uid = uid++, msgSeq = msgSeq++,
                from = "me@oconeco.com",
                to = listOf("Recipient4 <recipient4@example.com>"),
                cc = listOf("recipient5@example.com"),
                receivedAt = baseTime.plusHours((msgSeq - 1).toLong()),
                inReplyTo = null,
                threadId = "thread-r4-$msgSeq"
            )
        }

        // 8 inbound from recipient6 (1 is a reply).
        repeat(8) { i ->
            saveMessage(
                account, uid = uid++, msgSeq = msgSeq++,
                from = "Recipient Six <recipient6@example.com>",
                to = listOf("me@oconeco.com"),
                receivedAt = baseTime.plusHours((msgSeq - 1).toLong()),
                inReplyTo = if (i == 0) "<my-prior-msg@oconeco.com>" else null,
                threadId = "thread-r6"
            )
        }

        // 6 inbound from recipient7
        repeat(6) {
            saveMessage(
                account, uid = uid++, msgSeq = msgSeq++,
                from = "recipient7@example.com",
                to = listOf("me@oconeco.com"),
                receivedAt = baseTime.plusHours((msgSeq - 1).toLong()),
                inReplyTo = null,
                threadId = "thread-r7-$msgSeq"
            )
        }

        // 5 inbound from recipient8
        repeat(5) {
            saveMessage(
                account, uid = uid++, msgSeq = msgSeq++,
                from = "recipient8@example.com",
                to = listOf("me@oconeco.com"),
                receivedAt = baseTime.plusHours((msgSeq - 1).toLong()),
                inReplyTo = null,
                threadId = "thread-r8"
            )
        }

        // 4 inbound from recipient9
        repeat(4) {
            saveMessage(
                account, uid = uid++, msgSeq = msgSeq++,
                from = "recipient9@example.com",
                to = listOf("me@oconeco.com"),
                receivedAt = baseTime.plusHours((msgSeq - 1).toLong()),
                inReplyTo = null,
                threadId = "thread-r9"
            )
        }

        // 7 inbound from recipient10 across 2 threads
        repeat(7) { i ->
            saveMessage(
                account, uid = uid++, msgSeq = msgSeq++,
                from = "recipient10@example.com",
                to = listOf("me@oconeco.com"),
                receivedAt = baseTime.plusHours((msgSeq - 1).toLong()),
                inReplyTo = null,
                threadId = if (i < 4) "thread-r10-a" else "thread-r10-b"
            )
        }

        val totalMessages = emailMessageRepository.count()
        assertThat(totalMessages).isEqualTo(50L)

        // -- Recompute and verify --
        val touched = emailContactService.recomputeForAccount(account.id!!)
        assertThat(touched).isEqualTo(10)

        val contacts = emailContactRepository
            .findByEmailAccountId(account.id!!, PageRequest.of(0, 100, Sort.by("normalizedAddress")))
            .content
            .associateBy { it.normalizedAddress }

        assertThat(contacts.keys).containsExactlyInAnyOrder(
            "recipient1@example.com",
            "recipient2@example.com",
            "recipient3@example.com",
            "recipient4@example.com",
            "recipient5@example.com",
            "recipient6@example.com",
            "recipient7@example.com",
            "recipient8@example.com",
            "recipient9@example.com",
            "recipient10@example.com"
        )

        val r1 = contacts.getValue("recipient1@example.com")
        assertThat(r1.sentToCount).isEqualTo(5L)
        assertThat(r1.receivedFromCount).isZero
        assertThat(r1.repliedToCount).isEqualTo(2L)
        assertThat(r1.repliedFromCount).isZero
        assertThat(r1.threadsAppearedIn).isEqualTo(3L)
        assertThat(r1.firstSeen).isNotNull
        assertThat(r1.lastSeen).isNotNull
        assertThat(r1.lastRecomputedAt).isNotNull

        val r3 = contacts.getValue("recipient3@example.com")
        // Plus-suffix collapsed into a single contact row.
        assertThat(r3.sentToCount).isEqualTo(6L)
        assertThat(r3.threadsAppearedIn).isEqualTo(6L)

        val r4 = contacts.getValue("recipient4@example.com")
        // Display-name extracted from the angle-bracket header form.
        assertThat(r4.sentToCount).isEqualTo(5L)
        assertThat(r4.displayNameLatest).isEqualTo("Recipient4")

        val r5 = contacts.getValue("recipient5@example.com")
        // Co-recipient on the same 5 messages — counted once per message, not summed.
        assertThat(r5.sentToCount).isEqualTo(5L)

        val r6 = contacts.getValue("recipient6@example.com")
        assertThat(r6.sentToCount).isZero
        assertThat(r6.receivedFromCount).isEqualTo(8L)
        assertThat(r6.repliedFromCount).isEqualTo(1L)
        assertThat(r6.repliedToCount).isZero
        assertThat(r6.threadsAppearedIn).isEqualTo(1L)
        assertThat(r6.displayNameLatest).isEqualTo("Recipient Six")

        val r10 = contacts.getValue("recipient10@example.com")
        assertThat(r10.receivedFromCount).isEqualTo(7L)
        assertThat(r10.threadsAppearedIn).isEqualTo(2L)

        // -- Idempotency: re-run produces the same numbers. --
        val touchedAgain = emailContactService.recomputeForAccount(account.id!!)
        assertThat(touchedAgain).isEqualTo(10)

        val r1Again = emailContactRepository
            .findByEmailAccountIdAndNormalizedAddress(account.id!!, "recipient1@example.com")!!
        assertThat(r1Again.sentToCount).isEqualTo(5L)
        assertThat(r1Again.repliedToCount).isEqualTo(2L)
        // The row was reused (same id), not re-created.
        assertThat(r1Again.id).isEqualTo(r1.id)
    }

    private fun saveAccount(email: String): EmailAccount {
        val account = EmailAccount().apply {
            this.email = email
            this.uri = "email://$email"
            this.provider = EmailProvider.GENERIC_IMAP
            this.imapHost = "imap.example.com"
            this.imapPort = 993
            this.useSsl = true
            this.enabled = true
            this.version = 1L
        }
        return emailAccountRepository.save(account)
    }

    private fun saveMessage(
        account: EmailAccount,
        uid: Long,
        msgSeq: Int,
        from: String,
        to: List<String>,
        cc: List<String> = emptyList(),
        receivedAt: OffsetDateTime,
        inReplyTo: String?,
        threadId: String?
    ): EmailMessage {
        val msg = EmailMessage().apply {
            this.messageId = "<msg-$msgSeq@oconeco.com>"
            this.imapUid = uid
            this.uri = "email://${account.id ?: "noid"}/INBOX/$uid"
            this.fromAddress = from
            this.toAddresses = jsonArray(to)
            this.ccAddresses = jsonArray(cc)
            this.bccAddresses = null
            this.subject = "Subject $msgSeq"
            this.sentDate = receivedAt
            this.receivedDate = receivedAt
            this.inReplyTo = inReplyTo
            this.threadId = threadId
            this.fetchStatus = FetchStatus.COMPLETE
            this.bodyText = "Body $msgSeq"
            this.bodySize = 6L
            this.emailAccount = account
            this.version = 1L
        }
        return emailMessageRepository.save(msg)
    }

    private fun jsonArray(items: List<String>): String? {
        if (items.isEmpty()) return null
        // EmailQuickSyncProcessor writes a JSON array of strings; mirror that.
        val escaped = items.joinToString(",") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
        return "[$escaped]"
    }
}
