package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.EmailAccount
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
import com.oconeco.spring_search_tempo.batch.emailcrawl.EmailCrawlOrchestrator
import com.oconeco.spring_search_tempo.batch.emailcrawl.ParallelizationConfig
import com.oconeco.spring_search_tempo.web.service.EmailSyncStatusView
import com.oconeco.spring_search_tempo.web.service.EmailSyncStatusViewService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.OffsetDateTime

/**
 * Inline-Retry HTMX behavior on the EmailAccount view (issue #70).
 *
 * POST /emailAccounts/{id}/sync MUST:
 *  - When HX-Request is present → return the live `#syncStatusPanel` fragment
 *    (HTTP 200, fragment body) so the inline Retry button can swap directly via
 *    `hx-swap="outerHTML"`.
 *  - When HX-Request is absent → keep the legacy 302 back to the view page, so
 *    the top-of-page "Sync Now" form-submit continues to flash + redirect.
 *
 * It also exercises the in-flight de-dup path (#57): if a sync is already
 * running, the orchestrator throws `JobExecutionAlreadyRunningException`. The
 * HTMX response still returns the panel fragment — refreshed — so the operator
 * sees the actual in-flight state rather than a stale FAILED card.
 *
 * The orchestrator and status-view service are mocked so the test focuses on
 * the controller's response-shape logic without a real Spring Batch run.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@AutoConfigureMockMvc
@DisplayName("EmailAccountController /sync — HTMX retry response shape (issue #70)")
class EmailSyncRetryHtmxIT : BaseIT() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var emailAccountRepository: EmailAccountRepository

    @Autowired
    lateinit var emailAccountService: EmailAccountService

    @MockitoBean
    lateinit var emailCrawlOrchestrator: EmailCrawlOrchestrator

    @MockitoBean
    lateinit var syncStatusViewService: EmailSyncStatusViewService

    @Test
    @DisplayName("HX-Request POST /sync → returns syncStatusPanel fragment with fresh status")
    fun htmxPostReturnsPanelFragment() {
        val id = saveAccountWithPassword("retry-htmx@example.com")

        // Orchestrator dispatches a new in-flight execution.
        val stub = JobExecution(501L).apply { status = BatchStatus.STARTING }
        Mockito.`when`(
            emailCrawlOrchestrator.runQuickSyncForAccount(
                anyLong(), anyBoolean(), anyBoolean(), anyInt(), anyParallelConfig()
            )
        ).thenReturn(stub)

        // After the retry, the panel reflects the new STARTED execution.
        Mockito.`when`(syncStatusViewService.load(id)).thenReturn(
            EmailSyncStatusView(
                executionId = 501L,
                status = "STARTED",
                badgeClass = "bg-primary",
                terminal = false,
                retryable = false,
                startedAt = OffsetDateTime.now(),
                endedAt = null,
                durationSeconds = 1,
                exitMessage = null,
            )
        )

        mockMvc.perform(
            post("/emailAccounts/{id}/sync", id)
                .header("HX-Request", "true")
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"syncStatusPanel\"")))
            .andExpect(content().string(containsString("STARTED")))
            // The newly in-flight panel must NOT offer Retry — would race the de-dup guard.
            .andExpect(content().string(not(containsString("Retry Quick Sync"))))

        verify(emailCrawlOrchestrator, times(1)).runQuickSyncForAccount(
            anyLong(), anyBoolean(), anyBoolean(), anyInt(), anyParallelConfig()
        )
    }

    @Test
    @DisplayName("HX-Request POST /sync while one is in-flight → returns panel with existing run, not a flash redirect")
    fun htmxPostRefusedDuplicateStillReturnsPanel() {
        val id = saveAccountWithPassword("retry-htmx-dup@example.com")

        // `JobExecutionAlreadyRunningException` is a Java checked exception, and Kotlin
        // doesn't propagate throws-clauses through reflection. Mockito refuses both
        // `thenThrow` and `doThrow` for this combination. `thenAnswer { throw … }` is
        // the documented escape hatch — the lambda is invoked at call time, so the
        // exception propagates exactly as if the real method had thrown it.
        Mockito.`when`(
            emailCrawlOrchestrator.runQuickSyncForAccount(
                anyLong(), anyBoolean(), anyBoolean(), anyInt(), anyParallelConfig()
            )
        ).thenAnswer { throw JobExecutionAlreadyRunningException("already running") }

        // Status view reports the existing in-flight execution.
        Mockito.`when`(syncStatusViewService.load(id)).thenReturn(
            EmailSyncStatusView(
                executionId = 502L,
                status = "STARTED",
                badgeClass = "bg-primary",
                terminal = false,
                retryable = false,
                startedAt = OffsetDateTime.now().minusSeconds(5),
                endedAt = null,
                durationSeconds = 5,
                exitMessage = null,
            )
        )

        mockMvc.perform(
            post("/emailAccounts/{id}/sync", id)
                .header("HX-Request", "true")
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"syncStatusPanel\"")))
            .andExpect(content().string(containsString("STARTED")))
    }

    @Test
    @DisplayName("Non-HTMX POST /sync → preserves legacy 302 redirect to view page")
    fun nonHtmxPostStillRedirects() {
        val id = saveAccountWithPassword("retry-no-htmx@example.com")

        val stub = JobExecution(503L).apply { status = BatchStatus.STARTING }
        Mockito.`when`(
            emailCrawlOrchestrator.runQuickSyncForAccount(
                anyLong(), anyBoolean(), anyBoolean(), anyInt(), anyParallelConfig()
            )
        ).thenReturn(stub)

        mockMvc.perform(
            post("/emailAccounts/{id}/sync", id)
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().is3xxRedirection)
    }

    /**
     * Kotlin null-safety guards reject `Mockito.any(Class)` (returns null) when the
     * target parameter is a non-nullable Kotlin type. Register the matcher with Mockito
     * and return a real (unused) instance so the verification still compiles.
     */
    private fun anyParallelConfig(): ParallelizationConfig {
        org.mockito.ArgumentMatchers.any(ParallelizationConfig::class.java)
        return ParallelizationConfig()
    }

    private fun saveAccountWithPassword(email: String): Long {
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
        emailAccountService.setPassword(id, "test-app-pwd")
        return id
    }
}
