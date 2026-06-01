package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import java.time.OffsetDateTime


/**
 * Configuration for a source → destination IMAP mirror pair.
 *
 * Defines which folders are copied from a source `EmailAccount` to a destination
 * `EmailAccount`. Per ADR-005, this is the foundation entity for the IMAP mailbox
 * mirroring suite; the actual copy job (`ImapMirrorService`/`MirrorJob`) and
 * progress tracking are separate tickets.
 *
 * Folder mappings are persisted as a JSON array of `FolderMapping` rows to keep
 * the schema simple. A separate mapping table can be introduced later if cross-
 * mirror reuse becomes a requirement.
 */
@Entity
class MirrorConfig : SaveableObject() {

    @Column(nullable = false)
    var sourceAccountId: Long? = null

    @Column(nullable = false)
    var destAccountId: Long? = null

    @Column(nullable = false, columnDefinition = "text")
    var name: String? = null

    @Column(nullable = false)
    var enabled: Boolean = true

    /**
     * JSON array of folder-mapping rows:
     * `[{"source":"INBOX","dest":"INBOX","enabled":true}, ...]`.
     */
    @Column(columnDefinition = "text")
    var folderMappings: String? = "[]"

    /**
     * Throttle for APPEND calls against the destination server. Null = unthrottled.
     * Default 10/s is conservative for Gmail-class destinations (per ADR-005).
     */
    @Column
    var appendRateLimitPerSecond: Int? = 10

    @Column
    var lastRunStartedAt: OffsetDateTime? = null

    @Column
    var lastRunCompletedAt: OffsetDateTime? = null

    @Column(columnDefinition = "text")
    var lastError: String? = null
}
