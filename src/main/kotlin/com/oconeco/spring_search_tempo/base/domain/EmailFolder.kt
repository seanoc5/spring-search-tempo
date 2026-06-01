package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import java.time.OffsetDateTime


/**
 * IMAP folder metadata and sync state.
 *
 * Tracks folder-level sync progress for incremental crawling.
 * Supports standard folder types (INBOX, Sent, Drafts, etc.) and custom folders.
 */
@Entity
class EmailFolder : SaveableObject() {

    @Column(nullable = false, columnDefinition = "text")
    var folderName: String? = null  // Leaf name (e.g. "Archive", "INBOX")

    @Column(columnDefinition = "text")
    var path: String? = null  // Full hierarchy path (e.g. "INBOX/Archive/2025")

    /** IMAP hierarchy delimiter as reported by the server (often '/' or '.'). */
    @Column(length = 4)
    var delimiter: String? = null

    // Sync state
    @Column
    var lastSyncUid: Long? = null  // Highest UID synced from this folder

    @Column
    var messageCount: Long = 0  // Last known message count

    @Column
    var uidValidity: Long? = null  // IMAP UIDVALIDITY (detect folder recreations)

    /**
     * Whether this folder is included in `EmailQuickSyncReader.fetchTargets()`.
     * Defaults to true for selectable folders, false for `\Noselect` containers
     * such as `[Gmail]`.
     */
    @Column(nullable = false)
    var syncEnabled: Boolean = true

    @Column
    var lastEnumeratedAt: OffsetDateTime? = null

    // IMAP attribute flags (from `LIST` response — boolean for portability across servers)
    @Column
    var hasChildren: Boolean = false

    @Column
    var noselect: Boolean = false

    @Column
    var noinferiors: Boolean = false

    @Column
    var marked: Boolean = false

    @Column
    var unmarked: Boolean = false

    @Column
    var subscribed: Boolean = false

    // Folder type flags (semantic — derived from name and `\Trash` / `\Sent` etc.)
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
