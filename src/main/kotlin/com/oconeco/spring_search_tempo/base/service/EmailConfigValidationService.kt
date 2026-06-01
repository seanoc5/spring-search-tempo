package com.oconeco.spring_search_tempo.base.service

import com.sun.mail.imap.IMAPStore
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.MessagingException
import jakarta.mail.Session
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Properties
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory

/**
 * Pre-flight validation of IMAP account configuration.
 *
 * Probes are run in sequence; the first failure short-circuits and is
 * reflected in the [ValidationResult]. Each stage has a hard 5s timeout
 * to avoid hanging the UI thread.
 *
 * The service is side-effect free: it never writes to the database and
 * never authenticates beyond what is needed to determine whether the
 * supplied credentials work.
 */
@Service
class EmailConfigValidationService {

    private val log = LoggerFactory.getLogger(javaClass)

    fun validate(form: EmailAccountForm): ValidationResult {
        val host = form.host?.trim().orEmpty()
        val port = form.port ?: 0
        val username = form.username?.trim().orEmpty()
        val password = form.password.orEmpty()
        val useSsl = form.useSsl

        if (host.isBlank() || port <= 0 || username.isBlank() || password.isEmpty()) {
            return ValidationResult.LoginRejected("Missing required fields (host, port, username, password)")
        }

        // 1. TCP reachability
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
            }
        } catch (e: SocketTimeoutException) {
            log.debug("TCP probe timeout for {}:{}", host, port)
            return ValidationResult.Unreachable("Connection timed out after ${PROBE_TIMEOUT_MS / 1000}s")
        } catch (e: IOException) {
            log.debug("TCP probe failed for {}:{}: {}", host, port, e.message)
            return ValidationResult.Unreachable(e.message ?: "Connection refused")
        }

        // 2. TLS handshake (only when SSL is enabled)
        if (useSsl) {
            try {
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (factory.createSocket() as javax.net.ssl.SSLSocket).use { sslSocket ->
                    sslSocket.soTimeout = PROBE_TIMEOUT_MS
                    sslSocket.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
                    sslSocket.startHandshake()
                }
            } catch (e: SSLException) {
                log.debug("TLS handshake failed for {}:{}: {}", host, port, e.message)
                return ValidationResult.TlsFailed(e.message ?: "TLS handshake failed")
            } catch (e: IOException) {
                log.debug("TLS probe IO error for {}:{}: {}", host, port, e.message)
                return ValidationResult.TlsFailed(e.message ?: "TLS probe failed")
            }
        }

        // 3-5. LOGIN + CAPABILITY + LIST via jakarta.mail
        val props = buildJavaMailProperties(host, port, useSsl)
        val session = Session.getInstance(props)
        val protocol = if (useSsl) "imaps" else "imap"

        return try {
            (session.getStore(protocol) as IMAPStore).use { store ->
                try {
                    store.connect(host, port, username, password)
                } catch (e: AuthenticationFailedException) {
                    log.debug("Auth failed for {}@{}: {}", username, host, e.message)
                    return ValidationResult.AuthFailed(e.message ?: "Authentication failed")
                } catch (e: MessagingException) {
                    log.debug("Login rejected for {}@{}: {}", username, host, e.message)
                    return ValidationResult.LoginRejected(e.message ?: "Server rejected login")
                }

                val capabilities = collectCapabilities(store)
                val folderCount = store.defaultFolder.list("*").size.coerceAtMost(FOLDER_LIST_LIMIT)
                ValidationResult.Ok(folderCount = folderCount, capabilities = capabilities)
            }
        } catch (e: MessagingException) {
            log.warn("IMAP probe failed for {}@{}: {}", username, host, e.message)
            ValidationResult.LoginRejected(e.message ?: "IMAP probe failed")
        }
    }

    private fun buildJavaMailProperties(host: String, port: Int, useSsl: Boolean): Properties {
        val protocol = if (useSsl) "imaps" else "imap"
        return Properties().apply {
            put("mail.store.protocol", protocol)
            put("mail.$protocol.host", host)
            put("mail.$protocol.port", port.toString())
            put("mail.$protocol.connectiontimeout", PROBE_TIMEOUT_MS.toString())
            put("mail.$protocol.timeout", PROBE_TIMEOUT_MS.toString())
            put("mail.$protocol.writetimeout", PROBE_TIMEOUT_MS.toString())
            if (useSsl) {
                put("mail.imaps.ssl.enable", "true")
                // Use the JVM default trust store so the probe surfaces real
                // certificate issues to the user — that's exactly what a
                // pre-flight check is for.
            }
        }
    }

    private fun collectCapabilities(store: IMAPStore): List<String> {
        val known = listOf(
            "IMAP4rev1", "IDLE", "STARTTLS", "AUTH=PLAIN", "AUTH=LOGIN",
            "AUTH=XOAUTH2", "LITERAL+", "UIDPLUS", "ID", "MOVE", "QUOTA"
        )
        return known.filter { runCatching { store.hasCapability(it) }.getOrDefault(false) }
    }

    companion object {
        const val PROBE_TIMEOUT_MS = 5_000
        const val FOLDER_LIST_LIMIT = 20
    }
}

/**
 * Input for [EmailConfigValidationService.validate]. Carries the raw
 * password (not an env-var name) so the UI can validate before saving.
 */
data class EmailAccountForm(
    val host: String? = null,
    val port: Int? = null,
    val username: String? = null,
    val password: String? = null,
    val useSsl: Boolean = true
)

/**
 * Outcome of a pre-flight validation probe.
 */
sealed class ValidationResult {
    data class Ok(val folderCount: Int, val capabilities: List<String>) : ValidationResult()
    data class AuthFailed(val serverMessage: String) : ValidationResult()
    data class TlsFailed(val reason: String) : ValidationResult()
    data class Unreachable(val reason: String) : ValidationResult()
    data class LoginRejected(val serverMessage: String) : ValidationResult()
}
