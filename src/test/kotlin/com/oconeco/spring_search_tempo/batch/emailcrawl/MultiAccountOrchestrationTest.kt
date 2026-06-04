package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.EmailFolderService
import com.oconeco.spring_search_tempo.base.config.EmailConfiguration
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import com.oconeco.spring_search_tempo.base.model.EmailAccountDTO
import com.oconeco.spring_search_tempo.base.service.EmailFolderSyncService
import com.oconeco.spring_search_tempo.base.service.ImapConnectionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.anyString
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobInstance
import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.batch.core.launch.JobLauncher
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Unit tests for [EmailCrawlOrchestrator.runDueAccounts] — the per-account
 * cron dispatch contract introduced in issue #2 / ADR-004.
 *
 * Uses mocks rather than a SpringBootTest to keep iteration fast and to
 * isolate the dispatch logic from Spring Batch / IMAP / DB concerns. A
 * companion integration test (if needed) can wire the whole stack via
 * GreenMail; the contract this test pins is the one the brief calls out:
 *
 * 1. Each enabled account whose cron boundary has elapsed dispatches a
 *    `EmailQuickSyncJob` parameterized by `accountId`.
 * 2. A failing account does not block sibling accounts.
 */
class MultiAccountOrchestrationTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    // Long after EPOCH so any reasonable cron's "next from EPOCH" is < now.
    private val now: Instant = Instant.parse("2026-06-01T00:30:00Z")

    private lateinit var emailConfiguration: EmailConfiguration
    private lateinit var emailAccountService: EmailAccountService
    private lateinit var emailFolderService: EmailFolderService
    private lateinit var emailFolderSyncService: EmailFolderSyncService
    private lateinit var emailQuickSyncJobBuilder: EmailQuickSyncJobBuilder
    private lateinit var jobLauncher: JobLauncher
    private lateinit var imapConnectionService: ImapConnectionService
    private lateinit var jobExplorer: JobExplorer

    private lateinit var orchestrator: EmailCrawlOrchestrator

    @BeforeEach
    fun setUp() {
        // Use a real EmailConfiguration: Mockito can't intercept Kotlin
        // data-class property getters in a way that reliably returns the
        // stubbed value to the orchestrator.
        emailConfiguration = EmailConfiguration(
            enabled = true,
            quickSyncFolders = listOf("INBOX")
        )
        emailAccountService = mock(EmailAccountService::class.java)
        emailFolderService = mock(EmailFolderService::class.java)
        emailFolderSyncService = mock(EmailFolderSyncService::class.java)
        emailQuickSyncJobBuilder = mock(EmailQuickSyncJobBuilder::class.java)
        jobLauncher = mock(JobLauncher::class.java)
        // getExpectedMessageCount() already catches all exceptions and
        // returns 0L, so the default mock (returns 0L for Long via Mockito's
        // primitive defaults) is fine here — no stubbing needed.
        imapConnectionService = mock(ImapConnectionService::class.java)
        jobExplorer = mock(JobExplorer::class.java)
        // Default: no in-flight quick sync for any account. Tests that exercise
        // the issue #57 in-flight skip path stub this explicitly.
        `when`(jobExplorer.findRunningJobExecutions(anyString())).thenReturn(emptySet())

        orchestrator = EmailCrawlOrchestrator(
            emailConfiguration = emailConfiguration,
            emailAccountService = emailAccountService,
            emailFolderService = emailFolderService,
            emailFolderSyncService = emailFolderSyncService,
            emailQuickSyncJobBuilder = emailQuickSyncJobBuilder,
            jobLauncher = jobLauncher,
            imapConnectionService = imapConnectionService,
            jobExplorer = jobExplorer
        )
    }

    @Test
    fun `each due account dispatches an independent job parameterized by accountId`() {
        val gmail = account(id = 1L, email = "gmail@example.com",
            cron = "0 0 * * * *",       // every hour on the hour
            lastDispatched = null)
        val fastmail = account(id = 2L, email = "fm@example.com",
            cron = "0 */15 * * * *",    // every 15 minutes
            lastDispatched = null)

        `when`(emailAccountService.findEnabled()).thenReturn(listOf(gmail, fastmail))
        stubAccountGet(gmail)
        stubAccountGet(fastmail)
        stubJobBuild()
        stubJobLaunchSuccess()

        val results = orchestrator.runDueAccounts(now)

        // Both accounts had no prior dispatch and `now` is well past EPOCH,
        // so both should fire.
        assertThat(results.map { it.outcome })
            .containsExactly(DispatchOutcome.DISPATCHED, DispatchOutcome.DISPATCHED)
        assertThat(results.map { it.accountId }).containsExactly(1L, 2L)

        // Each dispatch must carry its own accountId through JobParameters.
        val paramsCaptor = ArgumentCaptor.forClass(JobParameters::class.java)
        verify(jobLauncher, org.mockito.Mockito.times(2))
            .run(any(Job::class.java), paramsCaptor.capture())

        val dispatchedAccountIds = paramsCaptor.allValues
            .mapNotNull { it.getString("accountId")?.toLong() }
            .toSet()
        assertThat(dispatchedAccountIds).containsExactlyInAnyOrder(1L, 2L)

        // lastDispatchedAt must be recorded for each account so the next
        // tick doesn't re-dispatch within the same cron boundary.
        verify(emailAccountService).recordDispatched(eq(1L), anyKotlin<OffsetDateTime>())
        verify(emailAccountService).recordDispatched(eq(2L), anyKotlin<OffsetDateTime>())
    }

    @Test
    fun `failing account does not block sibling accounts`() {
        val failing = account(id = 10L, email = "broken@example.com",
            cron = "0 0 * * * *", lastDispatched = null)
        val healthy = account(id = 11L, email = "ok@example.com",
            cron = "0 0 * * * *", lastDispatched = null)

        `when`(emailAccountService.findEnabled()).thenReturn(listOf(failing, healthy))
        stubAccountGet(failing)
        stubAccountGet(healthy)

        // Build succeeds for both — the failure surface we model is at
        // launch time (a stand-in for IMAP-down, batch-repo conflict, etc).
        stubJobBuild()

        // jobLauncher throws for the failing account, succeeds for the healthy one.
        `when`(jobLauncher.run(any(Job::class.java), any(JobParameters::class.java)))
            .thenAnswer { invocation ->
                val params = invocation.getArgument<JobParameters>(1)
                val accountId = params.getString("accountId")?.toLong()
                if (accountId == 10L) {
                    throw RuntimeException("simulated dispatch failure")
                }
                jobExecution()
            }

        val results = orchestrator.runDueAccounts(now)

        assertThat(results).hasSize(2)
        val byId = results.associateBy { it.accountId }
        assertThat(byId[10L]?.outcome).isEqualTo(DispatchOutcome.ERROR)
        assertThat(byId[11L]?.outcome).isEqualTo(DispatchOutcome.DISPATCHED)

        // Sibling must be marked as dispatched even though the prior
        // account threw — failure isolation is the whole point.
        verify(emailAccountService).recordDispatched(eq(11L), anyKotlin<OffsetDateTime>())
        // Failing account must NOT be marked as dispatched — next tick retries it.
        verify(emailAccountService, never()).recordDispatched(eq(10L), anyKotlin<OffsetDateTime>())
        // Failure should be recorded on the failing account for surfacing in the UI.
        verify(emailAccountService).recordError(eq(10L), anyString())
    }

    @Test
    fun `account whose cron boundary has not elapsed yet is not dispatched`() {
        // Already dispatched at 00:29:55Z; next minute boundary is 00:30:00Z,
        // exactly equal to `now`. CronExpression.next() returns the FIRST
        // instant strictly after the anchor, so a per-minute cron from
        // 00:29:55 yields 00:30:00 — which `now` matches → DISPATCHED.
        // To prove the "not due" path, use a cron that won't fire for a while.
        val notDueYet = account(id = 20L, email = "later@example.com",
            cron = "0 0 0 * * *",                          // daily at 00:00
            lastDispatched = OffsetDateTime.ofInstant(now, zone))  // just dispatched

        `when`(emailAccountService.findEnabled()).thenReturn(listOf(notDueYet))
        `when`(emailAccountService.hasPassword(eq(20L))).thenReturn(true)

        val results = orchestrator.runDueAccounts(now)

        assertThat(results).hasSize(1)
        assertThat(results[0].outcome).isEqualTo(DispatchOutcome.NOT_DUE)
        verify(jobLauncher, never()).run(any(Job::class.java), any(JobParameters::class.java))
        verify(emailAccountService, never()).recordDispatched(anyLong(), anyKotlin<OffsetDateTime>())
    }

    @Test
    fun `invalid cron string is recorded as an error and does not throw`() {
        val bad = account(id = 30L, email = "bad@example.com",
            cron = "not a real cron", lastDispatched = null)

        `when`(emailAccountService.findEnabled()).thenReturn(listOf(bad))
        `when`(emailAccountService.hasPassword(eq(30L))).thenReturn(true)

        val results = orchestrator.runDueAccounts(now)

        assertThat(results).hasSize(1)
        assertThat(results[0].outcome).isEqualTo(DispatchOutcome.INVALID_CRON)
        verify(emailAccountService).recordError(eq(30L), anyString())
        verify(jobLauncher, never()).run(any(Job::class.java), any(JobParameters::class.java))
    }

    @Test
    fun `due account is skipped when a quick sync for that account is already in flight (issue #57)`() {
        val dueButRunning = account(
            id = 42L, email = "busy@example.com",
            cron = "0 0 * * * *",  // due since EPOCH
            lastDispatched = null
        )
        val sibling = account(
            id = 43L, email = "free@example.com",
            cron = "0 0 * * * *",
            lastDispatched = null
        )

        `when`(emailAccountService.findEnabled()).thenReturn(listOf(dueButRunning, sibling))
        stubAccountGet(dueButRunning)
        stubAccountGet(sibling)
        stubJobBuild()
        stubJobLaunchSuccess()

        // Pre-existing in-flight execution for emailQuickSync_42 — simulates the
        // race in the issue (manual click at 16:43:08, scheduler tick at 16:43:39).
        val runningInstance = JobInstance(99L, "emailQuickSync_42")
        val runningExec = JobExecution(runningInstance, JobParameters()).apply { id = 292L }
        `when`(jobExplorer.findRunningJobExecutions(eq("emailQuickSync_42")))
            .thenReturn(setOf(runningExec))
        // Sibling account's job name remains "no running executions" — default stub
        // returns emptySet() so we don't need to restub.

        val results = orchestrator.runDueAccounts(now)

        // Busy account should be skipped, sibling should dispatch normally.
        val byId = results.associateBy { it.accountId }
        assertThat(byId[42L]?.outcome).isEqualTo(DispatchOutcome.SKIPPED_IN_PROGRESS)
        assertThat(byId[42L]?.detail).contains("executionId=292")
        assertThat(byId[43L]?.outcome).isEqualTo(DispatchOutcome.DISPATCHED)

        // Crucially: only the sibling's job was launched — no second emailQuickSync_42.
        val paramsCaptor = ArgumentCaptor.forClass(JobParameters::class.java)
        verify(jobLauncher, org.mockito.Mockito.times(1))
            .run(any(Job::class.java), paramsCaptor.capture())
        val launchedAccountIds = paramsCaptor.allValues
            .mapNotNull { it.getString("accountId")?.toLong() }
            .toSet()
        assertThat(launchedAccountIds).containsExactly(43L)

        // The busy account's lastDispatchedAt must NOT be touched — skip is not
        // a dispatch, so the next tick should re-evaluate (and skip again if
        // still running, dispatch once it finishes).
        verify(emailAccountService, never()).recordDispatched(eq(42L), anyKotlin<OffsetDateTime>())
    }

    @Test
    fun `runQuickSyncForAccount throws JobExecutionAlreadyRunningException when in-flight (issue #57)`() {
        val acct = account(id = 77L, email = "manual@example.com",
            cron = "0 0 * * * *", lastDispatched = null)
        stubAccountGet(acct)

        val runningInstance = JobInstance(7L, "emailQuickSync_77")
        val runningExec = JobExecution(runningInstance, JobParameters()).apply { id = 700L }
        `when`(jobExplorer.findRunningJobExecutions(eq("emailQuickSync_77")))
            .thenReturn(setOf(runningExec))

        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.batch.core.repository.JobExecutionAlreadyRunningException::class.java
        ) {
            orchestrator.runQuickSyncForAccount(accountId = 77L)
        }

        verify(jobLauncher, never()).run(any(Job::class.java), any(JobParameters::class.java))
    }

    @Test
    fun `runDueAccounts is a no-op when email crawling is disabled`() {
        // Rebuild orchestrator with a disabled configuration — emailConfiguration
        // is a real (non-mock) instance so we have to re-construct rather than stub.
        emailConfiguration = EmailConfiguration(enabled = false, quickSyncFolders = listOf("INBOX"))
        orchestrator = EmailCrawlOrchestrator(
            emailConfiguration = emailConfiguration,
            emailAccountService = emailAccountService,
            emailFolderService = emailFolderService,
            emailFolderSyncService = emailFolderSyncService,
            emailQuickSyncJobBuilder = emailQuickSyncJobBuilder,
            jobLauncher = jobLauncher,
            imapConnectionService = imapConnectionService,
            jobExplorer = jobExplorer
        )

        val results = orchestrator.runDueAccounts(now)

        assertThat(results).isEmpty()
        verify(emailAccountService, never()).findEnabled()
        verify(jobLauncher, never()).run(any(Job::class.java), any(JobParameters::class.java))
    }

    // ---- helpers ----

    private fun account(
        id: Long,
        email: String,
        cron: String,
        lastDispatched: OffsetDateTime?
    ): EmailAccountDTO = EmailAccountDTO().apply {
        this.id = id
        this.email = email
        this.uri = "email://$email"
        this.provider = EmailProvider.GENERIC_IMAP
        this.enabled = true
        this.version = 1L
        this.cronSchedule = cron
        this.lastDispatchedAt = lastDispatched
    }

    private fun stubAccountGet(dto: EmailAccountDTO) {
        `when`(emailAccountService.get(eq(dto.id!!))).thenReturn(dto)
        // Default tests to "password is set" so the issue #55 pre-flight skip
        // doesn't take effect — tests that exercise the NO_PASSWORD path do so
        // explicitly via [EmailCrawlOrchestratorNoPasswordIT].
        `when`(emailAccountService.hasPassword(eq(dto.id!!))).thenReturn(true)
    }

    private fun stubJobBuild() {
        val job = mock(Job::class.java)
        // doReturn().when() pattern avoids the call-site Kotlin null check
        // that fires when any() returns null into a non-null Kotlin parameter.
        org.mockito.Mockito.doReturn(job).`when`(emailQuickSyncJobBuilder).buildJob(
            anyKotlin(),
            anyKotlin(),
            org.mockito.Mockito.anyBoolean(),
            org.mockito.Mockito.anyBoolean(),
            org.mockito.Mockito.anyInt(),
            anyKotlin()
        )
    }

    /**
     * Kotlin-safe replacement for [org.mockito.ArgumentMatchers.any].
     * Mockito's `any()` returns null; assigning null into a non-null Kotlin
     * parameter trips the compiler-inserted null check at the call site.
     * This returns a real (no-arg-constructed) instance so Kotlin is happy
     * while Mockito still records the matcher.
     *
     * Calling [org.mockito.Mockito.mock] inside this helper would itself
     * trigger an `UnfinishedStubbingException` (a mock created mid-stubbing
     * looks like an attempt to start a new stub), so the fallback uses
     * Kotlin no-arg data-class constructors instead.
     */
    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T : Any> anyKotlin(): T {
        org.mockito.ArgumentMatchers.any(T::class.java)
        return when (T::class) {
            EmailAccountDTO::class -> EmailAccountDTO() as T
            ParallelizationConfig::class -> ParallelizationConfig() as T
            List::class -> emptyList<Any>() as T
            OffsetDateTime::class -> OffsetDateTime.now() as T
            Job::class -> SENTINEL_JOB as T
            JobParameters::class -> JobParameters() as T
            String::class -> "" as T
            else -> error("anyKotlin(): unsupported type ${T::class}; add a sentinel above.")
        }
    }

    companion object {
        // Pre-built once outside any stubbing scope so we don't trip
        // UnfinishedStubbingException by creating a mock mid-stub.
        private val SENTINEL_JOB: Job = mock(Job::class.java)
    }

    private fun stubJobLaunchSuccess() {
        `when`(jobLauncher.run(any(Job::class.java), any(JobParameters::class.java)))
            .thenAnswer { jobExecution() }
    }

    private fun jobExecution(): JobExecution {
        val instance = JobInstance(System.nanoTime(), "emailQuickSyncJob")
        return JobExecution(instance, JobParameters()).apply {
            id = System.nanoTime()
        }
    }

}
