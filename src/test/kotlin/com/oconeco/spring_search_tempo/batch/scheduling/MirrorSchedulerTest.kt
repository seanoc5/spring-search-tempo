package com.oconeco.spring_search_tempo.batch.scheduling

import com.oconeco.spring_search_tempo.base.MirrorConfigService
import com.oconeco.spring_search_tempo.base.model.MirrorConfigDTO
import com.oconeco.spring_search_tempo.batch.mirror.MirrorJobLauncher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobInstance
import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Unit tests for [MirrorScheduler.runDueMirrors] — the per-config cron
 * dispatch contract introduced in issue #38.
 *
 * Mirrors the structure of `MultiAccountOrchestrationTest` (the email
 * equivalent that this scheduler was ported from). Uses mocks rather
 * than a full SpringBootTest to keep iteration fast and to isolate the
 * dispatch logic from Spring Batch / IMAP / DB concerns.
 */
class MirrorSchedulerTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val now: Instant = Instant.parse("2026-06-01T00:30:00Z")

    private lateinit var mirrorConfigService: MirrorConfigService
    private lateinit var mirrorJobLauncher: MirrorJobLauncher
    private lateinit var jobExplorer: JobExplorer
    private lateinit var properties: MirrorSchedulerProperties
    private lateinit var scheduler: MirrorScheduler

    @BeforeEach
    fun setUp() {
        mirrorConfigService = mock(MirrorConfigService::class.java)
        mirrorJobLauncher = mock(MirrorJobLauncher::class.java)
        jobExplorer = mock(JobExplorer::class.java)
        // Default: no running mirrorJobs. Tests that need a running execution override this.
        `when`(jobExplorer.findRunningJobExecutions("mirrorJob")).thenReturn(emptySet())
        properties = MirrorSchedulerProperties(enabled = true, tickMillis = 60_000)

        scheduler = MirrorScheduler(
            mirrorConfigService = mirrorConfigService,
            mirrorJobLauncher = mirrorJobLauncher,
            jobExplorer = jobExplorer,
            properties = properties,
            clock = Clock.fixed(now, zone)
        )
    }

    @Test
    fun `mirror with every-second cron dispatches exactly once per tick and advances lastDispatched`() {
        val mirror = mirror(id = 1L, name = "src-to-dst", cron = "* * * * * *", lastDispatched = null)
        `when`(mirrorConfigService.findEnabled()).thenReturn(listOf(mirror))
        `when`(mirrorJobLauncher.launch(eq(1L), anyString())).thenReturn(jobExecution())

        val results = scheduler.runDueMirrors(now)

        // Exactly one dispatch per tick (acceptance #6).
        assertThat(results.map { it.outcome })
            .containsExactly(MirrorDispatchOutcome.DISPATCHED)
        verify(mirrorJobLauncher).launch(eq(1L), anyString())

        // lastDispatchedAt must advance from null (acceptance #6): recordDispatched
        // was called exactly once for this config with a non-null timestamp.
        verify(mirrorConfigService).recordDispatched(eq(1L), anyOffsetDateTime())
    }

    @Test
    fun `mirror with no cronSchedule is skipped (NO_SCHEDULE) and never dispatched`() {
        val mirror = mirror(id = 2L, name = "manual-only", cron = null, lastDispatched = null)
        `when`(mirrorConfigService.findEnabled()).thenReturn(listOf(mirror))

        val results = scheduler.runDueMirrors(now)

        assertThat(results.map { it.outcome })
            .containsExactly(MirrorDispatchOutcome.NO_SCHEDULE)
        verify(mirrorJobLauncher, never()).launch(anyLong(), anyString())
        verify(mirrorConfigService, never()).recordDispatched(anyLong(), anyOffsetDateTime())
    }

    @Test
    fun `mirror whose next cron boundary is still in the future is NOT_DUE`() {
        // Daily at 00:00, last dispatched at `now` — next boundary is tomorrow.
        val mirror = mirror(
            id = 3L,
            name = "daily",
            cron = "0 0 0 * * *",
            lastDispatched = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
        )
        `when`(mirrorConfigService.findEnabled()).thenReturn(listOf(mirror))

        val results = scheduler.runDueMirrors(now)

        assertThat(results.map { it.outcome })
            .containsExactly(MirrorDispatchOutcome.NOT_DUE)
        verify(mirrorJobLauncher, never()).launch(anyLong(), anyString())
    }

    @Test
    fun `invalid cron is reported as INVALID_CRON and does not throw`() {
        val mirror = mirror(id = 4L, name = "bad", cron = "not a cron", lastDispatched = null)
        `when`(mirrorConfigService.findEnabled()).thenReturn(listOf(mirror))

        val results = scheduler.runDueMirrors(now)

        assertThat(results.map { it.outcome })
            .containsExactly(MirrorDispatchOutcome.INVALID_CRON)
        verify(mirrorJobLauncher, never()).launch(anyLong(), anyString())
    }

    @Test
    fun `another mirror running against same destination account is skipped`() {
        // Mirror with destAccountId=99 is due, but another running mirrorJob
        // already targets destAccountId=99 (acceptance #5).
        val due = mirror(
            id = 5L,
            name = "due-but-blocked",
            cron = "* * * * * *",
            lastDispatched = null,
            destAccountId = 99L
        )
        `when`(mirrorConfigService.findEnabled()).thenReturn(listOf(due))

        // Simulate a running mirrorJob for some OTHER config that maps to destAccountId=99.
        val runningExec = JobExecution(
            JobInstance(42L, "mirrorJob"),
            JobParametersBuilder()
                .addLong("mirrorConfigId", 999L)
                .toJobParameters()
        )
        `when`(jobExplorer.findRunningJobExecutions("mirrorJob")).thenReturn(setOf(runningExec))
        `when`(mirrorConfigService.findByIdOrNull(999L)).thenReturn(
            MirrorConfigDTO().apply { id = 999L; destAccountId = 99L }
        )

        val results = scheduler.runDueMirrors(now)

        assertThat(results.map { it.outcome })
            .containsExactly(MirrorDispatchOutcome.SKIPPED_IN_PROGRESS)
        verify(mirrorJobLauncher, never()).launch(eq(5L), anyString())
        verify(mirrorConfigService, never()).recordDispatched(eq(5L), anyOffsetDateTime())
    }

    @Test
    fun `launcher's already-running exception is swallowed as SKIPPED_IN_PROGRESS`() {
        val mirror = mirror(id = 6L, name = "race", cron = "* * * * * *", lastDispatched = null)
        `when`(mirrorConfigService.findEnabled()).thenReturn(listOf(mirror))
        // doAnswer bypasses Mockito's checked-exception validation —
        // `launch` doesn't declare `throws JobExecutionAlreadyRunningException`
        // in its Kotlin signature, but the body throws it directly.
        doAnswer { throw JobExecutionAlreadyRunningException("already running") }
            .`when`(mirrorJobLauncher).launch(eq(6L), anyString())

        val results = scheduler.runDueMirrors(now)

        assertThat(results.map { it.outcome })
            .containsExactly(MirrorDispatchOutcome.SKIPPED_IN_PROGRESS)
        verify(mirrorConfigService, never()).recordDispatched(eq(6L), anyOffsetDateTime())
    }

    @Test
    fun `failing dispatch does not block sibling mirrors`() {
        val failing = mirror(id = 7L, name = "broken", cron = "* * * * * *",
            lastDispatched = null, destAccountId = 100L)
        val healthy = mirror(id = 8L, name = "ok", cron = "* * * * * *",
            lastDispatched = null, destAccountId = 101L)
        `when`(mirrorConfigService.findEnabled()).thenReturn(listOf(failing, healthy))

        `when`(mirrorJobLauncher.launch(eq(7L), anyString()))
            .thenThrow(RuntimeException("simulated dispatch failure"))
        `when`(mirrorJobLauncher.launch(eq(8L), anyString())).thenReturn(jobExecution())

        val results = scheduler.runDueMirrors(now)

        assertThat(results).hasSize(2)
        val byId = results.associateBy { it.mirrorConfigId }
        assertThat(byId[7L]?.outcome).isEqualTo(MirrorDispatchOutcome.ERROR)
        assertThat(byId[8L]?.outcome).isEqualTo(MirrorDispatchOutcome.DISPATCHED)

        verify(mirrorConfigService).recordDispatched(eq(8L), anyOffsetDateTime())
        verify(mirrorConfigService, never()).recordDispatched(eq(7L), anyOffsetDateTime())
    }

    @Test
    fun `runDueMirrors is a no-op when no mirrors are enabled`() {
        `when`(mirrorConfigService.findEnabled()).thenReturn(emptyList())

        val results = scheduler.runDueMirrors(now)

        assertThat(results).isEmpty()
        verify(mirrorJobLauncher, never()).launch(anyLong(), anyString())
    }

    @Test
    fun `tick is a no-op when scheduler is globally disabled`() {
        properties.enabled = false

        scheduler.tick()

        verify(mirrorConfigService, never()).findEnabled()
        verify(mirrorJobLauncher, never()).launch(anyLong(), anyString())
    }

    // ---- helpers ----

    /**
     * Kotlin-safe matcher for `OffsetDateTime`. Mockito's `any(Class)` returns
     * null, which trips Kotlin's compiler-inserted null check on non-null
     * parameters. This registers the matcher with Mockito and returns a
     * real instance so the call site is happy.
     */
    private fun anyOffsetDateTime(): OffsetDateTime {
        ArgumentMatchers.any(OffsetDateTime::class.java)
        return OffsetDateTime.now()
    }

    private fun mirror(
        id: Long,
        name: String,
        cron: String?,
        lastDispatched: OffsetDateTime?,
        destAccountId: Long? = 200L
    ): MirrorConfigDTO = MirrorConfigDTO().apply {
        this.id = id
        this.uri = "mirror://$name-$id"
        this.name = name
        this.enabled = true
        this.version = 1L
        this.sourceAccountId = 100L
        this.destAccountId = destAccountId
        this.cronSchedule = cron
        this.lastDispatchedAt = lastDispatched
    }

    private fun jobExecution(): JobExecution {
        val instance = JobInstance(System.nanoTime(), "mirrorJob")
        return JobExecution(instance, JobParameters()).apply {
            id = System.nanoTime()
        }
    }
}
