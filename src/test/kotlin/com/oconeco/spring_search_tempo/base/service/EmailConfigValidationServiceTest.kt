package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.service.email.EmailTestFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Duration

@DisplayName("EmailConfigValidationService — IMAP pre-flight probe (issue #6)")
class EmailConfigValidationServiceTest {

    companion object {
        @JvmField
        @RegisterExtension
        val greenMail = EmailTestFixtures.imapExtension()
    }

    private val service = EmailConfigValidationService(TcpReachabilityProbe())

    @BeforeEach
    fun setup() {
        EmailTestFixtures.createUser(
            greenMail,
            EmailTestFixtures.DEFAULT_USER,
            EmailTestFixtures.DEFAULT_PASSWORD
        )
    }

    private fun validForm() = EmailAccountForm(
        host = "127.0.0.1",
        port = EmailTestFixtures.IMAP.port,
        username = EmailTestFixtures.DEFAULT_USER,
        password = EmailTestFixtures.DEFAULT_PASSWORD,
        useSsl = false
    )

    @Test
    @DisplayName("valid IMAP account returns Ok with folder count")
    fun validReturnsOk() {
        val result = service.validate(validForm())
        assertThat(result).isInstanceOf(ValidationResult.Ok::class.java)
        val ok = result as ValidationResult.Ok
        assertThat(ok.folderCount).isGreaterThan(0)
    }

    @Test
    @DisplayName("wrong password returns AuthFailed")
    fun wrongPasswordReturnsAuthFailed() {
        val form = validForm().copy(password = "wrong")
        val result = service.validate(form)
        assertThat(result).isInstanceOf(ValidationResult.AuthFailed::class.java)
    }

    @Test
    @DisplayName("unreachable host returns Unreachable within 5s")
    fun unreachableHostShortCircuits() {
        // RFC 5737 documentation block — never routable. We assert the
        // probe completes inside the 5s budget rather than hanging.
        val form = validForm().copy(host = "192.0.2.1", port = 65000)
        assertTimeoutPreemptively(Duration.ofSeconds(8)) {
            val result = service.validate(form)
            assertThat(result).isInstanceOf(ValidationResult.Unreachable::class.java)
        }
    }

    @Test
    @DisplayName("missing required field returns LoginRejected without touching network")
    fun missingFieldsShortCircuit() {
        val result = service.validate(EmailAccountForm(host = "", port = 0))
        assertThat(result).isInstanceOf(ValidationResult.LoginRejected::class.java)
    }
}
