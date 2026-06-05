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
import java.time.OffsetDateTime

/**
 * Rendering coverage for the "Last Error" card on /emailAccounts/{id}/edit (issue #59).
 *
 * Acceptance:
 * - 2-day-old error → card visible, "(2 days ago)" age phrase rendered.
 * - 8-day-old error → card hidden entirely (border-danger marker absent), letting the
 *   DB row remain available for debugging while keeping the user from seeing fossil
 *   errors months after the underlying code path was fixed.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@AutoConfigureMockMvc
@DisplayName("EmailAccount edit page — lastError age display + 7d cutoff (issue #59)")
class EmailAccountLastErrorDisplayIT : BaseIT() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var emailAccountRepository: EmailAccountRepository

    @Test
    @DisplayName("GET /edit — 2-day-old error renders the card with '(2 days ago)' phrase")
    fun freshErrorRendersCard() {
        val id = saveAccount(
            email = "fresh-error@example.com",
            lastError = "IMAP LOGIN failed: invalid credentials",
            lastErrorAt = OffsetDateTime.now().minusDays(2)
        )

        val body = mockMvc.perform(
            get("/emailAccounts/{id}/edit", id)
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertThat(body).contains("border-danger")
        assertThat(body).contains("Last Error")
        assertThat(body).contains("(2 days ago)")
        assertThat(body).contains("IMAP LOGIN failed")
    }

    @Test
    @DisplayName("GET /edit — 8-day-old error is suppressed; no border-danger card rendered")
    fun staleErrorHidden() {
        val staleMessage = "IMAP LOGIN failed: env-var no longer used"
        val id = saveAccount(
            email = "stale-error@example.com",
            lastError = staleMessage,
            lastErrorAt = OffsetDateTime.now().minusDays(8)
        )

        val body = mockMvc.perform(
            get("/emailAccounts/{id}/edit", id)
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        // The error card is gone — both the header and the message must be absent.
        assertThat(body).doesNotContain("Last Error")
        assertThat(body).doesNotContain(staleMessage)
        // And the DB row is untouched (verified via the repository, not the UI).
        val account = emailAccountRepository.findById(id).orElseThrow()
        assertThat(account.lastError).isEqualTo(staleMessage)
        assertThat(account.lastErrorAt).isNotNull()
    }

    @Test
    @DisplayName("GET /edit — no error at all → no border-danger card rendered")
    fun noErrorRendersNoCard() {
        val id = saveAccount(
            email = "no-error@example.com",
            lastError = null,
            lastErrorAt = null
        )

        val body = mockMvc.perform(
            get("/emailAccounts/{id}/edit", id)
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertThat(body).doesNotContain("Last Error")
    }

    private fun saveAccount(
        email: String,
        lastError: String?,
        lastErrorAt: OffsetDateTime?
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
            this.lastError = lastError
            this.lastErrorAt = lastErrorAt
        }
        return emailAccountRepository.save(account).id!!
    }
}
