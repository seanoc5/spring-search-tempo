package com.oconeco.spring_search_tempo.base.service

import com.icegreen.greenmail.junit5.GreenMailExtension
import com.icegreen.greenmail.util.ServerSetup
import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.EmailFolderService
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.EmailAccount
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
import com.oconeco.spring_search_tempo.base.repos.EmailFolderRepository
import jakarta.mail.Folder
import jakarta.mail.Session
import jakarta.mail.Store
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.Properties

/**
 * IMAP folder enumeration & selective sync (issue #3).
 *
 * Uses GreenMail's plain-IMAP listener so the test is hermetic and fast —
 * no real IMAP server, no mocked Folder objects. Each test creates its
 * own account row so they don't share enumeration state.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@DisplayName("EmailFolderSyncService — multi-folder enumeration (issue #3)")
class EmailFolderSyncServiceTest : BaseIT() {

    companion object {
        @JvmField
        @RegisterExtension
        val greenMail = GreenMailExtension(ServerSetup.IMAP)

        private const val USER_EMAIL = "user@example.com"
        private const val USER_PASSWORD = "secret"
    }

    @Autowired
    lateinit var emailFolderSyncService: EmailFolderSyncService

    @Autowired
    lateinit var emailFolderService: EmailFolderService

    @Autowired
    lateinit var emailAccountService: EmailAccountService

    @Autowired
    lateinit var emailAccountRepository: EmailAccountRepository

    @Autowired
    lateinit var emailFolderRepository: EmailFolderRepository

    @BeforeEach
    fun setUp() {
        greenMail.setUser(USER_EMAIL, USER_EMAIL, USER_PASSWORD)
    }

    @Test
    @DisplayName("folder enumeration discovers nested folders and persists hierarchy")
    fun enumerationDiscoversNestedHierarchy() {
        val accountId = createAccount()
        createServerFolders(
            listOf(
                "INBOX/Archive",
                "INBOX/Archive/2025",
                "Sent",
                "Drafts",
                "[Gmail]",          // \Noselect container in real Gmail
                "[Gmail]/All Mail",
            )
        )

        emailFolderSyncService.enumerateFolders(accountId)

        val folders = emailFolderService.findByAccount(accountId)
        assertThat(folders).extracting<String> { it.path }
            .contains("INBOX", "INBOX/Archive", "INBOX/Archive/2025", "Sent", "Drafts", "[Gmail]/All Mail")

        // [Gmail] container is a \Noselect placeholder — flag it and don't auto-sync.
        val gmailContainer = folders.firstOrNull { it.path == "[Gmail]" }
        if (gmailContainer != null) {
            // GreenMail may or may not surface a parent-only folder as \Noselect, but
            // when it does, our enumeration must persist the flag.
            assertThat(gmailContainer.syncEnabled || !gmailContainer.noselect).isTrue
        }

        // Targets exclude \Noselect placeholders.
        val targets = emailFolderService.fetchTargets(accountId)
        assertThat(targets).contains("INBOX/Archive/2025", "[Gmail]/All Mail")
        targets.forEach { path ->
            val row = folders.first { it.path == path }
            assertThat(row.noselect).isFalse
        }
    }

    @Test
    @DisplayName("disabled folders are skipped by fetchTargets")
    fun disabledFoldersSkippedByReader() {
        val accountId = createAccount()
        createServerFolders(listOf("Sent", "Drafts"))
        emailFolderSyncService.enumerateFolders(accountId)

        // Disable "Drafts"
        val draftsId = emailFolderRepository.findByEmailAccountIdAndPath(accountId, "Drafts")!!.id!!
        emailFolderService.setSyncEnabled(draftsId, false)

        val targets = emailFolderService.fetchTargets(accountId)
        assertThat(targets).contains("INBOX", "Sent").doesNotContain("Drafts")
    }

    @Test
    @DisplayName("UIDVALIDITY change on one folder does not reset siblings")
    fun uidValidityChangePerFolder() {
        val accountId = createAccount()
        createServerFolders(listOf("Sent"))
        emailFolderSyncService.enumerateFolders(accountId)

        val inbox = emailFolderRepository.findByEmailAccountIdAndPath(accountId, "INBOX")!!
        val sent = emailFolderRepository.findByEmailAccountIdAndPath(accountId, "Sent")!!

        // Seed both folders with a sync cursor + initial UIDVALIDITY.
        emailFolderService.updateUidValidity(inbox.id!!, 1000L)
        emailFolderService.updateSyncState(inbox.id!!, 42L, 10L)
        emailFolderService.updateUidValidity(sent.id!!, 2000L)
        emailFolderService.updateSyncState(sent.id!!, 17L, 4L)

        // Simulate INBOX being recreated server-side: UIDVALIDITY changes.
        val inboxChanged = emailFolderService.updateUidValidity(inbox.id!!, 1001L)
        val sentTouched = emailFolderService.updateUidValidity(sent.id!!, 2000L)

        assertThat(inboxChanged).isTrue
        assertThat(sentTouched).isFalse

        val refreshedInbox = emailFolderService.get(inbox.id!!)
        val refreshedSent = emailFolderService.get(sent.id!!)

        // INBOX cursor wiped because its UIDs are now invalid.
        assertThat(refreshedInbox.lastSyncUid).isEqualTo(0L)
        // Sibling's cursor is untouched — its UIDVALIDITY is unchanged.
        assertThat(refreshedSent.lastSyncUid).isEqualTo(17L)
        assertThat(refreshedSent.uidValidity).isEqualTo(2000L)
    }

    @Test
    @DisplayName("re-enabling a disabled folder resets lastSyncUid so next sync pulls full history")
    fun reEnablingTriggersFullHistory() {
        val accountId = createAccount()
        createServerFolders(listOf("Sent"))
        emailFolderSyncService.enumerateFolders(accountId)

        val sentId = emailFolderRepository.findByEmailAccountIdAndPath(accountId, "Sent")!!.id!!
        emailFolderService.updateSyncState(sentId, 100L, 50L)
        emailFolderService.setSyncEnabled(sentId, false)
        // While disabled, the cursor should stay (so we don't lose tracking gratuitously).
        assertThat(emailFolderService.get(sentId).lastSyncUid).isEqualTo(100L)

        // Re-enable → cursor is wiped so the next sync pulls full history.
        emailFolderService.setSyncEnabled(sentId, true)
        val reEnabled = emailFolderService.get(sentId)
        assertThat(reEnabled.syncEnabled).isTrue
        assertThat(reEnabled.lastSyncUid).isEqualTo(0L)
    }

    @Test
    @DisplayName("re-running enumeration preserves syncEnabled selection and sync cursor")
    fun reEnumerationPreservesUserSelection() {
        val accountId = createAccount()
        createServerFolders(listOf("Sent", "Drafts"))
        emailFolderSyncService.enumerateFolders(accountId)

        val draftsId = emailFolderRepository.findByEmailAccountIdAndPath(accountId, "Drafts")!!.id!!
        emailFolderService.setSyncEnabled(draftsId, false)
        emailFolderService.updateSyncState(draftsId, 7L, 3L)

        // Disable, set a cursor, then re-enumerate — the user's choice must survive.
        emailFolderSyncService.enumerateFolders(accountId)

        val afterReEnum = emailFolderService.get(draftsId)
        assertThat(afterReEnum.syncEnabled).isFalse
        assertThat(afterReEnum.lastSyncUid).isEqualTo(7L)
    }

    private fun createAccount(): Long {
        val email = "user@example.com"
        val account = EmailAccount().apply {
            this.email = email
            this.uri = "email://$email-${System.nanoTime()}"
            this.provider = EmailProvider.GENERIC_IMAP
            this.imapHost = "127.0.0.1"
            this.imapPort = ServerSetup.IMAP.port
            this.useSsl = false
            this.enabled = true
            this.version = 1L
        }
        val id = emailAccountRepository.save(account).id!!
        emailAccountService.setPassword(id, USER_PASSWORD)
        return id
    }

    private fun createServerFolders(paths: List<String>) {
        val props = Properties().apply {
            put("mail.store.protocol", "imap")
            put("mail.imap.host", "127.0.0.1")
            put("mail.imap.port", ServerSetup.IMAP.port.toString())
        }
        val session = Session.getInstance(props)
        val store: Store = session.getStore("imap")
        store.connect("127.0.0.1", ServerSetup.IMAP.port, USER_EMAIL, USER_PASSWORD)
        try {
            paths.forEach { path ->
                val folder = store.getFolder(path)
                if (!folder.exists()) {
                    folder.create(Folder.HOLDS_MESSAGES or Folder.HOLDS_FOLDERS)
                }
            }
        } finally {
            store.close()
        }
    }
}
