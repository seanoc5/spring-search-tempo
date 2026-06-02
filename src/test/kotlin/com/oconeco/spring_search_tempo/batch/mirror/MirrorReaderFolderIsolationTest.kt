package com.oconeco.spring_search_tempo.batch.mirror

import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.MirrorConfigService
import com.oconeco.spring_search_tempo.base.domain.MirrorError
import com.oconeco.spring_search_tempo.base.model.EmailAccountDTO
import com.oconeco.spring_search_tempo.base.model.FolderMapping
import com.oconeco.spring_search_tempo.base.model.MirrorConfigDTO
import com.oconeco.spring_search_tempo.base.repos.MirrorErrorRepository
import com.oconeco.spring_search_tempo.base.repos.MirroredMessageRepository
import com.oconeco.spring_search_tempo.base.service.ImapConnectionService
import com.oconeco.spring_search_tempo.base.service.MirrorCheckpointService
import com.oconeco.spring_search_tempo.base.service.MirrorFolderCheckpointService
import com.oconeco.spring_search_tempo.base.service.MirrorFolderProgressService
import com.sun.mail.imap.IMAPFolder
import jakarta.mail.FetchProfile
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.Store
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobInstance
import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.StepExecution

/**
 * Reader-level test for issue #39 — per-folder error isolation.
 *
 * Uses a Mockito-backed "stub IMAP server" surface (mocked `Store` +
 * `IMAPFolder`) so we can deterministically force one folder to throw
 * on `open(...)` while the sibling folder enumerates cleanly. Drives
 * the reader through its full `beforeStep` → `read*` → `afterStep`
 * cycle and asserts:
 *
 *  - The failing folder triggers a folder-scope [MirrorError] with
 *    `retryable=true`, `errorScope='FOLDER'`, `sourceUid=0`.
 *  - The failing folder is stamped `FAILED` on
 *    [MirrorFolderProgressService].
 *  - The reader keeps emitting [MirrorTask]s from the sibling folder
 *    after the failure (it doesn't abort the step).
 *  - The step's `afterStep` exit status is `null` (= COMPLETED) when
 *    at least one folder succeeded — i.e. acceptance criterion 4.
 *
 * Running this at the reader level (not a full Spring Boot IT) keeps
 * the test deterministic and isolated from GreenMail's lack of per-
 * folder failure injection.
 */
@DisplayName("MirrorMessageReader — per-folder error isolation (issue #39)")
class MirrorReaderFolderIsolationTest {

    private val mirrorConfigId = 7L
    private val jobRunId = 42L

    private lateinit var mirrorConfigService: MirrorConfigService
    private lateinit var emailAccountService: EmailAccountService
    private lateinit var imapConnectionService: ImapConnectionService
    private lateinit var mirroredMessageRepository: MirroredMessageRepository
    private lateinit var checkpointService: MirrorCheckpointService
    private lateinit var folderCheckpointService: MirrorFolderCheckpointService
    private lateinit var folderProgressService: MirrorFolderProgressService
    private lateinit var mirrorErrorRepository: MirrorErrorRepository

    private lateinit var store: Store
    private lateinit var okFolder: IMAPFolder
    private lateinit var failFolder: IMAPFolder

    @BeforeEach
    fun setUp() {
        mirrorConfigService = mock(MirrorConfigService::class.java)
        emailAccountService = mock(EmailAccountService::class.java)
        imapConnectionService = mock(ImapConnectionService::class.java)
        mirroredMessageRepository = mock(MirroredMessageRepository::class.java)
        checkpointService = mock(MirrorCheckpointService::class.java)
        folderCheckpointService = mock(MirrorFolderCheckpointService::class.java)
        folderProgressService = mock(MirrorFolderProgressService::class.java)
        mirrorErrorRepository = mock(MirrorErrorRepository::class.java)

        // MirrorConfigService.get(...) → DTO with 2 enabled mappings:
        //   OK_FOLDER (succeeds) → DEST_OK
        //   FAIL_FOLDER (open() throws) → DEST_FAIL
        val dto = MirrorConfigDTO().apply {
            id = mirrorConfigId
            sourceAccountId = 99L
            destAccountId = 100L
            folderMappings = listOf(
                FolderMapping(source = "OK_FOLDER", dest = "DEST_OK", enabled = true),
                FolderMapping(source = "FAIL_FOLDER", dest = "DEST_FAIL", enabled = true)
            )
        }
        `when`(mirrorConfigService.get(mirrorConfigId)).thenReturn(dto)

        val acct = EmailAccountDTO().apply {
            id = 99L
            email = "src@oconeco.com"
        }
        `when`(emailAccountService.get(99L)).thenReturn(acct)

        // Checkpoint state: nothing pre-existing — fresh run.
        `when`(checkpointService.find(mirrorConfigId)).thenReturn(null)
        `when`(folderCheckpointService.findAll(mirrorConfigId)).thenReturn(emptyList())
        // Nothing pre-mirrored.
        `when`(
            mirroredMessageRepository.findByMirrorConfigIdAndMessageId(anyLong(), anyString())
        ).thenReturn(null)

        // Build the stub IMAP layer.
        store = mock(Store::class.java)
        okFolder = mock(IMAPFolder::class.java)
        failFolder = mock(IMAPFolder::class.java)

        configureOkFolder()
        configureFailFolder()

        `when`(store.getFolder("OK_FOLDER")).thenReturn(okFolder)
        `when`(store.getFolder("FAIL_FOLDER")).thenReturn(failFolder)
        `when`(imapConnectionService.connect(acct)).thenReturn(store)
    }

    /**
     * A well-behaved folder holding two messages with UIDs 101 and 102.
     */
    private fun configureOkFolder() {
        `when`(okFolder.exists()).thenReturn(true)
        `when`(okFolder.type).thenReturn(Folder.HOLDS_MESSAGES)
        val m1 = mock(Message::class.java)
        val m2 = mock(Message::class.java)
        `when`(m1.getHeader("Message-ID")).thenReturn(arrayOf("<m1@oconeco.com>"))
        `when`(m2.getHeader("Message-ID")).thenReturn(arrayOf("<m2@oconeco.com>"))
        `when`(okFolder.messages).thenReturn(arrayOf(m1, m2))
        `when`(okFolder.getUID(m1)).thenReturn(101L)
        `when`(okFolder.getUID(m2)).thenReturn(102L)
        // fetch(...) and close(...) are no-ops here; default void behavior
        // is fine, but the reader calls `folder.fetch(arr, profile)` which
        // doesn't return anything — leave it un-stubbed.
    }

    /**
     * A folder that exists and holds messages, but throws on
     * `open(...)` — the connection-blip / quota / auth-failure class of
     * error issue #39 isolates.
     */
    private fun configureFailFolder() {
        `when`(failFolder.exists()).thenReturn(true)
        `when`(failFolder.type).thenReturn(Folder.HOLDS_MESSAGES)
        doThrow(MessagingException("simulated IMAP failure on open"))
            .`when`(failFolder).open(anyInt())
    }

    /**
     * Helper to satisfy Kotlin's non-null parameter type when the
     * verified method takes a non-nullable receiver — Mockito's
     * `any()` returns `null` at the JVM level, which trips Kotlin's
     * null-checks at the verify call site.
     */
    private fun anyMirrorError(): MirrorError {
        any(MirrorError::class.java)
        return MirrorError()
    }

    private fun newReader(): MirrorMessageReader =
        MirrorMessageReader(
            mirrorConfigId = mirrorConfigId,
            mirrorConfigService = mirrorConfigService,
            emailAccountService = emailAccountService,
            imapConnectionService = imapConnectionService,
            mirroredMessageRepository = mirroredMessageRepository,
            checkpointService = checkpointService,
            folderCheckpointService = folderCheckpointService,
            folderProgressService = folderProgressService,
            mirrorErrorRepository = mirrorErrorRepository
        )

    private fun stepExecutionFor(mirrorConfigId: Long, jobRunId: Long): StepExecution {
        val params = JobParametersBuilder()
            .addLong("mirrorConfigId", mirrorConfigId)
            .toJobParameters()
        val jobExec = JobExecution(JobInstance(1L, "mirrorJob"), params)
        jobExec.executionContext.putLong(MirrorJobLifecycleListener.JOB_RUN_ID_KEY, jobRunId)
        return StepExecution("mirrorStep", jobExec)
    }

    @Test
    @DisplayName("folder-level open() failure logs MirrorError(scope=FOLDER), marks folder FAILED, continues to sibling")
    fun perFolderIsolation() {
        val reader = newReader()
        val stepExec = stepExecutionFor(mirrorConfigId, jobRunId)
        reader.beforeStep(stepExec)

        // Drain the reader — it should emit one task per UID in OK_FOLDER
        // (2 tasks) and zero from FAIL_FOLDER.
        val emitted = generateSequence { reader.read() }.toList()

        assertThat(emitted.map { it.sourceFolder to it.sourceUid })
            .containsExactly(
                "OK_FOLDER" to 101L,
                "OK_FOLDER" to 102L
            )

        // FAIL_FOLDER did NOT poison the run — OK_FOLDER's tasks were
        // all emitted normally even though FAIL_FOLDER threw on open().

        // A folder-scope MirrorError was persisted with the right shape.
        val errCaptor = ArgumentCaptor.forClass(MirrorError::class.java)
        verify(mirrorErrorRepository).save(errCaptor.capture())
        val err = errCaptor.value
        assertThat(err.sourceFolder).isEqualTo("FAIL_FOLDER")
        assertThat(err.destFolder).isEqualTo("DEST_FAIL")
        assertThat(err.errorScope).isEqualTo("FOLDER")
        assertThat(err.retryable).isTrue()
        assertThat(err.sourceUid).isEqualTo(0L)
        assertThat(err.jobRunId).isEqualTo(jobRunId)
        assertThat(err.reason).contains("Folder enumeration failed")

        // The folder-progress dashboard row was stamped FAILED.
        verify(folderProgressService).recordFolderFailed(
            mirrorConfigId,
            jobRunId,
            "FAIL_FOLDER",
            "DEST_FAIL"
        )
        // The succeeding folder was stamped opened + completed.
        verify(folderProgressService).recordFolderOpened(
            mirrorConfigId,
            jobRunId,
            "OK_FOLDER",
            "DEST_OK",
            2L
        )
        verify(folderProgressService).recordFolderCompleted(jobRunId, "OK_FOLDER")

        // Step exit: null = use the default (COMPLETED), because at
        // least one folder succeeded. Acceptance criterion 4.
        val exit = reader.afterStep(stepExec)
        assertThat(exit).isNull()
    }

    @Test
    @DisplayName("when every folder fails, afterStep returns FAILED so the step rolls up failed")
    fun allFoldersFailedFlipsStepToFailed() {
        // Reconfigure OK_FOLDER to also throw on open(), so every folder
        // fails — acceptance criterion 4's other branch.
        doThrow(MessagingException("simulated: OK_FOLDER also down"))
            .`when`(okFolder).open(anyInt())

        val reader = newReader()
        val stepExec = stepExecutionFor(mirrorConfigId, jobRunId)
        reader.beforeStep(stepExec)

        val emitted = generateSequence { reader.read() }.toList()
        assertThat(emitted).isEmpty()

        // Two folder-scope errors were recorded.
        verify(mirrorErrorRepository, org.mockito.Mockito.times(2))
            .save(anyMirrorError())

        val exit = reader.afterStep(stepExec)
        assertThat(exit).isNotNull
        assertThat(exit!!.exitCode).isEqualTo("FAILED")
        assertThat(exit.exitDescription).contains("All 2 folder(s) failed")
    }
}
