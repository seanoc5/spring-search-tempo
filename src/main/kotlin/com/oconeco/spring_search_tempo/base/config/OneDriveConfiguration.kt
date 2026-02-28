package com.oconeco.spring_search_tempo.base.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration


/**
 * Configuration properties for OneDrive integration.
 *
 * Example configuration:
 * ```yaml
 * app:
 *   onedrive:
 *     enabled: true
 *     client-id: "${ONEDRIVE_CLIENT_ID:}"
 *     redirect-uri: "http://localhost:8089/oneDriveAccounts/callback"
 *     scopes: [Files.Read, offline_access, User.Read]
 *     token-encryption-key: "${ONEDRIVE_TOKEN_ENCRYPTION_KEY:}"
 *     download-temp-dir: "/tmp/onedrive-downloads"
 *     max-download-size-mb: 100
 * ```
 */
@Configuration
@ConfigurationProperties(prefix = "app.onedrive")
data class OneDriveConfiguration(
    var enabled: Boolean = false,
    var clientId: String = "",
    var redirectUri: String = "http://localhost:8089/oneDriveAccounts/callback",
    var scopes: List<String> = listOf("Files.Read", "offline_access", "User.Read"),
    var tokenEncryptionKey: String = "",
    var downloadTempDir: String = "/tmp/onedrive-downloads",
    var maxDownloadSizeMb: Int = 100
)
