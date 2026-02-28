package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.config.EmailConfiguration
import com.oconeco.spring_search_tempo.base.config.TestContainersConfig
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import com.oconeco.spring_search_tempo.base.model.EmailAccountDTO
import jakarta.mail.Folder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/**
 * Integration tests for IMAP connectivity.
 *
 * These tests require real credentials and are disabled by default.
 * To run:
 *   1. Set environment variables:
 *      export GMAIL_APP_PASSWORD="your-app-password"
 *   2. Run with local profile:
 *      ./gradlew test --tests ImapConnectionServiceIntegrationTest -Dspring.profiles.active=test,local
 *
 * Or run individual tests from IDE with env vars configured.
 */
@SpringBootTest
@ActiveProfiles("test", "local")
@Import(TestContainersConfig::class)
@Tag("integration")
@Tag("manual")
@DisplayName("IMAP Connection Integration Tests")
class ImapConnectionServiceIntegrationTest {

    companion object {
        private val log = LoggerFactory.getLogger(ImapConnectionServiceIntegrationTest::class.java)
    }

    @Autowired
    private lateinit var imapConnectionService: ImapConnectionService

    @Autowired
    private lateinit var emailConfiguration: EmailConfiguration

    @Test
    @DisplayName("Should load email configuration from local profile")
    fun testConfigurationLoaded() {
        log.info("Email configuration enabled: {}", emailConfiguration.enabled)
        log.info("Configured accounts: {}", emailConfiguration.accounts.size)

        emailConfiguration.accounts.forEach { account ->
            log.info("  Account: {} ({})", account.email, account.provider)
        }

        assertTrue(emailConfiguration.enabled, "Email should be enabled in local profile")
        assertTrue(emailConfiguration.accounts.isNotEmpty(), "Should have at least one account configured")
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "GMAIL_APP_PASSWORD", matches = ".+")
    @DisplayName("Should authenticate to Gmail IMAP server")
    fun testGmailAuthentication() {
        val gmailAccount = findGmailAccount()
            ?: run {
                log.warn("No Gmail account configured, skipping test")
                return
            }

        log.info("Testing authentication for: {}", gmailAccount.email)
        log.info("Credential env var configured: {}", gmailAccount.credentialEnvVar)

        val passwordEnvVar = gmailAccount.credentialEnvVar
        val passwordValue = System.getenv(passwordEnvVar)
        log.info("Env var '{}' is set: {}, length: {}",
            passwordEnvVar,
            passwordValue != null,
            passwordValue?.length ?: 0)

        val account = EmailAccountDTO().apply {
            email = gmailAccount.email
            label = gmailAccount.name
            provider = EmailProvider.GMAIL
        }

        val connected = imapConnectionService.testConnection(account)
        assertTrue(connected, "Should successfully authenticate to Gmail. Check that your app password is correct at https://myaccount.google.com/apppasswords")
        log.info("Authentication successful for {}", account.email)
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "GMAIL_APP_PASSWORD", matches = ".+")
    @DisplayName("Should list IMAP folders from Gmail")
    fun testListGmailFolders() {
        val gmailAccount = findGmailAccount()
            ?: run {
                log.warn("No Gmail account configured, skipping test")
                return
            }

        log.info("Listing folders for: {}", gmailAccount.email)

        val account = EmailAccountDTO().apply {
            email = gmailAccount.email
            label = gmailAccount.name
            provider = EmailProvider.GMAIL
        }

        imapConnectionService.connect(account).use { store ->
            assertTrue(store.isConnected, "Store should be connected")

            val defaultFolder = store.defaultFolder
            val folders = defaultFolder.list("*")

            log.info("Found {} folders:", folders.size)
            folders.forEach { folder ->
                val messageCount = try {
                    folder.open(Folder.READ_ONLY)
                    val count = folder.messageCount
                    folder.close(false)
                    count
                } catch (e: Exception) {
                    -1 // Folder might not be openable
                }
                log.info("  {} ({} messages)", folder.fullName, messageCount)
            }

            assertTrue(folders.isNotEmpty(), "Should have at least one folder")

            // Check for common Gmail folders
            val folderNames = folders.map { it.fullName.uppercase() }
            val hasInbox = folderNames.any { it.contains("INBOX") }
            assertTrue(hasInbox, "Should have INBOX folder")
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "GMAIL_APP_PASSWORD", matches = ".+")
    @DisplayName("Should read INBOX message count")
    fun testReadInboxMessageCount() {
        val gmailAccount = findGmailAccount()
            ?: run {
                log.warn("No Gmail account configured, skipping test")
                return
            }

        val account = EmailAccountDTO().apply {
            email = gmailAccount.email
            label = gmailAccount.name
            provider = EmailProvider.GMAIL
        }

        imapConnectionService.connect(account).use { store ->
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)

            val totalMessages = inbox.messageCount
            val unreadMessages = inbox.unreadMessageCount

            log.info("INBOX stats for {}:", account.email)
            log.info("  Total messages: {}", totalMessages)
            log.info("  Unread messages: {}", unreadMessages)

            assertTrue(totalMessages >= 0, "Message count should be non-negative")

            inbox.close(false)
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "GMAIL_APP_PASSWORD", matches = ".+")
    @DisplayName("Should read recent message headers from INBOX")
    fun testReadRecentMessageHeaders() {
        val gmailAccount = findGmailAccount()
            ?: run {
                log.warn("No Gmail account configured, skipping test")
                return
            }

        val account = EmailAccountDTO().apply {
            email = gmailAccount.email
            label = gmailAccount.name
            provider = EmailProvider.GMAIL
        }

        imapConnectionService.connect(account).use { store ->
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)

            val totalMessages = inbox.messageCount
            if (totalMessages == 0) {
                log.info("INBOX is empty, skipping header read test")
                inbox.close(false)
                return
            }

            // Read last 5 messages (most recent)
            val start = maxOf(1, totalMessages - 4)
            val messages = inbox.getMessages(start, totalMessages)

            log.info("Recent messages from INBOX:")
            messages.reversed().take(5).forEach { message ->
                log.info("  Subject: {}", message.subject ?: "(no subject)")
                log.info("    From: {}", message.from?.firstOrNull()?.toString() ?: "(unknown)")
                log.info("    Date: {}", message.sentDate)
                log.info("    ---")
            }

            assertTrue(messages.isNotEmpty(), "Should have at least one message")

            inbox.close(false)
        }
    }

    private fun findGmailAccount() = emailConfiguration.accounts.find {
        it.provider.equals("GMAIL", ignoreCase = true) && it.email.isNotBlank()
    }
}
