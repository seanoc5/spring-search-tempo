package com.oconeco.spring_search_tempo.batch.audit

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException

/**
 * Unit test for [FolderAuditScheduler] (issue #105, acceptance criterion `e`).
 *
 * Three concerns:
 *  1. The @Scheduled cron expression on `runWeeklyAudit` matches the
 *     placeholder that resolves from `app.audit.weekly-cron`. This is
 *     the "trigger is registered with the configured cron" assertion
 *     the issue calls out — done via reflection so we don't need to
 *     spin up a Spring context.
 *  2. A normal fire delegates to [FolderAuditService.startFilesystemAuditRun]
 *     and bumps the "triggered" metric.
 *  3. An overlapping fire (service throws [JobExecutionAlreadyRunningException])
 *     is swallowed and the "skipped" metric is incremented — that's the
 *     idempotency contract from the acceptance criteria.
 */
class FolderAuditSchedulerTest {

    @Test
    @DisplayName("@Scheduled cron placeholder matches app.audit.weekly-cron with the documented default")
    fun cronAnnotationUsesConfigurablePlaceholderWithDocumentedDefault() {
        val method = FolderAuditScheduler::class.java.getDeclaredMethod("runWeeklyAudit")
        val scheduled = method.getAnnotation(Scheduled::class.java)
        assertThat(scheduled).isNotNull
        // Spring resolves "${app.audit.weekly-cron:DEFAULT}" against the environment;
        // the literal annotation value lets us assert both the property name and the
        // default the acceptance criteria pinned.
        assertThat(scheduled.cron).isEqualTo("\${app.audit.weekly-cron:0 0 3 * * SUN}")
    }

    @Test
    @DisplayName("normal fire delegates to startFilesystemAuditRun and bumps the triggered metric")
    fun normalFireIncrementsTriggered() {
        val service = Mockito.mock(FolderAuditService::class.java)
        val registry = SimpleMeterRegistry()
        val scheduler = FolderAuditScheduler(service, AuditProperties(), registry)

        Mockito.`when`(service.startFilesystemAuditRun()).thenReturn(42L)

        scheduler.runWeeklyAudit()

        Mockito.verify(service).startFilesystemAuditRun()
        assertThat(registry.counter(FolderAuditScheduler.TRIGGERED_METRIC).count()).isEqualTo(1.0)
        assertThat(
            registry.counter(FolderAuditScheduler.SKIPPED_METRIC, "reason", "already-running").count()
        ).isZero()
    }

    @Test
    @DisplayName("overlapping fire is swallowed and increments the already-running skip metric")
    fun overlapIncrementsSkipped() {
        val service = Mockito.mock(FolderAuditService::class.java)
        val registry = SimpleMeterRegistry()
        val scheduler = FolderAuditScheduler(service, AuditProperties(), registry)

        // Use doThrow / when().{call} form so Mockito doesn't validate against
        // the (Kotlin) throws clause — JobExecutionAlreadyRunningException is
        // a Java-checked exception that startFilesystemAuditRun does not declare.
        Mockito.doThrow(JobExecutionAlreadyRunningException("already running"))
            .`when`(service).startFilesystemAuditRun()

        // Must not propagate — the scheduler thread would die otherwise.
        scheduler.runWeeklyAudit()

        assertThat(
            registry.counter(FolderAuditScheduler.SKIPPED_METRIC, "reason", "already-running").count()
        ).isEqualTo(1.0)
        assertThat(registry.counter(FolderAuditScheduler.TRIGGERED_METRIC).count()).isZero()
    }

    @Test
    @DisplayName("when weekly-enabled=false, fire is a no-op (no metric bump, no service call)")
    fun disabledFireIsNoOp() {
        val service = Mockito.mock(FolderAuditService::class.java)
        val registry = SimpleMeterRegistry()
        val scheduler = FolderAuditScheduler(
            service,
            AuditProperties(weeklyEnabled = false),
            registry
        )

        scheduler.runWeeklyAudit()

        Mockito.verifyNoInteractions(service)
        assertThat(registry.counter(FolderAuditScheduler.TRIGGERED_METRIC).count()).isZero()
    }

    @Test
    @DisplayName("unexpected exception from service is swallowed and bumps skipped/error metric")
    fun unexpectedExceptionDoesNotPropagate() {
        val service = Mockito.mock(FolderAuditService::class.java)
        val registry = SimpleMeterRegistry()
        val scheduler = FolderAuditScheduler(service, AuditProperties(), registry)

        Mockito.`when`(service.startFilesystemAuditRun())
            .thenThrow(RuntimeException("boom"))

        scheduler.runWeeklyAudit()

        assertThat(registry.counter(FolderAuditScheduler.SKIPPED_METRIC, "reason", "error").count())
            .isEqualTo(1.0)
    }
}
