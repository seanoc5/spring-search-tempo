package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.OneToMany
import java.time.OffsetDateTime


/**
 * OneDrive account configuration, OAuth2 token storage, and sync state.
 *
 * Stores encrypted refresh tokens for OAuth2 PKCE flow and tracks delta sync progress
 * for incremental syncing via Microsoft Graph delta API.
 */
@Entity
class OneDriveAccount : SaveableObject() {

    // Microsoft identity
    @Column(columnDefinition = "text")
    var microsoftAccountId: String? = null

    @Column(columnDefinition = "text")
    var displayName: String? = null

    @Column(columnDefinition = "text")
    var email: String? = null

    // OAuth2 / PKCE
    @Column(nullable = false, columnDefinition = "text")
    var clientId: String? = null

    @Column(columnDefinition = "text")
    var encryptedRefreshToken: String? = null

    @Column
    var tokenObtainedAt: OffsetDateTime? = null

    // Drive info (from /me/drive)
    @Column(columnDefinition = "text")
    var driveId: String? = null

    @Column(columnDefinition = "text")
    var driveType: String? = null

    @Column
    var driveQuotaTotal: Long? = null

    @Column
    var driveQuotaUsed: Long? = null

    // Delta sync state
    @Column(columnDefinition = "text")
    var deltaToken: String? = null

    @Column
    var lastDeltaSyncAt: OffsetDateTime? = null

    @Column
    var lastFullSyncAt: OffsetDateTime? = null

    // Account status
    @Column(nullable = false)
    var enabled: Boolean = true

    @Column(columnDefinition = "text")
    var lastError: String? = null

    @Column
    var lastErrorAt: OffsetDateTime? = null

    // Aggregated stats
    @Column
    var totalItems: Long = 0

    @Column
    var totalSize: Long = 0

    // Relationships
    @OneToMany(mappedBy = "oneDriveAccount")
    var items: MutableSet<OneDriveItem> = mutableSetOf()

}
