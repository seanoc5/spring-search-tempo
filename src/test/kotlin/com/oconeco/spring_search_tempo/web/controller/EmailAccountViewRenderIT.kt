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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.view

/**
 * Render coverage for the EmailAccount view + edit templates after issue #55 removed
 * the env-var credential fallback.
 *
 * The original (issue #53) version of this test asserted the env-var fallback UI
 * panels rendered correctly across `(encryptedPassword × credentialEnvVar)` state
 * combinations. With #55, those panels are gone — the only credential state visible
 * to the user is "encrypted password set" vs "not set", and the env-var label /
 * code blocks must NOT appear anywhere on the page.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@AutoConfigureMockMvc
@DisplayName("EmailAccount view + edit templates render without env-var fallback markers (issue #55)")
class EmailAccountViewRenderIT : BaseIT() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var emailAccountRepository: EmailAccountRepository

    @Test
    @DisplayName("GET /emailAccounts/{id} — no encrypted password → 'Not set' + Edit link, no env-var markers")
    fun viewRendersWithoutPassword() {
        val id = saveAccount(email = "no-pwd-view@example.com", encryptedPassword = null)

        val body = mockMvc.perform(
            get("/emailAccounts/{id}", id)
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(view().name("emailAccount/view"))
            .andReturn().response.contentAsString

        assertThat(body).contains("Edit to set an IMAP password")
        assertNoEnvVarMarkers(body)
    }

    @Test
    @DisplayName("GET /emailAccounts/{id} — encrypted password set → 'Encrypted password stored', no env-var markers")
    fun viewRendersWithEncryptedPassword() {
        val id = saveAccount(
            email = "pwd-set-view@example.com",
            encryptedPassword = "v1:fake-ciphertext-for-render-test"
        )

        val body = mockMvc.perform(
            get("/emailAccounts/{id}", id)
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertThat(body).contains("Encrypted password stored")
        assertNoEnvVarMarkers(body)
    }

    @Test
    @DisplayName("GET /emailAccounts/{id}/edit — no encrypted password → 'Not Set' badge, no env-var card")
    fun editRendersWithoutPassword() {
        val id = saveAccount(email = "no-pwd-edit@example.com", encryptedPassword = null)

        val body = mockMvc.perform(
            get("/emailAccounts/{id}/edit", id)
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(view().name("emailAccount/edit"))
            .andReturn().response.contentAsString

        assertThat(body).contains("Not Set")
        assertNoEnvVarMarkers(body)
    }

    @Test
    @DisplayName("GET /emailAccounts/{id}/edit — encrypted password set → 'Stored (encrypted)', no env-var card")
    fun editRendersWithEncryptedPassword() {
        val id = saveAccount(
            email = "pwd-set-edit@example.com",
            encryptedPassword = "v1:fake-ciphertext-for-render-test"
        )

        val body = mockMvc.perform(
            get("/emailAccounts/{id}/edit", id)
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertThat(body).contains("Stored (encrypted)")
        assertNoEnvVarMarkers(body)
    }

    /**
     * After issue #55 the templates must never surface env-var fallback strings.
     * Centralised so accidental reintroduction in either template trips every
     * test in this class.
     */
    private fun assertNoEnvVarMarkers(body: String) {
        assertThat(body).doesNotContain("Env Var Fallback")
        assertThat(body).doesNotContain("Credential Env Var")
        assertThat(body).doesNotContain("GMAIL_APP_PASSWORD")
        assertThat(body).doesNotContain("credentialEnvVar")
    }

    private fun saveAccount(email: String, encryptedPassword: String?): Long {
        val account = EmailAccount().apply {
            this.email = email
            this.uri = "email://$email"
            this.provider = EmailProvider.GENERIC_IMAP
            this.imapHost = "imap.example.com"
            this.imapPort = 993
            this.useSsl = true
            this.enabled = true
            this.version = 1L
            this.encryptedPassword = encryptedPassword
        }
        return emailAccountRepository.save(account).id!!
    }
}
