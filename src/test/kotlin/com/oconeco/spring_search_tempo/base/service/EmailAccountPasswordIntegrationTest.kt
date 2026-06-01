package com.oconeco.spring_search_tempo.base.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.EmailAccount
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import com.oconeco.spring_search_tempo.base.model.EmailAccountDTO
import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest(
    classes = [SpringSearchTempoApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@DisplayName("EmailAccount encrypted password persistence")
class EmailAccountPasswordIntegrationTest : BaseIT() {

    @Autowired
    lateinit var emailAccountService: EmailAccountService

    @Autowired
    lateinit var emailAccountRepository: EmailAccountRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    @DisplayName("setPassword persists encrypted ciphertext; getPassword decrypts; raw column starts with v1:")
    fun passwordPersistedEncryptedAndDecryptedOnRead() {
        val accountId = createAccount(email = "imap-test-1@example.com")

        emailAccountService.setPassword(accountId, "s3cret!")

        val raw = jdbcTemplate.queryForObject(
            "SELECT encrypted_password FROM email_account WHERE id = ?",
            String::class.java,
            accountId
        )
        assertThat(raw).isNotNull
        assertThat(raw).isNotEqualTo("s3cret!")
        assertThat(raw).startsWith("v1:")

        assertThat(emailAccountService.getPassword(accountId)).isEqualTo("s3cret!")
        assertThat(emailAccountService.hasPassword(accountId)).isTrue()
    }

    @Test
    @DisplayName("setPassword with blank clears the stored password")
    fun setPasswordBlankClearsStoredValue() {
        val accountId = createAccount(email = "imap-test-2@example.com")
        emailAccountService.setPassword(accountId, "first")
        assertThat(emailAccountService.hasPassword(accountId)).isTrue()

        emailAccountService.setPassword(accountId, "")
        assertThat(emailAccountService.hasPassword(accountId)).isFalse()
        assertThat(emailAccountService.getPassword(accountId)).isNull()
    }

    @Test
    @DisplayName("getPassword returns null when no encrypted password is set")
    fun getPasswordReturnsNullWhenUnset() {
        val accountId = createAccount(email = "imap-test-3@example.com")
        assertThat(emailAccountService.getPassword(accountId)).isNull()
        assertThat(emailAccountService.hasPassword(accountId)).isFalse()
    }

    @Test
    @DisplayName("EmailAccount.toString elides the encrypted password")
    fun toStringElidesPassword() {
        val accountId = createAccount(email = "imap-test-4@example.com")
        emailAccountService.setPassword(accountId, "s3cret!")

        val entity = emailAccountRepository.findById(accountId).orElseThrow()
        val rendered = entity.toString()
        assertThat(rendered).doesNotContain("s3cret!")
        assertThat(rendered).contains("[REDACTED]")
    }

    @Test
    @DisplayName("plaintext password never appears in logs during setPassword/getPassword")
    fun plaintextNeverAppearsInLogs() {
        val secret = "uniquely-traceable-plaintext-9f2c"
        val appender = ListAppender<ILoggingEvent>().apply { start() }

        // Attach to the entire base package so we catch service + service-impl logs.
        val log = LoggerFactory.getLogger("com.oconeco.spring_search_tempo") as Logger
        val priorLevel = log.level
        log.level = Level.DEBUG
        log.addAppender(appender)

        try {
            val accountId = createAccount(email = "imap-test-5@example.com")
            emailAccountService.setPassword(accountId, secret)
            emailAccountService.getPassword(accountId)
        } finally {
            log.detachAppender(appender)
            log.level = priorLevel
        }

        val captured = appender.list.joinToString("\n") { it.formattedMessage }
        assertThat(captured).doesNotContain(secret)
    }

    private fun createAccount(email: String): Long {
        // Insert directly via repository so we exercise the entity, not the DTO mapper.
        val account = EmailAccount().apply {
            this.email = email
            this.uri = "email://$email"
            this.provider = EmailProvider.GENERIC_IMAP
            this.imapHost = "imap.example.com"
            this.imapPort = 993
            this.useSsl = true
            this.enabled = true
            this.version = 1L
        }
        return emailAccountRepository.save(account).id!!
    }

    @Suppress("unused")
    private fun createAccountViaDto(email: String): Long {
        val dto = EmailAccountDTO().apply {
            this.email = email
            uri = "email://$email"
            provider = EmailProvider.GENERIC_IMAP
            imapHost = "imap.example.com"
            imapPort = 993
            useSsl = true
            enabled = true
            version = 1L
        }
        return emailAccountService.create(dto)
    }
}
