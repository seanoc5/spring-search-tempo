package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.EmailAccount
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
import com.oconeco.spring_search_tempo.web.service.EmailSyncStatusView
import com.oconeco.spring_search_tempo.web.service.EmailSyncStatusViewService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.OffsetDateTime

/**
 * GET /emailAccounts/{id}/syncStatus — HTMX fragment that powers the live
 * sync-status panel on the EmailAccount view page (issue #68).
 *
 * Verifies the panel:
 *   1. renders a "no execution recorded yet" placeholder when no quick-sync
 *      job has ever run for the account;
 *   2. re-arms `hx-trigger="every 2s"` while the latest execution is
 *      non-terminal (STARTING / STARTED);
 *   3. drops the polling trigger when the execution is terminal (COMPLETED /
 *      FAILED / STOPPED / ABANDONED) and surfaces the truncated exit message
 *      on FAILED.
 *
 * [EmailSyncStatusViewService] is mocked so we can drive the view-model
 * branches deterministically without spinning up Spring Batch.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@AutoConfigureMockMvc
@DisplayName("EmailAccountController /syncStatus — live status fragment (issue #68)")
class EmailSyncStatusFragmentIT : BaseIT() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var emailAccountRepository: EmailAccountRepository

    @MockitoBean
    lateinit var syncStatusViewService: EmailSyncStatusViewService

    @Test
    @DisplayName("no recorded execution → 'No quick-sync execution recorded yet' placeholder")
    fun noExecutionRecorded() {
        val id = saveAccount("never-run@example.com")
        `when`(syncStatusViewService.load(id)).thenReturn(null)

        mockMvc.perform(
            get("/emailAccounts/{id}/syncStatus", id)
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"syncStatusPanel\"")))
            .andExpect(content().string(containsString("No quick-sync execution recorded yet")))
    }

    @Test
    @DisplayName("STARTED execution → panel re-arms hx-trigger=every 2s")
    fun nonTerminalRearmsPolling() {
        val id = saveAccount("running@example.com")
        `when`(syncStatusViewService.load(id)).thenReturn(
            EmailSyncStatusView(
                executionId = 297L,
                status = "STARTED",
                badgeClass = "bg-primary",
                terminal = false,
                startedAt = OffsetDateTime.now(),
                endedAt = null,
                durationSeconds = 5,
                exitMessage = null,
            )
        )

        mockMvc.perform(
            get("/emailAccounts/{id}/syncStatus", id)
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"syncStatusPanel\"")))
            .andExpect(content().string(containsString("hx-trigger=\"every 2s\"")))
            .andExpect(content().string(containsString("STARTED")))
            .andExpect(content().string(containsString("live — polling every 2s")))
    }

    @Test
    @DisplayName("FAILED terminal execution → no polling, exit message rendered")
    fun terminalFailureDropsPolling() {
        val id = saveAccount("failed@example.com")
        `when`(syncStatusViewService.load(id)).thenReturn(
            EmailSyncStatusView(
                executionId = 298L,
                status = "FAILED",
                badgeClass = "bg-danger",
                terminal = true,
                startedAt = OffsetDateTime.now().minusSeconds(12),
                endedAt = OffsetDateTime.now(),
                durationSeconds = 12,
                exitMessage = "[UNAVAILABLE] Temporary authentication failure",
            )
        )

        mockMvc.perform(
            get("/emailAccounts/{id}/syncStatus", id)
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"syncStatusPanel\"")))
            .andExpect(content().string(not(containsString("hx-trigger=\"every 2s\""))))
            .andExpect(content().string(containsString("FAILED")))
            .andExpect(content().string(containsString("Temporary authentication failure")))
    }

    @Test
    @DisplayName("COMPLETED terminal execution → no polling, green border")
    fun terminalSuccessDropsPolling() {
        val id = saveAccount("ok@example.com")
        `when`(syncStatusViewService.load(id)).thenReturn(
            EmailSyncStatusView(
                executionId = 299L,
                status = "COMPLETED",
                badgeClass = "bg-success",
                terminal = true,
                startedAt = OffsetDateTime.now().minusSeconds(30),
                endedAt = OffsetDateTime.now(),
                durationSeconds = 30,
                exitMessage = null,
            )
        )

        mockMvc.perform(
            get("/emailAccounts/{id}/syncStatus", id)
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"syncStatusPanel\"")))
            .andExpect(content().string(not(containsString("hx-trigger=\"every 2s\""))))
            .andExpect(content().string(containsString("border-success")))
            .andExpect(content().string(containsString("COMPLETED")))
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
}
