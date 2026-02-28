package com.oconeco.spring_search_tempo.base.config

import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import com.oconeco.spring_search_tempo.base.model.EmailAccountDTO
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Initializes email accounts from application configuration at startup.
 *
 * Creates database records for any email accounts defined in application.yml
 * that don't already exist. This ensures accounts are visible in the UI
 * immediately, without needing to trigger a sync first.
 *
 * Accounts with blank/empty email addresses are skipped (env var not set).
 */
@Component
@Order(100) // Run after other initializers
class EmailAccountInitializer(
    private val emailConfiguration: EmailConfiguration,
    private val emailAccountService: EmailAccountService
) : ApplicationRunner {

    companion object {
        private val log = LoggerFactory.getLogger(EmailAccountInitializer::class.java)
    }

    override fun run(args: ApplicationArguments?) {
        if (!emailConfiguration.enabled) {
            log.info("Email crawling is disabled, skipping account initialization")
            return
        }

        val configAccounts = emailConfiguration.accounts
        if (configAccounts.isEmpty()) {
            log.info("No email accounts configured in application.yml")
            return
        }

        log.info("Initializing {} email account(s) from configuration", configAccounts.size)

        var created = 0
        var skipped = 0
        var existing = 0

        configAccounts.forEach { config ->
            try {
                // Skip accounts with blank email (env var not set)
                if (config.email.isBlank()) {
                    log.debug("Skipping account '{}': email is blank (env var not set?)", config.name)
                    skipped++
                    return@forEach
                }

                // Check if account already exists
                val existingAccount = try {
                    emailAccountService.findByEmail(config.email)
                } catch (e: Exception) {
                    null
                }

                if (existingAccount != null) {
                    log.debug("Account '{}' already exists (id={})", config.email, existingAccount.id)
                    existing++
                    return@forEach
                }

                // Create new account
                val dto = EmailAccountDTO().apply {
                    email = config.email
                    label = config.name
                    provider = EmailProvider.valueOf(config.provider)
                    imapHost = config.imapHost
                    imapPort = config.imapPort
                    useSsl = config.useSsl
                    enabled = config.enabled
                    uri = "email://${config.email}"
                    version = 1L
                }

                val id = emailAccountService.create(dto)
                log.info("Created email account '{}' (id={}) from configuration", config.email, id)
                created++

            } catch (e: Exception) {
                log.error("Failed to initialize email account '{}': {}", config.email, e.message, e)
            }
        }

        log.info("Email account initialization complete: {} created, {} existing, {} skipped",
            created, existing, skipped)
    }
}
