package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.EmailAccountService
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

/**
 * Credential-resolution coverage for [ImapConnectionService] after the env-var
 * fallback was removed in issue #55. DB-encrypted password is now the only
 * supported credential path.
 *
 * The private `getCredential(EmailAccountDTO)` method is invoked via reflection
 * so we can assert on it directly without standing up an IMAP server (and so the
 * happy-path test doesn't end up exercising store.connect() against a real host).
 *
 * Every error-message assertion includes a paired negative assertion that the
 * legacy env-var phrases ("GMAIL_APP_PASSWORD", "environment variable", "export")
 * are absent — accidental reintroduction of the fallback would fail these tests.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@DisplayName("ImapConnectionService credential resolution (issue #55)")
class ImapCredentialResolutionIT : BaseIT() {

    @Autowired
    lateinit var imapConnectionService: ImapConnectionService

    @Autowired
    lateinit var emailAccountService: EmailAccountService

    @Autowired
    lateinit var emailAccountRepository: EmailAccountRepository

    @Test
    @DisplayName("Account with stored encrypted password → getCredential returns plaintext")
    fun resolvesEncryptedPassword() {
        val id = saveAccount("creds-happy@example.com")
        emailAccountService.setPassword(id, "s3cret-app-pwd")

        val resolved = invokeGetCredential(emailAccountService.get(id))

        assertThat(resolved).isEqualTo("s3cret-app-pwd")
    }

    @Test
    @DisplayName("Account with no stored password → IllegalStateException pointing at the Edit page")
    fun throwsWithEditHintWhenNoPassword() {
        val id = saveAccount("creds-missing@example.com")

        val dto = emailAccountService.get(id)
        val thrown = assertThatThrownBy { invokeGetCredentialUnwrapped(dto) }
            .isInstanceOf(IllegalStateException::class.java)

        thrown.hasMessageContaining("No password configured")
        thrown.hasMessageContaining("creds-missing@example.com")
        thrown.hasMessageContaining("Open the account page and click Edit")

        // Negative assertions — the legacy env-var phrases must be gone.
        thrown.extracting { it.message }.satisfies({ msg ->
            val m = msg as String
            assertThat(m).doesNotContain("GMAIL_APP_PASSWORD")
            assertThat(m).doesNotContain("environment variable")
            assertThat(m).doesNotContainIgnoringCase("export ")
        })
    }

    @Test
    @DisplayName("DTO with no id → IllegalStateException (cannot resolve credential)")
    fun throwsWhenAccountHasNoId() {
        val orphan = EmailAccountDTO().apply {
            email = "no-id@example.com"
            provider = EmailProvider.GENERIC_IMAP
        }

        assertThatThrownBy { invokeGetCredentialUnwrapped(orphan) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("no id")
    }

    private fun saveAccount(email: String): Long {
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

    private fun invokeGetCredential(dto: EmailAccountDTO): String {
        val method = ImapConnectionService::class.java
            .getDeclaredMethod("getCredential", EmailAccountDTO::class.java)
        method.isAccessible = true
        return method.invoke(imapConnectionService, dto) as String
    }

    /**
     * Reflection wraps thrown exceptions in [java.lang.reflect.InvocationTargetException].
     * Unwrap so AssertJ matchers see the real cause type.
     */
    private fun invokeGetCredentialUnwrapped(dto: EmailAccountDTO): String {
        try {
            return invokeGetCredential(dto)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }
}
