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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Pins the unified-form Thunderbird-style update flow: POST /emailAccounts/{id}/edit
 * now accepts an optional `newPassword` form field. Thunderbird semantics:
 *
 *  - newPassword non-blank → encrypt and replace the stored password
 *  - newPassword blank → leave the stored password unchanged
 *
 * Clearing the stored password is a separate explicit action (POST /password with
 * clear=true via the inline Clear button), not a side-effect of saving the form.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@AutoConfigureMockMvc
@DisplayName("EmailAccountController.update — newPassword field on /edit (Thunderbird semantics)")
class EmailAccountEditSavesPasswordIT : BaseIT() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var emailAccountRepository: EmailAccountRepository
    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @Test
    @DisplayName("POST /edit with newPassword=... replaces the stored encrypted password")
    fun editPostStoresNewPassword() {
        val id = createAccount("edit-password-save@example.com")

        mockMvc.perform(
            post("/emailAccounts/{id}/edit", id)
                // Required DTO fields
                .param("id", id.toString())
                .param("uri", "email://edit-password-save@example.com")
                .param("version", "1")
                .param("email", "edit-password-save@example.com")
                .param("provider", "GENERIC_IMAP")
                .param("imapHost", "imap.example.com")
                .param("imapPort", "993")
                .param("useSsl", "true")
                .param("enabled", "true")
                // The new field
                .param("newPassword", "fresh-app-password-abc")
                .with(user(BaseIT.LOGIN).roles("USER"))
                .with(csrf())
        ).andExpect(status().is3xxRedirection)

        val stored = jdbcTemplate.queryForObject(
            "SELECT encrypted_password FROM email_account WHERE id = ?",
            String::class.java,
            id
        )
        assertThat(stored)
            .describedAs("Save Changes with a non-blank newPassword must persist the encrypted credential")
            .isNotNull
            .startsWith("v1:")
            .doesNotContain("fresh-app-password-abc")
    }

    @Test
    @DisplayName("POST /edit with blank newPassword preserves the existing stored password (no clobber)")
    fun blankNewPasswordPreservesStored() {
        val id = createAccount("edit-keep-password@example.com")
        // Pre-seed a saved password directly through the service layer.
        seedEncryptedPassword(id, "existing-saved-password")
        val before = jdbcTemplate.queryForObject(
            "SELECT encrypted_password FROM email_account WHERE id = ?",
            String::class.java, id
        )
        assertThat(before).describedAs("test precondition: row must have an encrypted password before the edit").isNotNull

        mockMvc.perform(
            post("/emailAccounts/{id}/edit", id)
                .param("id", id.toString())
                .param("uri", "email://edit-keep-password@example.com")
                .param("version", "1")
                .param("email", "edit-keep-password@example.com")
                .param("provider", "GENERIC_IMAP")
                .param("imapHost", "imap.example.com")
                .param("imapPort", "993")
                .param("useSsl", "true")
                .param("enabled", "true")
                .param("newPassword", "")  // explicitly blank, the Thunderbird case
                .with(user(BaseIT.LOGIN).roles("USER"))
                .with(csrf())
        ).andExpect(status().is3xxRedirection)

        val after = jdbcTemplate.queryForObject(
            "SELECT encrypted_password FROM email_account WHERE id = ?",
            String::class.java, id
        )
        assertThat(after)
            .describedAs("blank newPassword must NOT clobber the stored credential")
            .isEqualTo(before)
    }

    private fun seedEncryptedPassword(id: Long, plaintext: String) {
        // Use the same controller endpoint we test elsewhere so the format/version
        // exactly matches what the app would write. This avoids depending on
        // EncryptionService directly in this test.
        mockMvc.perform(
            post("/emailAccounts/{id}/password", id)
                .param("newPassword", plaintext)
                .with(user(BaseIT.LOGIN).roles("USER"))
                .with(csrf())
        ).andExpect(status().is3xxRedirection)
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
