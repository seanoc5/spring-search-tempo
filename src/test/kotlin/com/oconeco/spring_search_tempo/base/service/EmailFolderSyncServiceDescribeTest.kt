package com.oconeco.spring_search_tempo.base.service

import com.sun.mail.imap.IMAPFolder
import jakarta.mail.Folder
import jakarta.mail.URLName
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Lightweight, in-memory coverage of the folder-attribute parsing rules used
 * by [EmailFolderSyncService.describe]. Tests that *exercise an IMAP server*
 * live in [EmailFolderSyncServiceTest] under Testcontainers; this suite
 * focuses on the attribute-flag normalisation that we care about regardless
 * of which IMAP server returned the metadata.
 *
 * Issue #3 guardrail: attribute names vary in casing across servers — boolean
 * flags here insulate downstream code from that variance.
 */
class EmailFolderSyncServiceDescribeTest {

    private val service = EmailFolderSyncService(
        imapConnectionService = stubImapConnectionService(),
        emailAccountService = stubEmailAccountService(),
        emailAccountRepository = stubEmailAccountRepository(),
        emailFolderRepository = stubEmailFolderRepository(),
    )

    @Test
    @DisplayName("plain folder with no attributes is selectable and has no flags")
    fun plainFolder() {
        val desc = service.describe(fakeFolder("INBOX", separator = '/', attributes = emptyArray()))
        checkNotNull(desc)
        assertThat(desc.path).isEqualTo("INBOX")
        assertThat(desc.folderName).isEqualTo("INBOX")
        assertThat(desc.delimiter).isEqualTo("/")
        assertThat(desc.noselect).isFalse
        assertThat(desc.noinferiors).isFalse
        assertThat(desc.hasChildren).isFalse
    }

    @Test
    @DisplayName("nested folder splits leaf from path using server's hierarchy delimiter")
    fun nestedFolderSplitsLeaf() {
        val desc = service.describe(fakeFolder("INBOX/Archive/2025", separator = '/', attributes = emptyArray()))
        checkNotNull(desc)
        assertThat(desc.path).isEqualTo("INBOX/Archive/2025")
        assertThat(desc.folderName).isEqualTo("2025")
    }

    @Test
    @DisplayName("\\Noselect attribute (any casing) is normalised to noselect=true")
    fun noselectAttributeNormalised() {
        listOf("\\Noselect", "\\NOSELECT", "noselect").forEach { attr ->
            val desc = service.describe(
                fakeFolder("[Gmail]", separator = '/', attributes = arrayOf(attr))
            )
            assertThat(desc?.noselect).withFailMessage("attribute '%s' should map to noselect=true", attr).isTrue
        }
    }

    @Test
    @DisplayName("SPECIAL-USE attributes are surfaced via descriptor flags")
    fun specialUseAttributes() {
        val sent = service.describe(
            fakeFolder("Sent Items", separator = '/', attributes = arrayOf("\\Sent"))
        )
        assertThat(sent?.isSentAttr).isTrue

        val trash = service.describe(
            fakeFolder("Trash", separator = '/', attributes = arrayOf("\\Trash"))
        )
        assertThat(trash?.isTrashAttr).isTrue

        val allMail = service.describe(
            fakeFolder("[Gmail]/All Mail", separator = '/', attributes = arrayOf("\\All"))
        )
        assertThat(allMail?.isArchiveAttr).isTrue
    }

    @Test
    @DisplayName("\\HasChildren attribute lights up hierarchy hint")
    fun hasChildrenAttribute() {
        val desc = service.describe(
            fakeFolder("INBOX", separator = '/', attributes = arrayOf("\\HasChildren"))
        )
        assertThat(desc?.hasChildren).isTrue
    }

    @Test
    @DisplayName("non-/ delimiter (Cyrus-style '.') splits leaf correctly")
    fun cyrusStyleDelimiter() {
        val desc = service.describe(
            fakeFolder("INBOX.Archive.2025", separator = '.', attributes = emptyArray())
        )
        checkNotNull(desc)
        assertThat(desc.path).isEqualTo("INBOX.Archive.2025")
        assertThat(desc.folderName).isEqualTo("2025")
        assertThat(desc.delimiter).isEqualTo(".")
    }

    /** Subclass IMAPFolder so we can hand-craft attributes without an open IMAP connection. */
    private class FakeImapFolder(
        private val fullName: String,
        private val separator: Char,
        private val attrs: Array<String>,
        store: com.sun.mail.imap.IMAPStore
    ) : IMAPFolder(fullName, separator, store, false) {
        override fun getFullName(): String = fullName
        override fun getSeparator(): Char = separator
        override fun getAttributes(): Array<String> = attrs
    }

    private fun fakeFolder(fullName: String, separator: Char, attributes: Array<String>): Folder {
        // We don't actually open IMAP — the constructor just needs a non-null Store reference
        // and the methods we exercise are overridden above. Using a real IMAPStore avoids the
        // need for null-handling in describe().
        val session = jakarta.mail.Session.getInstance(java.util.Properties())
        val store = session.getStore(URLName("imap://localhost")) as com.sun.mail.imap.IMAPStore
        return FakeImapFolder(fullName, separator, attributes, store)
    }

    // --- stubs -------------------------------------------------------------

    private fun stubImapConnectionService(): ImapConnectionService =
        ImapConnectionService(
            emailConfiguration = com.oconeco.spring_search_tempo.base.config.EmailConfiguration(),
            emailAccountService = stubEmailAccountService(),
        )

    private fun stubEmailAccountService(): com.oconeco.spring_search_tempo.base.EmailAccountService {
        // describe() never calls into the account service — proxy is enough.
        return java.lang.reflect.Proxy.newProxyInstance(
            this::class.java.classLoader,
            arrayOf(com.oconeco.spring_search_tempo.base.EmailAccountService::class.java)
        ) { _, _, _ -> null } as com.oconeco.spring_search_tempo.base.EmailAccountService
    }

    private fun stubEmailAccountRepository(): com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository {
        // The describe() method never touches the repository — return a Mockito-free stub via dynamic proxy.
        return java.lang.reflect.Proxy.newProxyInstance(
            this::class.java.classLoader,
            arrayOf(com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository::class.java)
        ) { _, _, _ -> null } as com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
    }

    private fun stubEmailFolderRepository(): com.oconeco.spring_search_tempo.base.repos.EmailFolderRepository {
        return java.lang.reflect.Proxy.newProxyInstance(
            this::class.java.classLoader,
            arrayOf(com.oconeco.spring_search_tempo.base.repos.EmailFolderRepository::class.java)
        ) { _, _, _ -> null } as com.oconeco.spring_search_tempo.base.repos.EmailFolderRepository
    }
}
