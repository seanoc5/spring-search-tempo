package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.EmailAccount
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Pins down the nested-<form> save-password bug:
 *
 *   The IMAP password card was wrapped in a `<form action="/emailAccounts/{id}/password">`
 *   that lived INSIDE the outer EmailAccount edit form. Nested <form> tags are
 *   invalid HTML — browsers ignore the inner <form> and absorb its inputs into
 *   the outer form. Result: clicking "Save Password" submitted the OUTER form
 *   to /emailAccounts/{id}/edit, which silently ignored the password field
 *   (EmailAccountDTO has no `password` property). Users clicked Save, page
 *   reloaded, "Credential: Not set" persisted — no error, no log line, just
 *   silently lost.
 *
 * The fix replaces the nested <form> with submit buttons that carry
 * `formaction="/emailAccounts/{id}/password"`, which overrides the outer form's
 * action only when those buttons are clicked. The password input was also
 * renamed from `name="password"` to `name="newPassword"` so it doesn't collide
 * with any future `password` field on the outer form / DTO.
 *
 * These tests use Testcontainers + real EncryptionService (with the legit key
 * the test framework provides) to verify the encrypted_password actually lands
 * in the DB end-to-end via the controller.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@AutoConfigureMockMvc
@DisplayName("EmailAccountController setPassword — nested-form save-password fix (newPassword binding)")
class EmailAccountSavePasswordIT : BaseIT() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var emailAccountRepository: EmailAccountRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    @DisplayName("POST with newPassword=... stores ciphertext in encrypted_password and redirects with success flash")
    fun savePasswordPersistsCiphertext() {
        val id = createAccount("save-success@example.com")

        mockMvc.perform(
            post("/emailAccounts/{id}/password", id)
                .param("newPassword", "real-app-password-xyz")
                .with(user(BaseIT.LOGIN).roles("USER"))
                .with(csrf())
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/emailAccounts/$id/edit"))
            .andExpect(flash().attributeExists("message"))

        val stored = jdbcTemplate.queryForObject(
            "SELECT encrypted_password FROM email_account WHERE id = ?",
            String::class.java,
            id
        )
        assertThat(stored)
            .describedAs("encrypted_password must be persisted after a successful Save Password POST")
            .isNotNull
            .isNotBlank
            .startsWith("v1:")
            .doesNotContain("real-app-password-xyz")  // never store plaintext
    }

    @Test
    @DisplayName("POST with legacy `password=` param (no newPassword) → DB stays NULL: controller no longer reads legacy field")
    fun legacyPasswordParamIsRejected() {
        val id = createAccount("legacy-rejected@example.com")

        // Pre-fix: this POST would have succeeded (3xx redirect) and stored the
        // value. Post-fix: the controller requires `newPassword`, so Spring
        // rejects (4xx/5xx depending on this project's error-handling
        // configuration — irrelevant to the user-facing guarantee).
        // The contract that matters: DB stays NULL regardless of status code.
        runCatching {
            mockMvc.perform(
                post("/emailAccounts/{id}/password", id)
                    .param("password", "this-should-be-ignored")
                    .with(user(BaseIT.LOGIN).roles("USER"))
                    .with(csrf())
            )
        }  // status doesn't matter — DB check below is the assertion

        val stored = jdbcTemplate.queryForObject(
            "SELECT encrypted_password FROM email_account WHERE id = ?",
            String::class.java,
            id
        )
        assertThat(stored)
            .describedAs("must NOT have stored anything — the legacy `password` field is no longer bound")
            .isNull()
    }

    private fun createAccount(email: String): Long {
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
}
