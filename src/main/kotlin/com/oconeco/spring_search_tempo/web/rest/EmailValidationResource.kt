package com.oconeco.spring_search_tempo.web.rest

import com.oconeco.spring_search_tempo.base.service.EmailAccountForm
import com.oconeco.spring_search_tempo.base.service.EmailConfigValidationService
import com.oconeco.spring_search_tempo.base.service.ValidationResult
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Pre-flight validation REST endpoint for IMAP email account configuration.
 *
 * Pairs with `EmailConfigValidationService` — see that service for probe
 * semantics. This endpoint is idempotent and writes nothing.
 */
@RestController
@RequestMapping("/api/email/accounts")
class EmailValidationResource(
    private val validationService: EmailConfigValidationService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/validate")
    fun validate(@RequestBody form: EmailAccountForm): ResponseEntity<EmailValidationResponse> {
        log.info("Validating IMAP config for {}@{}:{}", form.username, form.host, form.port)
        val result = validationService.validate(form)
        val body = EmailValidationResponse.from(result)
        return if (result is ValidationResult.Ok) {
            ResponseEntity.ok(body)
        } else {
            ResponseEntity.badRequest().body(body)
        }
    }
}

/**
 * Wire shape for validation responses. `outcome` is the discriminator;
 * `detail` carries the human-readable message or capability list.
 */
data class EmailValidationResponse(
    val outcome: String,
    val message: String,
    val folderCount: Int? = null,
    val capabilities: List<String>? = null
) {
    companion object {
        fun from(result: ValidationResult): EmailValidationResponse = when (result) {
            is ValidationResult.Ok -> EmailValidationResponse(
                outcome = "OK",
                message = "Connected — discovered ${result.folderCount} folder(s)",
                folderCount = result.folderCount,
                capabilities = result.capabilities
            )
            is ValidationResult.AuthFailed -> EmailValidationResponse(
                outcome = "AUTH_FAILED",
                message = "Authentication failed: ${result.serverMessage}"
            )
            is ValidationResult.TlsFailed -> EmailValidationResponse(
                outcome = "TLS_FAILED",
                message = "TLS handshake failed: ${result.reason}"
            )
            is ValidationResult.Unreachable -> EmailValidationResponse(
                outcome = "UNREACHABLE",
                message = "Server unreachable: ${result.reason}"
            )
            is ValidationResult.LoginRejected -> EmailValidationResponse(
                outcome = "LOGIN_REJECTED",
                message = "Server rejected login: ${result.serverMessage}"
            )
        }
    }
}
