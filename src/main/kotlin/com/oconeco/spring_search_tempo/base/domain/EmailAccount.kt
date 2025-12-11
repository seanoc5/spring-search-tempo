package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.OneToMany
import java.time.OffsetDateTime


/**
 * Email account configuration and sync state.
 *
 * Stores IMAP connection settings and tracks sync progress for incremental crawling.
 * Supports Gmail (App Password/OAuth2), Amazon WorkMail, and generic IMAP providers.
 */
@Entity
class EmailAccount : SaveableObject() {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    var provider: EmailProvider? = null

    @Column(nullable = false, columnDefinition = "text")
    var email: String? = null

    @Column(columnDefinition = "text")
    var displayName: String? = null

    // IMAP connection settings (primarily for GENERIC_IMAP)
    @Column(columnDefinition = "text")
    var imapHost: String? = null

    @Column
    var imapPort: Int? = null

    @Column
    var useSsl: Boolean = true

    // Quick sync state (UID-based incremental)
    @Column
    var inboxLastSyncUid: Long? = null

    @Column
    var sentLastSyncUid: Long? = null

    @Column
    var lastQuickSyncAt: OffsetDateTime? = null

    // Full sync state (exhaustive weekly)
    @Column
    var lastFullSyncAt: OffsetDateTime? = null

    @Column
    var lastFullSyncFolderCount: Int? = null

    // Account status
    @Column
    var enabled: Boolean = true

    @Column(columnDefinition = "text")
    var lastError: String? = null

    @Column
    var lastErrorAt: OffsetDateTime? = null

    // Relationships
    @OneToMany(mappedBy = "emailAccount")
    var messages: MutableSet<EmailMessage> = mutableSetOf()

    @OneToMany(mappedBy = "emailAccount")
    var folders: MutableSet<EmailFolder> = mutableSetOf()

}

/**
 * Supported email providers with their default IMAP settings.
 */
enum class EmailProvider {
    GMAIL,          // imap.gmail.com:993 SSL
    WORKMAIL,       // imap.mail.{region}.awsapps.com:993 SSL
    GENERIC_IMAP    // User-configured host/port
}
