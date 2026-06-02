package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.EmailAccount
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.view

/**
 * Regression coverage for issue #53: view + edit templates crashed with
 * `IllegalArgumentException: Invalid boolean value '...'` when a String
 * field (`credentialEnvVar`) was used directly as a SpEL boolean condition.
 *
 * Exercises all four state combinations of
 *   (encryptedPassword set | null) × (credentialEnvVar set | null)
 * to ensure every conditional branch in both templates renders without
 * a TemplateInputException.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@AutoConfigureMockMvc
@DisplayName("EmailAccount view + edit templates render across credential-state combinations (issue #53)")
class EmailAccountViewRenderIT : BaseIT() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var emailAccountRepository: EmailAccountRepository

    @Test
    @DisplayName("GET /emailAccounts/{id} — envVar set, no encrypted password (the PR #50 crash case)")
    fun viewRendersWithEnvVarOnly() {
        val id = saveAccount(
            email = "envvar-only@example.com",
            credentialEnvVar = "GMAIL_APP_PASSWORD",
            encryptedPassword = null
        )

        mockMvc.perform(
            get("/emailAccounts/{id}", id)
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(view().name("emailAccount/view"))
            .andExpect(content().string(containsString("<code>GMAIL_APP_PASSWORD</code>")))
            .andExpect(content().string(not(containsString("Not configured"))))
            .andExpect(content().string(not(containsString("Encrypted password stored"))))
    }

    @Test
    @DisplayName("GET /emailAccounts/{id}/edit — envVar set, no encrypted password (the PR #50 crash case)")
    fun editRendersWithEnvVarOnly() {
        val id = saveAccount(
            email = "envvar-only-edit@example.com",
            credentialEnvVar = "GMAIL_APP_PASSWORD",
            encryptedPassword = null
        )

        mockMvc.perform(
            get("/emailAccounts/{id}/edit", id)
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(view().name("emailAccount/edit"))
            .andExpect(content().string(containsString("Env Var Fallback")))
            .andExpect(content().string(containsString("<code>GMAIL_APP_PASSWORD</code>")))
    }

    @Test
    @DisplayName("GET /emailAccounts/{id} — encrypted password set, no envVar")
    fun viewRendersWithEncryptedPasswordOnly() {
        val id = saveAccount(
            email = "encrypted-only@example.com",
            credentialEnvVar = null,
            encryptedPassword = "v1:fake-ciphertext-for-render-test"
        )

        mockMvc.perform(
            get("/emailAccounts/{id}", id)
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Encrypted password stored")))
            .andExpect(content().string(not(containsString("Not configured"))))
    }

    @Test
    @DisplayName("GET /emailAccounts/{id}/edit — encrypted password set, no envVar")
    fun editRendersWithEncryptedPasswordOnly() {
        val id = saveAccount(
            email = "encrypted-only-edit@example.com",
            credentialEnvVar = null,
            encryptedPassword = "v1:fake-ciphertext-for-render-test"
        )

        mockMvc.perform(
            get("/emailAccounts/{id}/edit", id)
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString("Env Var Fallback"))))
    }

    @Test
    @DisplayName("GET /emailAccounts/{id} — neither encrypted password nor envVar configured")
    fun viewRendersWithNeitherCredential() {
        val id = saveAccount(
            email = "neither@example.com",
            credentialEnvVar = null,
            encryptedPassword = null
        )

        mockMvc.perform(
            get("/emailAccounts/{id}", id)
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Not configured")))
    }

    @Test
    @DisplayName("GET /emailAccounts/{id}/edit — neither encrypted password nor envVar configured")
    fun editRendersWithNeitherCredential() {
        val id = saveAccount(
            email = "neither-edit@example.com",
            credentialEnvVar = null,
            encryptedPassword = null
        )

        mockMvc.perform(
            get("/emailAccounts/{id}/edit", id)
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString("Env Var Fallback"))))
    }

    @Test
    @DisplayName("GET /emailAccounts/{id} — both credentials set (encrypted wins, envVar still surfaced as unused)")
    fun viewRendersWithBothCredentials() {
        val id = saveAccount(
            email = "both@example.com",
            credentialEnvVar = "GMAIL_APP_PASSWORD",
            encryptedPassword = "v1:fake-ciphertext-for-render-test"
        )

        val body = mockMvc.perform(
            get("/emailAccounts/{id}", id)
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        // Encrypted-password badge wins; the standalone "envVar only" branch must not render.
        assertThat(body).contains("Encrypted password stored")
        assertThat(body).doesNotContain("Not configured")
    }

    @Test
    @DisplayName("GET /emailAccounts/{id}/edit — both credentials set, env-var card still shown with 'unused' note")
    fun editRendersWithBothCredentials() {
        val id = saveAccount(
            email = "both-edit@example.com",
            credentialEnvVar = "GMAIL_APP_PASSWORD",
            encryptedPassword = "v1:fake-ciphertext-for-render-test"
        )

        val body = mockMvc.perform(
            get("/emailAccounts/{id}/edit", id)
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertThat(body).contains("Env Var Fallback")
        assertThat(body).contains("<code>GMAIL_APP_PASSWORD</code>")
        assertThat(body).contains("env var is unused")
    }

    private fun saveAccount(
        email: String,
        credentialEnvVar: String?,
        encryptedPassword: String?
    ): Long {
        val account = EmailAccount().apply {
            this.email = email
            this.uri = "email://$email"
            this.provider = EmailProvider.GENERIC_IMAP
            this.imapHost = "imap.example.com"
            this.imapPort = 993
            this.useSsl = true
            this.enabled = true
            this.version = 1L
            this.credentialEnvVar = credentialEnvVar
            this.encryptedPassword = encryptedPassword
        }
        return emailAccountRepository.save(account).id!!
    }
}
