package com.oconeco.spring_search_tempo.base.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration


/**
 * Configuration properties for email crawling.
 *
 * The YAML `accounts:` block carries default host/port/region info for accounts the app
 * should know about at startup. Credentials are NOT read from configuration — they live
 * exclusively in the DB (encrypted, set via the account edit page). See issue #55.
 *
 * Example configuration:
 * ```yaml
 * app:
 *   email:
 *     enabled: true
 *     quick-sync-folders: [INBOX, Sent]
 *     accounts:
 *       - name: "personal-gmail"
 *         email: "you@gmail.com"
 *         provider: GMAIL
 * ```
 */
@Configuration
@ConfigurationProperties(prefix = "app.email")
data class EmailConfiguration(
    var enabled: Boolean = false,
    var quickSyncFolders: List<String> = listOf("INBOX", "Sent"),
    var accounts: List<EmailAccountConfig> = emptyList(),
    var errorDisplay: ErrorDisplayConfig = ErrorDisplayConfig(),
    /**
     * Issue #84: drift detection for server-side folder list. When the account's
     * `lastFolderEnumeratedAt` is older than this many hours, the orchestrator
     * re-enumerates folders before resolving the sync set. Cheap (single
     * LIST *), so the default is intentionally conservative — 24h matches the
     * "rare enough that the operator notices new folders by hand within a day"
     * heuristic.
     */
    var folderEnumMaxAgeHours: Long = 24,
)

/**
 * Controls how stale `EmailAccount.lastError` values are rendered in the UI (issue #59).
 *
 * If the error is older than `maxAgeDays`, the card is suppressed entirely — the row in
 * the DB is retained for debugging but treated as expired for display purposes. The
 * assumption: if the failure were still relevant, you'd have hit it again recently and
 * the timestamp would have refreshed.
 */
data class ErrorDisplayConfig(
    var maxAgeDays: Int = 7
)

/**
 * Individual email account configuration.
 *
 * Provides defaults for IMAP host/port/provider/region. Credentials are set in the UI
 * (stored DB-encrypted) — not in this configuration object.
 */
data class EmailAccountConfig(
    var name: String = "",
    var email: String = "",
    var provider: String = "GENERIC_IMAP",  // GMAIL, WORKMAIL, GENERIC_IMAP
    var imapHost: String? = null,
    var imapPort: Int = 993,
    var useSsl: Boolean = true,
    var enabled: Boolean = true,
    var region: String = "us-east-1"  // For WorkMail
)
