package com.oconeco.spring_search_tempo.base.config

import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
import com.oconeco.spring_search_tempo.base.service.EncryptionService
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Startup-time check: if at-rest encryption is unconfigured AND the DB holds rows that
 * require decryption (currently: EmailAccount.encrypted_password), surface a loud,
 * actionable ERROR banner before the batch jobs trip on it mid-run.
 *
 * Why this exists: without the key, the first time EmailQuickSyncJob tries to decrypt
 * an IMAP password the failure surfaces deep in a batch-job stack trace, which makes it
 * look like a sync bug rather than a missing config. This check makes the diagnosis
 * the first thing the user sees on startup.
 */
@Component
class EncryptionKeyStartupCheck(
    private val encryptionService: EncryptionService,
    private val emailAccountRepository: EmailAccountRepository
) {

    private val log = LoggerFactory.getLogger(EncryptionKeyStartupCheck::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun check() {
        if (encryptionService.isConfigured()) return

        val encryptedAccounts = emailAccountRepository.countByEncryptedPasswordIsNotNull()
        if (encryptedAccounts == 0L) {
            // Key missing but nothing in the DB needs it yet — the existing PostConstruct
            // WARN in EncryptionService is enough.
            return
        }

        log.error("╔══════════════════════════════════════════════════════════════════════════════")
        log.error("║  ENCRYPTION KEY NOT CONFIGURED — sync jobs will fail on decrypt")
        log.error("╠══════════════════════════════════════════════════════════════════════════════")
        log.error("║  app.security.encryption-key (env APP_ENCRYPTION_KEY) is not set, but")
        log.error("║  {} EmailAccount row(s) have an encrypted_password that requires decryption.", encryptedAccounts)
        log.error("║")
        log.error("║  IMAP quick-sync, body enrichment, and any other path that reads stored")
        log.error("║  credentials will fail with IllegalStateException at first decrypt attempt.")
        log.error("╠══════════════════════════════════════════════════════════════════════════════")
        log.error("║  FIX (pick ONE):")
        log.error("║")
        log.error("║  1. Generate a 32-byte AES-256 key:")
        log.error("║       openssl rand -base64 32")
        log.error("║")
        log.error("║  2. Either set it as an environment variable:")
        log.error("║       export APP_ENCRYPTION_KEY='<paste-key-here>'")
        log.error("║     …and restart the app.")
        log.error("║")
        log.error("║     OR add it to src/main/resources/application-local.yml (already")
        log.error("║     gitignored — safe for secrets in dev):")
        log.error("║       app:")
        log.error("║         security:")
        log.error("║           encryption-key: \"<paste-key-here>\"")
        log.error("║     …then run with --spring.profiles.active=dev,local (you already are).")
        log.error("╠══════════════════════════════════════════════════════════════════════════════")
        log.error("║  SECURITY NOTES:")
        log.error("║  - .gitignore already excludes application-local.yml. Verify before committing.")
        log.error("║  - NEVER commit the key value to git, paste it in PRs, or include it in logs.")
        log.error("║  - The same key must be used across all environments that share a DB — losing")
        log.error("║    it means encrypted_password values become unrecoverable; you'd have to")
        log.error("║    NULL them and have users re-enter their IMAP / app passwords.")
        log.error("╚══════════════════════════════════════════════════════════════════════════════")
    }
}
