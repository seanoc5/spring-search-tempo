package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.EmailAccount
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Controller-level recovery for the missing-encryption-key path (issue #45).
 *
 * Overrides both `app.security.encryption-key` and the legacy `app.onedrive.token-encryption-key`
 * to blank so `EncryptionService.encrypt()` throws `IllegalStateException`. The controller must
 * catch it, surface a flash error naming the property, redirect back to the edit page, and leave
 * `encrypted_password` NULL.
 */
@SpringBootTest(
    classes = [SpringSearchTempoApplication::class],
    properties = [
        "app.security.encryption-key=",
        "app.onedrive.token-encryption-key="
    ]
)
@AutoConfigureMockMvc
@DisplayName("EmailAccountController setPassword — missing encryption key")
class EmailAccountPasswordControllerIT : BaseIT() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var emailAccountRepository: EmailAccountRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    @DisplayName("POST with blank-key config redirects to edit, sets flash error, leaves column NULL")
    fun blankKeyFailsGracefully() {
        val accountId = createAccount("imap-issue45@example.com")

        mockMvc.perform(
            post("/emailAccounts/{id}/password", accountId)
                .param("password", "would-be-encrypted")
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/emailAccounts/$accountId/edit"))
            .andExpect(flash().attributeExists("error"))
            .andExpect(flash().attribute("error", containsString("app.security.encryption-key")))
            .andExpect(flash().attribute("error", containsString("APP_ENCRYPTION_KEY")))

        val raw = jdbcTemplate.queryForObject(
            "SELECT encrypted_password FROM email_account WHERE id = ?",
            String::class.java,
            accountId
        )
        assertThat(raw).isNull()
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
