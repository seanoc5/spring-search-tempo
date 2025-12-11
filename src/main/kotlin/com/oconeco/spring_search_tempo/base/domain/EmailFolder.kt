package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany


/**
 * IMAP folder metadata and sync state.
 *
 * Tracks folder-level sync progress for incremental crawling.
 * Supports standard folder types (INBOX, Sent, Drafts, etc.) and custom folders.
 */
@Entity
class EmailFolder : SaveableObject() {

    @Column(nullable = false, columnDefinition = "text")
    var folderName: String? = null  // e.g., "INBOX", "Sent", "[Gmail]/All Mail"

    @Column(columnDefinition = "text")
    var fullPath: String? = null  // Full IMAP path

    // Sync state
    @Column
    var lastSyncUid: Long? = null  // Highest UID synced from this folder

    @Column
    var messageCount: Long = 0  // Last known message count

    @Column
    var uidValidity: Long? = null  // IMAP UIDVALIDITY (detect folder recreations)

    // Folder type flags (for quick filtering)
    @Column
    var isInbox: Boolean = false

    @Column
    var isSent: Boolean = false

    @Column
    var isDraft: Boolean = false

    @Column
    var isTrash: Boolean = false

    @Column
    var isSpam: Boolean = false

    @Column
    var isArchive: Boolean = false

    // Relationships
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "email_account_id")
    var emailAccount: EmailAccount? = null

    @OneToMany(mappedBy = "emailFolder")
    var messages: MutableSet<EmailMessage> = mutableSetOf()

}
