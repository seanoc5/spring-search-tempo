package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.MirrorConfigService
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.EmailAccount
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import com.oconeco.spring_search_tempo.base.model.FolderMapping
import com.oconeco.spring_search_tempo.base.model.MirrorConfigDTO
import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
import com.oconeco.spring_search_tempo.base.repos.MirrorConfigRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * Pins the foundation contract for issue #22:
 *
 * 1. A mirror with two distinct enabled accounts persists with its folder mappings.
 * 2. A mirror with `sourceAccountId == destAccountId` is rejected.
 * 3. A mirror referencing a disabled account is rejected (per issue acceptance —
 *    "Both accounts must be enabled").
 */
@SpringBootTest(
    classes = [SpringSearchTempoApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@DisplayName("MirrorConfigService — foundation contract")
class MirrorConfigServiceTest : BaseIT() {

    @Autowired
    lateinit var mirrorConfigService: MirrorConfigService

    @Autowired
    lateinit var mirrorConfigRepository: MirrorConfigRepository

    @Autowired
    lateinit var emailAccountRepository: EmailAccountRepository

    @Test
    @DisplayName("creating a mirror with two accounts persists folder mappings")
    fun creatingMirrorPersistsFolderMappings() {
        val src = createAccount(
            email = "old@example.com",
            host = "imap.workmail.us-east-1.amazonaws.com"
        )
        val dst = createAccount(
            email = "new@example.com",
            host = "imap.gmail.com"
        )

        val saved = mirrorConfigService.create(
            name = "oconeco.com migration",
            sourceAccountId = src,
            destAccountId = dst,
            folderMappings = listOf(
                FolderMapping(source = "INBOX", dest = "INBOX", enabled = true),
                FolderMapping(source = "Sent", dest = "Sent", enabled = true)
            )
        )

        val reloaded = mirrorConfigService.get(saved.id!!)
        assertThat(reloaded.name).isEqualTo("oconeco.com migration")
        assertThat(reloaded.sourceAccountId).isEqualTo(src)
        assertThat(reloaded.destAccountId).isEqualTo(dst)
        assertThat(reloaded.folderMappings).hasSize(2)
        assertThat(reloaded.folderMappings.first().source).isEqualTo("INBOX")
        assertThat(reloaded.folderMappings.first().dest).isEqualTo("INBOX")
        assertThat(reloaded.folderMappings.first().enabled).isTrue()
        assertThat(reloaded.appendRateLimitPerSecond).isEqualTo(10)
    }

    @Test
    @DisplayName("cannot create mirror with source == dest")
    fun cannotCreateWithIdenticalAccounts() {
        val accountId = createAccount(email = "self@example.com")

        assertThatThrownBy {
            mirrorConfigService.create(
                name = "bad",
                sourceAccountId = accountId,
                destAccountId = accountId,
                folderMappings = emptyList()
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("source")
    }

    @Test
    @DisplayName("cannot create mirror referencing a disabled account")
    fun cannotCreateReferencingDisabledAccount() {
        val src = createAccount(email = "src@example.com", enabled = false)
        val dst = createAccount(email = "dst@example.com")

        assertThatThrownBy {
            mirrorConfigService.create(
                name = "uses-disabled-source",
                sourceAccountId = src,
                destAccountId = dst,
                folderMappings = listOf(FolderMapping("INBOX", "INBOX"))
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("disabled")
    }

    private fun createAccount(
        email: String,
        host: String = "imap.example.com",
        enabled: Boolean = true
    ): Long {
        val account = EmailAccount().apply {
            this.email = email
            this.uri = "email://$email"
            this.provider = EmailProvider.GENERIC_IMAP
            this.imapHost = host
            this.imapPort = 993
            this.useSsl = true
            this.enabled = enabled
            this.version = 1L
        }
        return emailAccountRepository.save(account).id!!
    }
}
