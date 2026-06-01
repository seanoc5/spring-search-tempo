package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.MirrorConfigService
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import com.oconeco.spring_search_tempo.base.model.EmailAccountDTO
import com.oconeco.spring_search_tempo.base.model.FolderMapping
import com.oconeco.spring_search_tempo.base.model.MirrorConfigDTO
import com.oconeco.spring_search_tempo.base.repos.MirroredMessageRepository
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Store
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.anyString
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Unit-level tests for the dry-run service (issue #25). Mocks
 * `MirrorConfigService`, `EmailAccountService`, `ImapConnectionService`,
 * and `MirroredMessageRepository` so the result shape, totals math, and
 * sealed-error mapping are pinned without standing up the full Spring
 * Boot context or a real IMAP server.
 *
 * The full-stack integration shape (GreenMail + Postgres via
 * Testcontainers) is covered by the project's existing IT scaffold and
 * runs locally / in CI when Docker is reachable; the test here is the
 * authoritative pinning of the service's contract.
 */
@DisplayName("MirrorDryRunService — dry-run contract (issue #25)")
class MirrorDryRunServiceTest {

    private lateinit var mirrorConfigService: MirrorConfigService
    private lateinit var emailAccountService: EmailAccountService
    private lateinit var imapConnectionService: ImapConnectionService
    private lateinit var mirroredMessageRepository: MirroredMessageRepository

    private lateinit var service: MirrorDryRunService

    @BeforeEach
    fun setUp() {
        mirrorConfigService = mock(MirrorConfigService::class.java)
        emailAccountService = mock(EmailAccountService::class.java)
        imapConnectionService = mock(ImapConnectionService::class.java)
        mirroredMessageRepository = mock(MirroredMessageRepository::class.java)

        service = MirrorDryRunService(
            mirrorConfigService = mirrorConfigService,
            emailAccountService = emailAccountService,
            imapConnectionService = imapConnectionService,
            mirroredMessageRepository = mirroredMessageRepository
        )
    }

    @Test
    @DisplayName("Ok result reports per-folder counts and totals")
    fun okResultReportsCountsAndTotals() {
        val configId = configWith(
            mappings = listOf(FolderMapping("INBOX", "INBOX", enabled = true)),
            rate = 10
        )
        val sourceStore = fakeStore(
            folders = mapOf("INBOX" to FolderStub(messages = sized(100, 50_000)))
        )
        val destStore = fakeStore(folders = mapOf("INBOX" to FolderStub(messages = emptyList())))
        stubConnects(source = sourceStore, dest = destStore)

        val ok = service.dryRun(configId) as MirrorDryRunResult.Ok
        assertThat(ok.perFolder).hasSize(1)
        val inbox = ok.perFolder.first { it.sourceFolder == "INBOX" }
        assertThat(inbox.sourceMessageCount).isEqualTo(100L)
        assertThat(inbox.sourceBytesEstimate).isEqualTo(100L * 50_000L)
        assertThat(inbox.destMessageCount).isEqualTo(0L)
        assertThat(inbox.alreadyMirroredCount).isEqualTo(0L)

        assertThat(ok.totals.messages).isEqualTo(100L)
        assertThat(ok.totals.bytesEstimate).isEqualTo(100L * 50_000L)
        // 100 msgs / 10 per second → 10s
        assertThat(ok.totals.estimatedSeconds).isEqualTo(10L)
    }

    @Test
    @DisplayName("multi-folder totals sum across mappings")
    fun multiFolderTotalsSum() {
        val configId = configWith(
            mappings = listOf(
                FolderMapping("INBOX", "INBOX"),
                FolderMapping("Sent", "Sent")
            ),
            rate = 10
        )
        val sourceStore = fakeStore(folders = mapOf(
            "INBOX" to FolderStub(messages = sized(100, 50_000)),
            "Sent" to FolderStub(messages = sized(25, 75_000))
        ))
        val destStore = fakeStore(folders = mapOf(
            "INBOX" to FolderStub(messages = emptyList()),
            "Sent" to FolderStub(messages = emptyList())
        ))
        stubConnects(source = sourceStore, dest = destStore)

        val ok = service.dryRun(configId) as MirrorDryRunResult.Ok
        assertThat(ok.perFolder).hasSize(2)
        assertThat(ok.totals.messages).isEqualTo(125L)
        assertThat(ok.totals.bytesEstimate).isEqualTo(100L * 50_000L + 25L * 75_000L)
    }

    @Test
    @DisplayName("already-mirrored messages reduce the to-copy total")
    fun alreadyMirroredReducesToCopy() {
        val configId = configWith(
            mappings = listOf(FolderMapping("INBOX", "INBOX")),
            rate = 10
        )
        val sourceStore = fakeStore(
            folders = mapOf("INBOX" to FolderStub(messages = sized(50, 1_000)))
        )
        val destStore = fakeStore(folders = mapOf("INBOX" to FolderStub(messages = emptyList())))
        stubConnects(source = sourceStore, dest = destStore)

        `when`(mirroredMessageRepository.countByMirrorConfigIdAndSourceFolder(configId, "INBOX"))
            .thenReturn(20L)

        val ok = service.dryRun(configId) as MirrorDryRunResult.Ok
        val inbox = ok.perFolder.single()
        assertThat(inbox.alreadyMirroredCount).isEqualTo(20L)
        assertThat(ok.totals.alreadyMirrored).isEqualTo(20L)
        assertThat(ok.totals.messages).isEqualTo(30L)
    }

    @Test
    @DisplayName("time estimate honors per-config rate limit")
    fun timeEstimateHonorsRateLimit() {
        // 1000 msgs at 10/sec → ceil(1000/10) = 100s exactly
        val configId = configWith(
            mappings = listOf(FolderMapping("INBOX", "INBOX")),
            rate = 10
        )
        val sourceStore = fakeStore(
            folders = mapOf("INBOX" to FolderStub(messages = sized(1000, 100)))
        )
        val destStore = fakeStore(folders = mapOf("INBOX" to FolderStub(messages = emptyList())))
        stubConnects(source = sourceStore, dest = destStore)

        val ok = service.dryRun(configId) as MirrorDryRunResult.Ok
        assertThat(ok.totals.messages).isEqualTo(1000L)
        assertThat(ok.totals.estimatedSeconds).isEqualTo(100L)
    }

    @Test
    @DisplayName("null rate limit reports 0s estimate (no throttle configured)")
    fun nullRateLimitMeansNoEstimate() {
        val configId = configWith(
            mappings = listOf(FolderMapping("INBOX", "INBOX")),
            rate = null
        )
        val sourceStore = fakeStore(
            folders = mapOf("INBOX" to FolderStub(messages = sized(500, 100)))
        )
        val destStore = fakeStore(folders = mapOf("INBOX" to FolderStub(messages = emptyList())))
        stubConnects(source = sourceStore, dest = destStore)

        val ok = service.dryRun(configId) as MirrorDryRunResult.Ok
        assertThat(ok.totals.estimatedSeconds).isEqualTo(0L)
    }

    @Test
    @DisplayName("disabled folder mappings are skipped")
    fun disabledMappingsAreSkipped() {
        val configId = configWith(
            mappings = listOf(
                FolderMapping("INBOX", "INBOX", enabled = true),
                FolderMapping("Spam", "Spam", enabled = false)
            ),
            rate = 10
        )
        val sourceStore = fakeStore(folders = mapOf(
            "INBOX" to FolderStub(messages = sized(5, 1_000)),
            "Spam" to FolderStub(messages = sized(99, 1_000))
        ))
        val destStore = fakeStore(folders = mapOf(
            "INBOX" to FolderStub(messages = emptyList()),
            "Spam" to FolderStub(messages = emptyList())
        ))
        stubConnects(source = sourceStore, dest = destStore)

        val ok = service.dryRun(configId) as MirrorDryRunResult.Ok
        assertThat(ok.perFolder).hasSize(1)
        assertThat(ok.perFolder.single().sourceFolder).isEqualTo("INBOX")
        assertThat(ok.totals.messages).isEqualTo(5L)
    }

    @Test
    @DisplayName("AuthFailed(SOURCE) when source credentials are wrong")
    fun authFailedSource() {
        val configId = configWith(
            mappings = listOf(FolderMapping("INBOX", "INBOX")),
            rate = 10
        )
        stubConnectsBy(
            sourceId = 1L,
            destId = 2L,
            sourceBehavior = { throw AuthenticationFailedException("LOGIN failed") },
            destBehavior = { fakeStore(folders = emptyMap()) }
        )

        val result = service.dryRun(configId)
        assertThat(result).isInstanceOf(MirrorDryRunResult.AuthFailed::class.java)
        val auth = result as MirrorDryRunResult.AuthFailed
        assertThat(auth.side).isEqualTo(Side.SOURCE)
        assertThat(auth.reason).contains("LOGIN failed")
    }

    @Test
    @DisplayName("AuthFailed(DEST) when dest credentials are wrong")
    fun authFailedDest() {
        val configId = configWith(
            mappings = listOf(FolderMapping("INBOX", "INBOX")),
            rate = 10
        )
        stubConnectsBy(
            sourceId = 1L,
            destId = 2L,
            sourceBehavior = { fakeStore(folders = emptyMap()) },
            destBehavior = { throw AuthenticationFailedException("dest bad pw") }
        )

        val result = service.dryRun(configId)
        assertThat(result).isInstanceOf(MirrorDryRunResult.AuthFailed::class.java)
        val auth = result as MirrorDryRunResult.AuthFailed
        assertThat(auth.side).isEqualTo(Side.DEST)
    }

    @Test
    @DisplayName("SourceUnreachable when source IMAP connect throws IOException")
    fun sourceUnreachable() {
        val configId = configWith(
            mappings = listOf(FolderMapping("INBOX", "INBOX")),
            rate = 10
        )
        stubConnectsBy(
            sourceId = 1L,
            destId = 2L,
            sourceBehavior = { throw jakarta.mail.MessagingException("connection refused") },
            destBehavior = { fakeStore(folders = emptyMap()) }
        )

        val result = service.dryRun(configId)
        assertThat(result).isInstanceOf(MirrorDryRunResult.SourceUnreachable::class.java)
        assertThat((result as MirrorDryRunResult.SourceUnreachable).reason).contains("connection refused")
    }

    @Test
    @DisplayName("empty mappings short-circuits to Ok with zero totals")
    fun emptyMappingsShortCircuits() {
        val configId = configWith(mappings = emptyList(), rate = 10)
        val ok = service.dryRun(configId) as MirrorDryRunResult.Ok
        assertThat(ok.perFolder).isEmpty()
        assertThat(ok.totals.messages).isEqualTo(0L)
        assertThat(ok.totals.estimatedSeconds).isEqualTo(0L)
    }

    @Test
    @DisplayName("missing MirrorConfig id throws IllegalArgumentException")
    fun missingConfigThrows() {
        `when`(mirrorConfigService.get(999L)).thenThrow(NotFoundException())
        try {
            service.dryRun(999L)
            assertThat(false).`as`("expected IllegalArgumentException").isTrue
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("999")
        }
    }

    // ---- helpers ----

    private fun configWith(
        mappings: List<FolderMapping>,
        rate: Int?
    ): Long {
        val configId = 42L
        val dto = MirrorConfigDTO().apply {
            this.id = configId
            this.name = "test"
            this.sourceAccountId = 1L
            this.destAccountId = 2L
            this.folderMappings = mappings
            this.appendRateLimitPerSecond = rate
        }
        `when`(mirrorConfigService.get(configId)).thenReturn(dto)
        `when`(emailAccountService.get(1L)).thenReturn(accountDto(1L, "source@example.com"))
        `when`(emailAccountService.get(2L)).thenReturn(accountDto(2L, "dest@example.com"))
        // Default 0 for already-mirrored unless the test overrides.
        `when`(mirroredMessageRepository.countByMirrorConfigIdAndSourceFolder(eq(configId), anyString()))
            .thenReturn(0L)
        return configId
    }

    private fun stubConnects(source: Store, dest: Store) {
        stubConnectsBy(
            sourceId = 1L,
            destId = 2L,
            sourceBehavior = { source },
            destBehavior = { dest }
        )
    }

    /**
     * Stub `imapConnectionService.connect` with side-specific behavior. The
     * dispatch is by account id (NOT Mockito argument matchers) to avoid
     * matcher-state leakage between tests.
     */
    private fun stubConnectsBy(
        sourceId: Long,
        destId: Long,
        sourceBehavior: () -> Store,
        destBehavior: () -> Store
    ) {
        `when`(imapConnectionService.connect(anyNonNull(EmailAccountDTO::class.java)))
            .thenAnswer { invocation ->
                val account = invocation.getArgument<EmailAccountDTO>(0)
                when (account.id) {
                    sourceId -> sourceBehavior()
                    destId -> destBehavior()
                    else -> throw IllegalArgumentException("unexpected account id ${account.id}")
                }
            }
    }

    /**
     * Kotlin-friendly Mockito `any()`: returns a non-null sentinel so the
     * call site doesn't NPE when matcher state is being recorded.
     */
    private fun <T> anyNonNull(clazz: Class<T>): T {
        ArgumentMatchers.any(clazz)
        return clazz.getDeclaredConstructor().newInstance()
    }

    private fun accountDto(id: Long, email: String): EmailAccountDTO = EmailAccountDTO().apply {
        this.id = id
        this.email = email
        this.provider = EmailProvider.GENERIC_IMAP
        this.imapHost = "127.0.0.1"
        this.imapPort = 143
        this.useSsl = false
        this.uri = "email://$email"
        this.version = 1L
    }

    private fun sized(count: Int, eachBytes: Int): List<Message> =
        (1..count).map {
            val msg = mock(Message::class.java)
            `when`(msg.size).thenReturn(eachBytes)
            msg
        }

    private fun fakeStore(folders: Map<String, FolderStub>): Store {
        val store = mock(Store::class.java)
        `when`(store.getFolder(anyString())).thenAnswer { invocation ->
            val name = invocation.getArgument<String>(0)
            folders[name]?.let { stub ->
                val folder = mock(Folder::class.java)
                `when`(folder.exists()).thenReturn(true)
                `when`(folder.type).thenReturn(Folder.HOLDS_MESSAGES)
                `when`(folder.messageCount).thenReturn(stub.messages.size)
                `when`(folder.messages).thenReturn(stub.messages.toTypedArray())
                folder
            } ?: run {
                val folder = mock(Folder::class.java)
                `when`(folder.exists()).thenReturn(false)
                folder
            }
        }
        return store
    }

    private data class FolderStub(val messages: List<Message>)
}
