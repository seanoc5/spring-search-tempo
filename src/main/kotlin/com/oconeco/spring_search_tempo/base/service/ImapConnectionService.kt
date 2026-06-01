package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.config.EmailAccountConfig
import com.oconeco.spring_search_tempo.base.config.EmailConfiguration
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import com.oconeco.spring_search_tempo.base.model.EmailAccountDTO
import jakarta.mail.Session
import jakarta.mail.Store
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import java.util.Properties


/**
 * Service for managing IMAP connections to email providers.
 *
 * Supports Gmail, Amazon WorkMail, and generic IMAP servers.
 * Credentials are loaded from the account's encrypted password column when set,
 * falling back to environment variables (via `credentialEnvVar`) for legacy accounts.
 */
@Service
class ImapConnectionService(
    private val emailConfiguration: EmailConfiguration,
    @Lazy private val emailAccountService: EmailAccountService
) {
    companion object {
        private val log = LoggerFactory.getLogger(ImapConnectionService::class.java)

        // Provider-specific IMAP settings
        val GMAIL_IMAP = ImapSettings("imap.gmail.com", 993, true)

        fun getWorkmailImap(region: String): ImapSettings {
            val foo = ImapSettings("imap.mail.$region.awsapps.com", 993, true)
            log.debug("Imap settings: {}", foo)
            return foo
        }
    }

    /**
     * Connect to an email account's IMAP server.
     *
     * @param account The email account DTO with provider and connection info
     * @return Connected IMAP Store (caller must close when done)
     * @throws IllegalStateException if credentials are not configured
     */
    fun connect(account: EmailAccountDTO): Store {
        val settings = getImapSettings(account)
        val password = getCredential(account)

        log.debug("\t\tConnecting to IMAP server: {}:{} for {}", settings.host, settings.port, account.email)

        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", settings.host)
            put("mail.imaps.port", settings.port.toString())
            put("mail.imaps.ssl.enable", settings.useSsl.toString())
            put("mail.imaps.connectiontimeout", "15000")
            put("mail.imaps.timeout", "60000")
            put("mail.imaps.ssl.trust", "*")  // Trust all certificates for now
            // Note: Don't set auth.mechanisms - let JavaMail auto-negotiate
            // XOAUTH2 requires OAuth2 tokens, not app passwords
        }

        val session = Session.getInstance(props)
        val store = session.getStore("imaps")

        try {
            store.connect(settings.host, account.email, password)
            log.info("Successfully connected to IMAP server for {}", account.email)
            return store
        } catch (e: Exception) {
            log.error("Failed to connect to IMAP server for {}: {}", account.email, e.message)
            throw e
        }
    }

    /**
     * Get IMAP settings based on the email provider.
     */
    private fun getImapSettings(account: EmailAccountDTO): ImapSettings {
        return when (account.provider) {
            EmailProvider.GMAIL -> GMAIL_IMAP
            EmailProvider.WORKMAIL -> {
                val config = findAccountConfig(account)
                getWorkmailImap(config?.region ?: "us-east-1")
            }
            EmailProvider.GENERIC_IMAP -> ImapSettings(
                account.imapHost
                    ?: throw IllegalStateException("IMAP host required for GENERIC_IMAP provider"),
                account.imapPort ?: 993,
                account.useSsl
            )
            null -> throw IllegalStateException("Provider not specified for account: ${account.email}")
        }
    }

    /**
     * Resolve the IMAP password for an account.
     *
     * Resolution order:
     *  1. Encrypted password stored on the account (preferred).
     *  2. `credentialEnvVar` on the account → environment variable lookup (legacy fallback).
     *  3. `credentialEnvVar` from `application.yml` for the matching account (legacy fallback).
     *
     * Never logs the plaintext password.
     */
    private fun getCredential(account: EmailAccountDTO): String {
        val id = account.id
        if (id != null) {
            // Only IllegalStateException ("key not configured") falls through to the env-var fallback —
            // a real crypto failure (e.g. AEADBadTagException from a rotated key or tampered ciphertext)
            // must propagate so the misconfiguration is loud, not silently masked by a stale env var.
            val decrypted = try {
                emailAccountService.getPassword(id)
            } catch (e: IllegalStateException) {
                log.warn("Encryption key not configured; will try env-var fallback for {}: {}", account.email, e.message)
                null
            }
            if (!decrypted.isNullOrBlank()) {
                return decrypted
            }
        }

        val envVar = account.credentialEnvVar?.takeIf { it.isNotBlank() }
            ?: findAccountConfig(account)?.credentialEnvVar
            ?: throw IllegalStateException(
                "No credential configured for ${account.email}. " +
                "Either set a password on the account edit page or configure a credentialEnvVar in application.yml."
            )

        return System.getenv(envVar)
            ?: throw IllegalStateException(
                "Environment variable '$envVar' not set for ${account.email}. " +
                "Set it with: export $envVar=your_password (or set the password via the account edit page)."
            )
    }

    /**
     * Find the account configuration from application.yml.
     */
    private fun findAccountConfig(account: EmailAccountDTO): EmailAccountConfig? {
        return emailConfiguration.accounts.find {
            it.email == account.email || it.name == account.label
        }
    }

    /**
     * Test connection to an email account without keeping it open.
     *
     * @return true if connection succeeded, false otherwise
     */
    fun testConnection(account: EmailAccountDTO): Boolean {
        return try {
            connect(account).use { store ->
                store.isConnected
            }
        } catch (e: Exception) {
            log.warn("Connection test failed for {}: {}", account.email, e.message)
            false
        }
    }

    /**
     * Execute a block with a connected IMAP store.
     * The store is automatically closed after the block completes.
     *
     * @param account The email account to connect to
     * @param block The block to execute with the connected store
     * @return The result of the block
     */
    fun <T> withConnection(account: EmailAccountDTO, block: (Store) -> T): T {
        return connect(account).use { store -> block(store) }
    }
}

/**
 * IMAP server connection settings.
 */
data class ImapSettings(
    val host: String,
    val port: Int,
    val useSsl: Boolean
)
