package com.oconeco.spring_search_tempo.base.model

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.EmailCategory
import com.oconeco.spring_search_tempo.base.domain.FetchStatus
import com.oconeco.spring_search_tempo.base.domain.Status
import jakarta.validation.constraints.NotNull
import java.time.OffsetDateTime


class EmailMessageDTO {

    var id: Long? = null

    @NotNull
    var uri: String? = null

    var status: Status? = Status.NEW

    var analysisStatus: AnalysisStatus? = AnalysisStatus.INDEX

    var label: String? = null

    var description: String? = null

    var type: String? = null

    var crawlDepth: Int? = null

    var size: Long? = null

    @NotNull
    var version: Long? = null

    var archived: Boolean? = null

    var jobRunId: Long? = null

    var sourceHost: String? = null

    // Email identifiers
    var messageId: String? = null

    var imapUid: Long? = null

    // Two-pass sync status
    var fetchStatus: FetchStatus = FetchStatus.HEADERS_ONLY

    // Envelope data
    var subject: String? = null

    var fromAddress: String? = null

    var toAddresses: String? = null

    var ccAddresses: String? = null

    var bccAddresses: String? = null

    var sentDate: OffsetDateTime? = null

    var receivedDate: OffsetDateTime? = null

    // Content
    var bodyText: String? = null

    var bodySize: Long? = null

    var bodyHtml: String? = null

    // Email metadata
    var contentType: String? = null

    var hasAttachments: Boolean = false

    var attachmentCount: Int = 0

    var attachmentNames: String? = null

    // Read/unread status
    var isRead: Boolean = false

    // Threading
    var inReplyTo: String? = null

    var references: String? = null

    var threadId: String? = null

    // Tags (as IDs for display)
    var tagIds: MutableList<Long> = mutableListOf()

    // Tags with full details (only populated when needed)
    var tags: MutableList<EmailTagDTO> = mutableListOf()

    // Categorization
    var category: EmailCategory = EmailCategory.UNCATEGORIZED

    var categoryConfidence: Double? = null

    var categorizedAt: OffsetDateTime? = null

    // Relationships (as IDs)
    var emailAccount: Long? = null

    var emailFolder: Long? = null

}
