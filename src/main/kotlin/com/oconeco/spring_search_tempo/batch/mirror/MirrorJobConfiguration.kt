package com.oconeco.spring_search_tempo.batch.mirror

import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.MirrorConfigService
import com.oconeco.spring_search_tempo.base.repos.MirroredMessageRepository
import com.oconeco.spring_search_tempo.base.service.ImapConnectionService
import com.oconeco.spring_search_tempo.base.service.MirrorCheckpointService
import com.oconeco.spring_search_tempo.base.service.mirror.ImapMirrorService
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.configuration.annotation.JobScope
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

/**
 * Spring Batch wiring for `MirrorJob` (issue #24).
 *
 * The job is parameterized by `mirrorConfigId`. A single step iterates every
 * enabled folder mapping in the config, mirrors each pending source UID via
 * `ImapMirrorService`, and persists a `MirrorCheckpoint` at the end of each
 * chunk so a mid-folder interruption can be resumed.
 *
 * Chunk size starts at 25 (configurable via `app.email.mirror.chunk-size`).
 * The step runs single-threaded so the reader's IMAP source connection
 * isn't shared across threads — the destination connection is reopened
 * per call inside `ImapMirrorService`, which is the correctness boundary.
 */
@Configuration
class MirrorJobConfiguration(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val mirrorConfigService: MirrorConfigService,
    private val emailAccountService: EmailAccountService,
    private val imapConnectionService: ImapConnectionService,
    private val mirroredMessageRepository: MirroredMessageRepository,
    private val checkpointService: MirrorCheckpointService,
    private val imapMirrorService: ImapMirrorService,
    private val mirrorJobLifecycleListener: MirrorJobLifecycleListener,
    private val properties: MirrorJobProperties
) {

    @Bean
    fun mirrorJob(mirrorStep: Step): Job =
        JobBuilder("mirrorJob", jobRepository)
            .listener(mirrorJobLifecycleListener)
            .start(mirrorStep)
            .build()

    @Bean
    @JobScope
    fun mirrorStep(
        @Value("#{jobParameters['mirrorConfigId']}") mirrorConfigId: Long
    ): Step {
        val reader = MirrorMessageReader(
            mirrorConfigId = mirrorConfigId,
            mirrorConfigService = mirrorConfigService,
            emailAccountService = emailAccountService,
            imapConnectionService = imapConnectionService,
            mirroredMessageRepository = mirroredMessageRepository,
            checkpointService = checkpointService
        )
        val processor = MirrorMessageProcessor(imapMirrorService)
        val writer = MirrorCheckpointWriter(checkpointService)

        return StepBuilder("mirrorStep", jobRepository)
            .chunk<MirrorTask, MirrorTask>(properties.chunkSize, transactionManager)
            .reader(reader)
            .processor(processor)
            .writer(writer)
            // Register the reader as a step listener so it can rebind to
            // the current job's mirrorConfigId in `beforeStep`, avoiding
            // `@JobScope` proxy refresh issues across consecutive runs.
            .listener(reader)
            .build()
    }
}

/**
 * Tunables for [MirrorJobConfiguration]. New keys (timeouts, fetch batch
 * size) can be added here without touching the job wiring.
 */
@Configuration
@ConfigurationProperties(prefix = "app.email.mirror")
data class MirrorJobProperties(
    /**
     * Spring Batch chunk size for the `mirrorStep`. Smaller chunks tighten
     * the checkpoint cadence; larger chunks reduce DB write overhead. The
     * default matches the guardrail in the issue brief.
     */
    var chunkSize: Int = 25
)
