package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.EmailAccount
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Pre-flight password guard in [EmailCrawlOrchestrator.runDueAccounts] (issue #55).
 *
 * Before #55, the scheduler would call `runQuickSyncForAccount` for every enabled
 * account at its cron boundary, and credential resolution would fail at step 1
 * for accounts with no DB-encrypted password — producing one ERROR log entry per
 * unconfigured account per scheduler tick. After #55 those accounts are filtered
 * out up front with a single INFO log line and surfaced as
 * [DispatchOutcome.NO_PASSWORD].
 *
 * `JobLauncher` is mocked so we can assert dispatch count precisely without
 * actually running any batch jobs. To keep the test deterministic and fast we
 * also stamp `lastDispatchedAt = now` on the password-set account so its next
 * cron boundary is in the future — the account appears in results as `NOT_DUE`,
 * `runQuickSyncForAccount` isn't invoked, and no IMAP timeout fires.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@TestPropertySource(properties = [
    "app.email.enabled=true",
    // Disable the auto-fire scheduler so it doesn't race with the direct calls
    // this test makes to `runDueAccounts`.
    "app.scheduling.email.multi-account.enabled=false"
])
@DisplayName("EmailCrawlOrchestrator.runDueAccounts pre-flight password guard (issue #55)")
class EmailCrawlOrchestratorNoPasswordIT : BaseIT() {

    @Autowired
    lateinit var orchestrator: EmailCrawlOrchestrator

    @Autowired
    lateinit var emailAccountRepository: EmailAccountRepository

    @Autowired
    lateinit var emailAccountService: EmailAccountService

    @MockitoBean
    lateinit var jobLauncher: JobLauncher

    @Test
    @DisplayName("3 accounts (1 with password, 2 without) → 2 NO_PASSWORD outcomes, no dispatches, no errors")
    fun skipsAccountsWithoutPassword() {
        val withPwdId = saveAccount("with-pwd@example.com", lastDispatchedAt = OffsetDateTime.now())
        val noPwdAId = saveAccount("no-pwd-a@example.com")
        val noPwdBId = saveAccount("no-pwd-b@example.com")

        emailAccountService.setPassword(withPwdId, "the-only-configured-password")

        val results = orchestrator.runDueAccounts(Instant.now())

        // Both unconfigured accounts surface as NO_PASSWORD (one outcome row each).
        val noPasswordIds = results
            .filter { it.outcome == DispatchOutcome.NO_PASSWORD }
            .map { it.accountId }
        assertThat(noPasswordIds).containsExactlyInAnyOrder(noPwdAId, noPwdBId)

        // The password-set account must appear in results (NOT_DUE — see test setup)
        // and must never land in NO_PASSWORD.
        assertThat(results.map { it.accountId }).contains(withPwdId)
        assertThat(noPasswordIds).doesNotContain(withPwdId)

        // No ERROR outcomes — the symptom that motivated #55 was exactly this: one
        // ERROR per unconfigured account per tick.
        assertThat(results.filter { it.outcome == DispatchOutcome.ERROR }).isEmpty()

        // And no batch jobs were dispatched for the unconfigured accounts (the
        // password-set one is NOT_DUE so it doesn't dispatch either).
        verify(jobLauncher, never()).run(any(Job::class.java), any(JobParameters::class.java))
    }

    @Test
    @DisplayName("All accounts missing passwords → all NO_PASSWORD, no dispatches")
    fun allAccountsMissingPasswords() {
        saveAccount("none-a@example.com")
        saveAccount("none-b@example.com")

        val results = orchestrator.runDueAccounts(Instant.now())

        assertThat(results).hasSize(2)
        assertThat(results).allMatch { it.outcome == DispatchOutcome.NO_PASSWORD }
        verify(jobLauncher, never()).run(any(Job::class.java), any(JobParameters::class.java))
    }

    private fun saveAccount(
        email: String,
        lastDispatchedAt: OffsetDateTime? = null
    ): Long {
        val account = EmailAccount().apply {
            this.email = email
            this.uri = "email://$email"
            this.provider = EmailProvider.GENERIC_IMAP
            this.imapHost = "imap.example.invalid"
            this.imapPort = 993
            this.useSsl = true
            this.enabled = true
            this.version = 1L
            this.cronSchedule = "0 0 0 * * *"  // daily at midnight (well-known boundary)
            this.lastDispatchedAt = lastDispatchedAt
        }
        return emailAccountRepository.save(account).id!!
    }
}
