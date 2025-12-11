package com.oconeco.spring_search_tempo.base.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration


/**
 * Configuration properties for email crawling.
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
 *         credential-env-var: "GMAIL_APP_PASSWORD"
 * ```
 */
@Configuration
@ConfigurationProperties(prefix = "app.email")
data class EmailConfiguration(
    var enabled: Boolean = false,
    var quickSyncFolders: List<String> = listOf("INBOX", "Sent"),
    var accounts: List<EmailAccountConfig> = emptyList()
)

/**
 * Individual email account configuration.
 *
 * Credentials are loaded from environment variables for security.
 */
data class EmailAccountConfig(
    var name: String = "",
    var email: String = "",
    var provider: String = "GENERIC_IMAP",  // GMAIL, WORKMAIL, GENERIC_IMAP
    var imapHost: String? = null,
    var imapPort: Int = 993,
    var useSsl: Boolean = true,
    var credentialEnvVar: String? = null,  // e.g., "GMAIL_APP_PASSWORD"
    var enabled: Boolean = true,
    var region: String = "us-east-1"  // For WorkMail
)
