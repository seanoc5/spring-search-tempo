package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.JobRunService
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.EmailAccount
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobInstance
import org.springframework.batch.core.JobParameter
import org.springframework.batch.core.JobParameters
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.OffsetDateTime

/**
 * Verifies that a successful email sync job clears `EmailAccount.lastError` and
 * `lastErrorAt` (issue #59).
 *
 * The full IMAP pipeline is mocked at the orchestrator layer in [EmailQuickSyncControllerIT];
 * here we exercise the post-job state transition directly by invoking
 * [EmailJobRunTrackingListener.afterJob] with a synthetic `JobExecution` whose status is
 * `COMPLETED`. That is the only path the listener uses to decide success — recreating the
 * full IMAP roundtrip would buy us no extra coverage at much higher cost.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@DisplayName("EmailJobRunTrackingListener — nulls lastError/lastErrorAt on COMPLETED (issue #59)")
class EmailJobRunTrackingListenerClearErrorIT : BaseIT() {

    @Autowired
    lateinit var listener: EmailJobRunTrackingListener

    @Autowired
    lateinit var emailAccountRepository: EmailAccountRepository

    @Autowired
    lateinit var jobRunService: JobRunService

    @Test
    @DisplayName("afterJob(COMPLETED) → DB columns lastError and lastErrorAt are NULL")
    fun clearsErrorOnSuccess() {
        // Pre-seed an account with a fossil error.
        val accountId = saveAccount(
            email = "fossil@example.com",
            lastError = "stale error from months ago",
            lastErrorAt = OffsetDateTime.now().minusDays(30)
        )

        // Create a real JobRun so the listener's `jobRunId > 0` branch runs and
        // updateAccountSyncState (which calls clearError) executes.
        val jobName = "emailQuickSyncJob-$accountId"
        val jobRun = jobRunService.startJobRunWithoutConfig(jobName, "fossil@example.com")

        // Build a synthetic COMPLETED JobExecution carrying the params the listener reads.
        val params = JobParameters(
            mapOf(
                EmailJobRunTrackingListener.ACCOUNT_ID_KEY to JobParameter(accountId.toString(), String::class.java),
                EmailJobRunTrackingListener.ACCOUNT_EMAIL_KEY to JobParameter("fossil@example.com", String::class.java)
            )
        )
        val instance = JobInstance(1L, jobName)
        val jobExecution = JobExecution(instance, params).apply {
            status = BatchStatus.COMPLETED
            executionContext.putLong(EmailJobRunTrackingListener.JOB_RUN_ID_KEY, jobRun.id!!)
        }

        listener.afterJob(jobExecution)

        val refreshed = emailAccountRepository.findById(accountId).orElseThrow()
        assertThat(refreshed.lastError).isNull()
        assertThat(refreshed.lastErrorAt).isNull()
    }

    @Test
    @DisplayName("afterJob(FAILED) → lastError stays populated, recordError captures the failure")
    fun preservesErrorOnFailure() {
        val accountId = saveAccount(
            email = "fail@example.com",
            lastError = null,
            lastErrorAt = null
        )

        val jobName = "emailQuickSyncJob-$accountId"
        val jobRun = jobRunService.startJobRunWithoutConfig(jobName, "fail@example.com")

        val params = JobParameters(
            mapOf(
                EmailJobRunTrackingListener.ACCOUNT_ID_KEY to JobParameter(accountId.toString(), String::class.java),
                EmailJobRunTrackingListener.ACCOUNT_EMAIL_KEY to JobParameter("fail@example.com", String::class.java)
            )
        )
        val instance = JobInstance(2L, jobName)
        val jobExecution = JobExecution(instance, params).apply {
            status = BatchStatus.FAILED
            addFailureException(IllegalStateException("IMAP auth failed"))
            executionContext.putLong(EmailJobRunTrackingListener.JOB_RUN_ID_KEY, jobRun.id!!)
        }

        listener.afterJob(jobExecution)

        val refreshed = emailAccountRepository.findById(accountId).orElseThrow()
        assertThat(refreshed.lastError).contains("IMAP auth failed")
        assertThat(refreshed.lastErrorAt).isNotNull()
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
