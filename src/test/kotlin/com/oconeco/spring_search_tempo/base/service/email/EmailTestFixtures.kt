package com.oconeco.spring_search_tempo.base.service.email

import com.icegreen.greenmail.base.GreenMailOperations
import com.icegreen.greenmail.junit5.GreenMailExtension
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetup
import com.icegreen.greenmail.util.ServerSetupTest
import jakarta.mail.internet.MimeMessage

/**
 * Reusable GreenMail-backed IMAP test fixtures.
 *
 * Issue #6 establishes this as the shared scaffold for all subsequent
 * IMAP tickets (multi-account, multi-folder, encryption, attachments).
 * Subsequent tickets should consume these helpers rather than wiring
 * their own GreenMail instances — keeps probe configuration consistent
 * across the IMAP test suite.
 *
 * Usage:
 *
 *   @RegisterExtension
 *   val greenMail = EmailTestFixtures.imapExtension()
 *
 *   @Test fun foo() {
 *       EmailTestFixtures.createUser(greenMail, "alice@example.com", "secret")
 *       ...
 *   }
 *
 * For non-JUnit-5 scenarios (e.g. SpringBoot integration tests that
 * manage lifecycle externally) use [startStandalone] / [stopStandalone].
 */
object EmailTestFixtures {

    /** Standard plain-IMAP setup on the canonical test port (3143). */
    val IMAP: ServerSetup = ServerSetupTest.IMAP

    /** Standard imaps (SSL) setup on the canonical test port (3993). */
    val IMAPS: ServerSetup = ServerSetupTest.IMAPS

    /**
     * JUnit5 extension that boots GreenMail's plain IMAP server before
     * each test class and tears it down after. Pair with `@RegisterExtension`.
     */
    fun imapExtension(): GreenMailExtension = GreenMailExtension(IMAP)

    /**
     * JUnit5 extension with both plain IMAP and IMAPS endpoints — useful
     * for tickets that need to assert TLS behaviour.
     */
    fun fullExtension(): GreenMailExtension = GreenMailExtension(arrayOf(IMAP, IMAPS))

    /**
     * Programmatically start a GreenMail server. Caller is responsible
     * for calling [stopStandalone] (typically from `@AfterAll`).
     */
    fun startStandalone(setup: ServerSetup = IMAP): GreenMail {
        val gm = GreenMail(setup)
        gm.start()
        return gm
    }

    fun stopStandalone(greenMail: GreenMail) {
        greenMail.stop()
    }

    /** Register a user; returns the email address that was set up. */
    fun createUser(greenMail: GreenMailOperations, email: String, password: String): String {
        greenMail.setUser(email, email, password)
        return email
    }

    /**
     * Deliver a minimal text/plain message to the user's INBOX. Subsequent
     * tickets that need richer content (HTML, attachments) can layer on
     * top of this helper.
     */
    fun sendSimpleMessage(
        greenMail: GreenMailOperations,
        to: String,
        from: String = "tester@example.com",
        subject: String = "Test",
        body: String = "Hello"
    ): MimeMessage {
        GreenMailUtil.sendTextEmailTest(to, from, subject, body)
        greenMail.waitForIncomingEmail(1)
        return greenMail.receivedMessages.last()
    }

    /** Canonical credentials for tests that don't care about the values. */
    const val DEFAULT_USER = "user@example.com"
    const val DEFAULT_PASSWORD = "secret"
}
