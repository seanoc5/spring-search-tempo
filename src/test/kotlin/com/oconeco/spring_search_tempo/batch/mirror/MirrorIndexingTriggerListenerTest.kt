package com.oconeco.spring_search_tempo.batch.mirror

import com.oconeco.spring_search_tempo.base.events.MirrorJobCompletedEvent
import com.oconeco.spring_search_tempo.batch.emailcrawl.EmailCrawlOrchestrator
import com.oconeco.spring_search_tempo.batch.emailcrawl.ParallelizationConfig
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobInstance
import org.springframework.batch.core.JobParameters
import java.time.OffsetDateTime

/**
 * Unit tests for [MirrorIndexingTriggerListener] — verify the routing
 * decisions (auto-index toggle, missing destAccountId, zero-mirrored
 * short-circuit, swallowed downstream exceptions) without standing up a
 * full Spring context.
 */
class MirrorIndexingTriggerListenerTest {

    private lateinit var orchestrator: EmailCrawlOrchestrator

    @BeforeEach
    fun setup() {
        orchestrator = mock(EmailCrawlOrchestrator::class.java)
    }

    private fun listener(enabled: Boolean = true) =
        MirrorIndexingTriggerListener(orchestrator, autoIndexEnabled = enabled)

    private fun event(
        destAccountId: Long? = 42L,
        messagesMirrored: Long = 5L
    ) = MirrorJobCompletedEvent(
        mirrorConfigId = 7L,
        jobRunId = 100L,
        destAccountId = destAccountId,
        completedAt = OffsetDateTime.now(),
        messagesMirrored = messagesMirrored
    )

    private fun stubExecution(): JobExecution {
        val jobExecution = JobExecution(JobInstance(1L, "emailQuickSync_42"), JobParameters())
        jobExecution.id = 999L
        return jobExecution
    }

    @Test
    fun `launches quick sync for destination account on mirror completion`() {
        // Kotlin default-params compile via the $default synthetic, so verify
        // through any() matchers across the full signature and capture the
        // accountId positionally.
        `when`(
            orchestrator.runQuickSyncForAccount(
                anyLong(), anyBoolean(), anyBoolean(), anyInt(), any(ParallelizationConfig::class.java)
            )
        ).thenReturn(stubExecution())

        listener().onMirrorCompleted(event())

        val captor = ArgumentCaptor.forClass(Long::class.javaObjectType)
        verify(orchestrator).runQuickSyncForAccount(
            captor.capture(), anyBoolean(), anyBoolean(), anyInt(), any(ParallelizationConfig::class.java)
        )
        assertEquals(42L, captor.value)
    }

    @Test
    fun `skips when auto-index disabled`() {
        listener(enabled = false).onMirrorCompleted(event())

        verifyNoInteractions(orchestrator)
    }

    @Test
    fun `skips when destAccountId is null`() {
        listener().onMirrorCompleted(event(destAccountId = null))

        verify(orchestrator, never()).runQuickSyncForAccount(
            anyLong(), anyBoolean(), anyBoolean(), anyInt(), any(ParallelizationConfig::class.java)
        )
    }

    @Test
    fun `skips when zero messages mirrored`() {
        listener().onMirrorCompleted(event(messagesMirrored = 0L))

        verify(orchestrator, never()).runQuickSyncForAccount(
            anyLong(), anyBoolean(), anyBoolean(), anyInt(), any(ParallelizationConfig::class.java)
        )
    }

    @Test
    fun `swallows orchestrator exception so mirror job stays COMPLETED`() {
        `when`(
            orchestrator.runQuickSyncForAccount(
                anyLong(), anyBoolean(), anyBoolean(), anyInt(), any(ParallelizationConfig::class.java)
            )
        ).thenThrow(RuntimeException("imap down"))

        assertDoesNotThrow {
            listener().onMirrorCompleted(event())
        }

        verify(orchestrator).runQuickSyncForAccount(
            anyLong(), anyBoolean(), anyBoolean(), anyInt(), any(ParallelizationConfig::class.java)
        )
    }

    private fun <T> any(type: Class<T>): T = org.mockito.ArgumentMatchers.any(type)
}
