package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.EmailAccount
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
import com.oconeco.spring_search_tempo.batch.emailcrawl.EmailCrawlOrchestrator
import com.oconeco.spring_search_tempo.batch.emailcrawl.ParallelizationConfig
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito
import org.mockito.Mockito.never
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Pre-flight password guard on the per-account "Run Quick Sync" button (issue #55).
 *
 * `POST /emailAccounts/{id}/sync` MUST refuse when the account has no DB-encrypted
 * password — surfacing a flash error and NOT dispatching the batch job. With a
 * password set it dispatches normally.
 *
 * [EmailCrawlOrchestrator] is mocked so the test focuses on the controller's
 * guard logic without spinning up the email pipeline.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@AutoConfigureMockMvc
@DisplayName("EmailAccountController /sync — pre-flight password guard (issue #55)")
class EmailQuickSyncControllerIT : BaseIT() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var emailAccountRepository: EmailAccountRepository

    @Autowired
    lateinit var emailAccountService: EmailAccountService

    @MockitoBean
    lateinit var emailCrawlOrchestrator: EmailCrawlOrchestrator

    @Test
    @DisplayName("POST /sync with no password → flash error, no orchestrator dispatch")
    fun refusesWhenPasswordMissing() {
        val id = saveAccount("guard-no-pwd@example.com")

        mockMvc.perform(
            post("/emailAccounts/{id}/sync", id)
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/emailAccounts/$id"))
            .andExpect(flash().attribute("error", containsString("Set a password before syncing")))

        verify(emailCrawlOrchestrator, never()).runQuickSyncForAccount(
            anyLong(), anyBoolean(), anyBoolean(), anyInt(), anyParallelConfig()
        )
    }

    @Test
    @DisplayName("POST /sync with password set → dispatches to orchestrator")
    fun dispatchesWhenPasswordSet() {
        val id = saveAccount("guard-pwd-set@example.com")
        emailAccountService.setPassword(id, "test-app-pwd")

        val stubExecution = JobExecution(99L).apply { status = BatchStatus.STARTING }
        Mockito.`when`(
            emailCrawlOrchestrator.runQuickSyncForAccount(
                anyLong(), anyBoolean(), anyBoolean(), anyInt(),
                anyParallelConfig()
            )
        ).thenReturn(stubExecution)

        mockMvc.perform(
            post("/emailAccounts/{id}/sync", id)
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(flash().attribute("message", containsString("quick sync started")))

        verify(emailCrawlOrchestrator, times(1)).runQuickSyncForAccount(
            anyLong(), anyBoolean(), anyBoolean(), anyInt(), anyParallelConfig()
        )
    }

    @Test
    @DisplayName("POST /password then POST /sync → no longer refused (extends issue #53 coverage)")
    fun passwordSetUnlocksSync() {
        val id = saveAccount("set-then-sync@example.com")

        // 1. account has no password → /sync refuses
        mockMvc.perform(
            post("/emailAccounts/{id}/sync", id)
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(flash().attribute("error", containsString("Set a password before syncing")))

        // 2. set the password
        mockMvc.perform(
            post("/emailAccounts/{id}/password", id)
                .param("password", "now-i-have-a-pwd")
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().is3xxRedirection)

        assertThat(emailAccountService.hasPassword(id)).isTrue()

        // 3. /sync now dispatches
        val stubExecution = JobExecution(42L).apply { status = BatchStatus.STARTING }
        Mockito.`when`(
            emailCrawlOrchestrator.runQuickSyncForAccount(
                anyLong(), anyBoolean(), anyBoolean(), anyInt(),
                anyParallelConfig()
            )
        ).thenReturn(stubExecution)

        mockMvc.perform(
            post("/emailAccounts/{id}/sync", id)
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(flash().attribute("message", containsString("quick sync started")))

        verify(emailCrawlOrchestrator, times(1)).runQuickSyncForAccount(
            anyLong(), anyBoolean(), anyBoolean(), anyInt(), anyParallelConfig()
        )
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
