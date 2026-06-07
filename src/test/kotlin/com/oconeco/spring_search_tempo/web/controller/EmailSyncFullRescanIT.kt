package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.EmailAccount
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
import com.oconeco.spring_search_tempo.batch.emailcrawl.EmailCrawlOrchestrator
import com.oconeco.spring_search_tempo.batch.emailcrawl.ParallelizationConfig
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * `fullRescan` form-param plumbing for the collapsed "Sync Now" button (issue #83).
 *
 * The view-page form (and the lower "Sync Tuning" form) post to a single
 * `POST /emailAccounts/{id}/sync` endpoint. The controller must:
 *  - default to incremental sync when neither flag is present;
 *  - flip to forceFullSync=true when the new `fullRescan` checkbox posts true;
 *  - continue to honor the legacy `forceFullSync` param (used by the lower
 *    Sync Tuning form and external scripts) until that form is also migrated.
 *
 * [EmailCrawlOrchestrator] is mocked so we can capture the flag passed through.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@AutoConfigureMockMvc
@DisplayName("EmailAccountController /sync — fullRescan form-param plumbing (issue #83)")
class EmailSyncFullRescanIT : BaseIT() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var emailAccountRepository: EmailAccountRepository

    @Autowired
    lateinit var emailAccountService: EmailAccountService

    @MockitoBean
    lateinit var emailCrawlOrchestrator: EmailCrawlOrchestrator

    @Test
    @DisplayName("POST /sync (no fullRescan param) → forceFullSync=false (default incremental)")
    fun defaultsToIncrementalSync() {
        val id = withPassword(saveAccount("issue83-default@example.com"))
        stubExecution()

        mockMvc.perform(
            post("/emailAccounts/{id}/sync", id)
                .with(user(BaseIT.LOGIN).roles("USER"))
        )

        verify(emailCrawlOrchestrator, times(1)).runQuickSyncForAccount(
            eq(id), eq(false), anyBoolean(), anyInt(), anyParallelConfig()
        )
    }

    @Test
    @DisplayName("POST /sync with fullRescan=true → forceFullSync=true (issue #83 new checkbox)")
    fun fullRescanCheckboxForcesFullSync() {
        val id = withPassword(saveAccount("issue83-checkbox@example.com"))
        stubExecution()

        mockMvc.perform(
            post("/emailAccounts/{id}/sync", id)
                .param("fullRescan", "true")
                .with(user(BaseIT.LOGIN).roles("USER"))
        )

        verify(emailCrawlOrchestrator, times(1)).runQuickSyncForAccount(
            eq(id), eq(true), anyBoolean(), anyInt(), anyParallelConfig()
        )
    }

    @Test
    @DisplayName("POST /sync with legacy forceFullSync=true → still forces full sync (back-compat)")
    fun legacyForceFullSyncStillAccepted() {
        val id = withPassword(saveAccount("issue83-legacy@example.com"))
        stubExecution()

        mockMvc.perform(
            post("/emailAccounts/{id}/sync", id)
                .param("forceFullSync", "true")
                .with(user(BaseIT.LOGIN).roles("USER"))
        )

        verify(emailCrawlOrchestrator, times(1)).runQuickSyncForAccount(
            eq(id), eq(true), anyBoolean(), anyInt(), anyParallelConfig()
        )
    }

    private fun stubExecution() {
        val stub = JobExecution(101L).apply { status = BatchStatus.STARTING }
        Mockito.`when`(
            emailCrawlOrchestrator.runQuickSyncForAccount(
                anyLong(), anyBoolean(), anyBoolean(), anyInt(), anyParallelConfig()
            )
        ).thenReturn(stub)
    }

    private fun anyParallelConfig(): ParallelizationConfig {
        org.mockito.ArgumentMatchers.any(ParallelizationConfig::class.java)
        return ParallelizationConfig()
    }

    private fun withPassword(id: Long): Long {
        emailAccountService.setPassword(id, "test-app-pwd")
        return id
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
