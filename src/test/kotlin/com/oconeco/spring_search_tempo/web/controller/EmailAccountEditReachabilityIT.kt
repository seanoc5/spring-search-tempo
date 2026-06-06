package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.EmailAccount
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.model
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.view
import java.net.ServerSocket

/**
 * Pre-save reachability check on POST /emailAccounts/{id}/edit (issue #72).
 *
 * The check is a 3-second TCP connect to <imapHost>:<imapPort>. On failure the
 * controller re-renders the edit form with a field-level error on imapHost and
 * does NOT persist the form. A "Skip reachability check" checkbox bypasses the
 * probe for cases where the host is temporarily down but the operator still
 * wants the row updated.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@AutoConfigureMockMvc
@DisplayName("EmailAccountController.update — pre-save reachability check (issue #72)")
class EmailAccountEditReachabilityIT : BaseIT() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var emailAccountRepository: EmailAccountRepository

    @Test
    @DisplayName("Unresolvable host re-renders the edit form with an imapHost field error and persists nothing")
    fun unresolvableHostBlocksSave() {
        val id = createAccount("reach-bad-host@example.com", imapHost = "imap.example.com")

        mockMvc.perform(
            post("/emailAccounts/{id}/edit", id)
                .editFormParams(id, "reach-bad-host@example.com")
                // RFC 2606 reserved TLD — guaranteed not to resolve.
                .param("imapHost", "does-not-resolve.invalid")
                .param("imapPort", "993")
                .with(user(BaseIT.LOGIN).roles("USER"))
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(view().name("emailAccount/edit"))
            .andExpect(model().attributeHasFieldErrors("emailAccount", "imapHost"))

        val after = emailAccountRepository.findById(id).orElseThrow()
        assertThat(after.imapHost)
            .describedAs("Unreachable host must not be persisted")
            .isEqualTo("imap.example.com")
    }

    @Test
    @DisplayName("Valid host with refused port re-renders the edit form with an imapHost error and persists nothing")
    fun unreachablePortBlocksSave() {
        // Grab an ephemeral port, then close the socket so the OS will refuse the connect.
        val refusedPort = ServerSocket(0).use { it.localPort }
        val id = createAccount("reach-bad-port@example.com", imapHost = "imap.example.com", imapPort = 993)

        mockMvc.perform(
            post("/emailAccounts/{id}/edit", id)
                .editFormParams(id, "reach-bad-port@example.com")
                .param("imapHost", "127.0.0.1")
                .param("imapPort", refusedPort.toString())
                .with(user(BaseIT.LOGIN).roles("USER"))
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(view().name("emailAccount/edit"))
            .andExpect(model().attributeHasFieldErrors("emailAccount", "imapHost"))

        val after = emailAccountRepository.findById(id).orElseThrow()
        assertThat(after.imapHost)
            .describedAs("Refused host:port must not be persisted")
            .isEqualTo("imap.example.com")
        assertThat(after.imapPort).isEqualTo(993)
    }

    @Test
    @DisplayName("skipReachabilityCheck=true bypasses the probe and persists the unreachable host")
    fun skipCheckboxBypassesProbe() {
        val id = createAccount("reach-skip@example.com", imapHost = "imap.example.com")

        mockMvc.perform(
            post("/emailAccounts/{id}/edit", id)
                .editFormParams(id, "reach-skip@example.com")
                .param("imapHost", "does-not-resolve.invalid")
                .param("imapPort", "993")
                .param("skipReachabilityCheck", "true")
                .with(user(BaseIT.LOGIN).roles("USER"))
                .with(csrf())
        ).andExpect(status().is3xxRedirection)

        val after = emailAccountRepository.findById(id).orElseThrow()
        assertThat(after.imapHost)
            .describedAs("Skip checkbox must allow the unreachable host to be saved")
            .isEqualTo("does-not-resolve.invalid")
    }

    @Test
    @DisplayName("Reachable host:port persists normally")
    fun reachableHostSavesNormally() {
        // Keep a listener open for the duration of the probe so it succeeds.
        ServerSocket(0).use { listener ->
            val id = createAccount("reach-ok@example.com", imapHost = "imap.example.com")

            mockMvc.perform(
                post("/emailAccounts/{id}/edit", id)
                    .editFormParams(id, "reach-ok@example.com")
                    .param("imapHost", "127.0.0.1")
                    .param("imapPort", listener.localPort.toString())
                    .with(user(BaseIT.LOGIN).roles("USER"))
                    .with(csrf())
            ).andExpect(status().is3xxRedirection)

            val after = emailAccountRepository.findById(id).orElseThrow()
            assertThat(after.imapHost).isEqualTo("127.0.0.1")
            assertThat(after.imapPort).isEqualTo(listener.localPort)
        }
    }

    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.editFormParams(
        id: Long,
        email: String
    ): org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder =
        this.param("id", id.toString())
            .param("uri", "email://$email")
            .param("version", "1")
            .param("email", email)
            .param("provider", "GENERIC_IMAP")
            .param("useSsl", "true")
            .param("enabled", "true")

    private fun createAccount(email: String, imapHost: String = "imap.example.com", imapPort: Int = 993): Long {
        val account = EmailAccount().apply {
            this.email = email
            this.uri = "email://$email"
            this.provider = EmailProvider.GENERIC_IMAP
            this.imapHost = imapHost
            this.imapPort = imapPort
            this.useSsl = true
            this.enabled = true
            this.version = 1L
        }
        return emailAccountRepository.save(account).id!!
    }
}
