package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.EmailAccount
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import com.oconeco.spring_search_tempo.base.model.EmailAccountDTO
import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Companion to [ImapCredentialResolutionIT]: same credential-resolution layer, but
 * launched with both encryption-key properties blank so [EncryptionService.decrypt]
 * throws when called. Ensures the "key not configured" error surfaces verbatim
 * instead of being swallowed (the prior implementation caught
 * [IllegalStateException] and silently routed to the env-var fallback).
 *
 * We seed a ciphertext-shaped payload directly into the `encrypted_password`
 * column via JDBC — `EmailAccountService.setPassword()` requires a working key,
 * so we can't go through it in this profile.
 */
@SpringBootTest(
    classes = [SpringSearchTempoApplication::class],
    properties = [
        "app.security.encryption-key=",
        "app.onedrive.token-encryption-key="
    ]
)
@DisplayName("ImapConnectionService credential resolution — encryption key unset (issue #55)")
class ImapCredentialResolutionMissingKeyIT : BaseIT() {

    @Autowired
    lateinit var imapConnectionService: ImapConnectionService

    @Autowired
    lateinit var emailAccountRepository: EmailAccountRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    @DisplayName("Account with encrypted_password set but no key → encryption-key error propagates")
    fun propagatesEncryptionKeyErrorWhenKeyMissing() {
        val id = saveAccountWithCiphertext("missing-key@example.com", "v1:not-a-real-ciphertext")

        val dto = EmailAccountDTO().apply {
            this.id = id
            this.email = "missing-key@example.com"
            this.provider = EmailProvider.GENERIC_IMAP
        }

        val thrown = assertThatThrownBy { invokeGetCredentialUnwrapped(dto) }
            .isInstanceOf(IllegalStateException::class.java)

        thrown.hasMessageContaining("Encryption key not configured")

        // Negative assertions — the encryption-key error must not have been masked
        // by a fallback to the legacy env-var path, and must not mention any of
        // those phrases (which would imply the fallback is back).
        thrown.extracting { it.message }.satisfies({ msg ->
            val m = msg as String
            assertThat(m).doesNotContain("GMAIL_APP_PASSWORD")
            assertThat(m).doesNotContain("environment variable")
            assertThat(m).doesNotContainIgnoringCase("export ")
        })
    }

    private fun saveAccountWithCiphertext(email: String, ciphertext: String): Long {
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
        val id = emailAccountRepository.save(account).id!!
        jdbcTemplate.update(
            "UPDATE email_account SET encrypted_password = ? WHERE id = ?",
            ciphertext, id
        )
        return id
    }

    private fun invokeGetCredentialUnwrapped(dto: EmailAccountDTO): String {
        val method = ImapConnectionService::class.java
            .getDeclaredMethod("getCredential", EmailAccountDTO::class.java)
        method.isAccessible = true
        try {
            return method.invoke(imapConnectionService, dto) as String
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }
}
