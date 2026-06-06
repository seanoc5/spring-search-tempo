package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.UserOwnershipService
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import com.oconeco.spring_search_tempo.base.model.EmailAccountDTO
import com.oconeco.spring_search_tempo.base.service.EmailAccountForm
import com.oconeco.spring_search_tempo.base.service.EmailConfigValidationService
import com.oconeco.spring_search_tempo.base.service.ValidationResult
import com.oconeco.spring_search_tempo.batch.emailcrawl.EmailCrawlOrchestrator
import com.oconeco.spring_search_tempo.batch.emailcrawl.ParallelizationConfig
import com.oconeco.spring_search_tempo.web.service.EmailSyncStatusViewService
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * Web controller for managing email accounts and triggering email sync jobs.
 */
@Controller
@RequestMapping("/emailAccounts")
class EmailAccountController(
    private val emailAccountService: EmailAccountService,
    private val emailCrawlOrchestrator: EmailCrawlOrchestrator,
    private val userOwnershipService: UserOwnershipService,
    private val validationService: EmailConfigValidationService,
    private val syncStatusViewService: EmailSyncStatusViewService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ModelAttribute("providers")
    fun providers(): Array<EmailProvider> = EmailProvider.entries.toTypedArray()

    /**
     * List email accounts with summary data.
     * Non-admin users see only their owned accounts; admins can toggle showAll.
     */
    @GetMapping
    fun list(
        @RequestParam(name = "showAll", required = false, defaultValue = "false") showAll: Boolean,
        model: Model
    ): String {
        val isAdmin = userOwnershipService.isCurrentUserAdmin()
        val accounts = if (showAll && isAdmin) {
            emailAccountService.findAllWithSummary()
        } else {
            emailAccountService.findAllWithSummaryForCurrentUser()
        }

        model.addAttribute("emailAccounts", accounts)
        model.addAttribute("showAll", showAll)
        model.addAttribute("isAdmin", isAdmin)
        return "emailAccount/list"
    }

    /**
     * HTMX partial: refresh all account rows (for polling during sync).
     */
    @GetMapping("/rows")
    fun rows(model: Model): String {
        val accounts = emailAccountService.findAllWithSummary()
        model.addAttribute("emailAccounts", accounts)
        return "emailAccount/list :: account-rows"
    }

    /**
     * HTMX partial: refresh a single account row.
     */
    @GetMapping("/{id}/row")
    fun row(@PathVariable id: Long, model: Model): String {
        val account = emailAccountService.getSummary(id)
        model.addAttribute("account", account)
        return "emailAccount/list :: account-row"
    }

    /**
     * View a single email account with sync status.
     */
    @GetMapping("/{id}")
    fun view(@PathVariable id: Long, model: Model): String {
        val account = emailAccountService.get(id)
        model.addAttribute("emailAccount", account)
        model.addAttribute("hasEncryptedPassword", emailAccountService.hasPassword(id))
        return "emailAccount/view"
    }

    /**
     * Live sync-status fragment (issue #68).
     *
     * Returns a Bootstrap card describing the latest `emailQuickSync_<accountId>`
     * JobExecution: color-coded badge, start/duration, and (on failure) the
     * truncated exit message. The fragment re-arms its own HTMX polling only
     * while the execution is non-terminal — see the template for details.
     *
     * Always returns the same `#syncStatusPanel` root element so HTMX
     * `hx-swap="outerHTML"` swaps cleanly per the CLAUDE.md HTMX rules.
     */
    @GetMapping("/{id}/syncStatus")
    fun syncStatus(@PathVariable id: Long, model: Model): String {
        // Touch the account so a bad id returns 404 via NotFoundException.
        val account = emailAccountService.get(id)
        model.addAttribute("accountId", id)
        model.addAttribute("accountEmail", account.email)
        model.addAttribute("syncStatus", syncStatusViewService.load(id))
        return "emailAccount/syncStatus :: panel"
    }

    /**
     * Show add form.
     */
    @GetMapping("/add")
    fun add(model: Model): String {
        model.addAttribute("emailAccount", EmailAccountDTO())
        return "emailAccount/add"
    }

    /**
     * Create a new email account.
     */
    @PostMapping("/add")
    fun create(
        @Valid @ModelAttribute("emailAccount") emailAccountDTO: EmailAccountDTO,
        bindingResult: BindingResult,
        redirectAttributes: RedirectAttributes
    ): String {
        if (bindingResult.hasErrors()) {
            return "emailAccount/add"
        }

        // Check for duplicate email
        if (emailAccountService.emailExists(emailAccountDTO.email!!)) {
            bindingResult.rejectValue("email", "Exists", "Email account already exists")
            return "emailAccount/add"
        }

        // Set default URI based on email
        if (emailAccountDTO.uri.isNullOrBlank()) {
            emailAccountDTO.uri = "email://${emailAccountDTO.email}"
        }

        // Set default version
        if (emailAccountDTO.version == null) {
            emailAccountDTO.version = 1L
        }

        val id = emailAccountService.create(emailAccountDTO)
        redirectAttributes.addFlashAttribute("message", "Email account created successfully")
        return "redirect:/emailAccounts/$id"
    }

    /**
     * Show edit form.
     */
    @GetMapping("/{id}/edit")
    fun edit(@PathVariable id: Long, model: Model): String {
        val account = emailAccountService.get(id)
        model.addAttribute("emailAccount", account)
        model.addAttribute("hasEncryptedPassword", emailAccountService.hasPassword(id))
        return "emailAccount/edit"
    }

    /**
     * Set or clear the IMAP password for an account.
     *
     * The form field is write-only: GETs never echo the stored value. Submitting an empty value
     * clears the stored password (after which the account has no credential and will be skipped
     * by the scheduler until set again). Plaintext is never logged.
     */
    @PostMapping("/{id}/password")
    fun setPassword(
        @PathVariable id: Long,
        @RequestParam("newPassword") password: String,
        @RequestParam(name = "clear", required = false, defaultValue = "false") clear: Boolean,
        redirectAttributes: RedirectAttributes
    ): String {
        val account = emailAccountService.get(id)
        if (clear) {
            emailAccountService.setPassword(id, "")
            redirectAttributes.addFlashAttribute("message", "Stored password cleared for ${account.email}")
        } else if (password.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Password cannot be blank — use the clear action to remove the stored password")
        } else {
            try {
                emailAccountService.setPassword(id, password)
                redirectAttributes.addFlashAttribute("message", "Encrypted password updated for ${account.email}")
            } catch (e: IllegalStateException) {
                log.warn("Failed to store encrypted password for account id={}: {}", id, e.message)
                redirectAttributes.addFlashAttribute(
                    "error",
                    "Cannot store encrypted password: server-side `app.security.encryption-key` " +
                        "(env `APP_ENCRYPTION_KEY`) is not configured. " +
                        "Set it and restart the app, then try again."
                )
            }
        }
        return "redirect:/emailAccounts/$id/edit"
    }

    /**
     * Update an email account.
     */
    @PostMapping("/{id}/edit")
    fun update(
        @PathVariable id: Long,
        @Valid @ModelAttribute("emailAccount") emailAccountDTO: EmailAccountDTO,
        bindingResult: BindingResult,
        @RequestParam(name = "newPassword", required = false) newPassword: String?,
        redirectAttributes: RedirectAttributes
    ): String {
        if (bindingResult.hasErrors()) {
            return "emailAccount/edit"
        }

        emailAccountService.update(id, emailAccountDTO)

        // Thunderbird semantics: blank password input = no change; non-blank = replace stored.
        // Clearing is a separate explicit action (POST /password with clearPassword=true).
        if (!newPassword.isNullOrBlank()) {
            try {
                emailAccountService.setPassword(id, newPassword)
            } catch (e: IllegalStateException) {
                log.warn("Failed to store encrypted password during account update id={}: {}", id, e.message)
                redirectAttributes.addFlashAttribute(
                    "error",
                    "Account fields saved, but password could not be encrypted: server-side `app.security.encryption-key` " +
                        "(env `APP_ENCRYPTION_KEY`) is not configured. Set it and try again."
                )
                return "redirect:/emailAccounts/$id/edit"
            }
        }

        redirectAttributes.addFlashAttribute("message", "Email account updated successfully")
        return "redirect:/emailAccounts/$id"
    }

    /**
     * Delete an email account.
     */
    @PostMapping("/{id}/delete")
    fun delete(
        @PathVariable id: Long,
        redirectAttributes: RedirectAttributes
    ): String {
        val account = emailAccountService.get(id)
        emailAccountService.delete(id)
        redirectAttributes.addFlashAttribute("message", "Email account '${account.email}' deleted")
        return "redirect:/emailAccounts"
    }

    /**
     * Trigger sync for a specific account.
     *
     * @param forceFullSync If true, ignore lastSyncUid and fetch all messages (full recrawl)
     */
    @PostMapping("/{id}/sync")
    fun syncAccount(
        @PathVariable id: Long,
        @RequestParam(name = "forceFullSync", required = false, defaultValue = "false") forceFullSync: Boolean,
        @RequestParam(name = "stepThreads", required = false, defaultValue = "1") stepThreads: Int,
        @RequestParam(name = "itemAsync", required = false, defaultValue = "false") itemAsync: Boolean,
        @RequestParam(name = "asyncThreads", required = false, defaultValue = "4") asyncThreads: Int,
        @RequestParam(name = "chunkSize", required = false, defaultValue = "20") chunkSize: Int,
        @RequestHeader(value = "HX-Request", required = false) hxRequest: String?,
        model: Model,
        redirectAttributes: RedirectAttributes
    ): String {
        val account = emailAccountService.get(id)
        val syncType = if (forceFullSync) "full sync" else "quick sync"
        val parallelConfig = normalizeParallelConfig(stepThreads, itemAsync, asyncThreads, chunkSize)
        val isHtmx = !hxRequest.isNullOrBlank()

        // Pre-flight password guard (issue #55): refuse before dispatching a job
        // that's guaranteed to fail at credential resolution.
        if (!emailAccountService.hasPassword(id)) {
            log.info("Refusing {} for {} — no password set", syncType, account.email)
            redirectAttributes.addFlashAttribute(
                "error",
                "Set a password before syncing — Edit the account first."
            )
            return syncResponse(id, account.email, isHtmx, model)
        }

        try {
            log.info("Starting {} for account: {} ({})", syncType, account.email, parallelConfig)
            emailCrawlOrchestrator.runQuickSyncForAccount(
                accountId = id,
                forceFullSync = forceFullSync,
                parallelConfig = parallelConfig
            )
            // Issue #68: keep the flash brief — the live status panel on the view page
            // (HTMX-polled /emailAccounts/{id}/syncStatus) is the source of truth for
            // execution state. The legacy "Status: JobExecution: id=..., version=0,
            // startTime=null, endTime=null, ... status=STARTING" banner was both misleading
            // (it never refreshed) and rendered twice (duplicate alert in view.html on top
            // of layout.html's flash region).
            redirectAttributes.addFlashAttribute("message",
                "Email $syncType started for ${account.email}.")
        } catch (e: JobExecutionAlreadyRunningException) {
            // Issue #57: another quick sync is in flight (scheduler tick or earlier click).
            log.info("Refusing duplicate {} for {}: {}", syncType, account.email, e.message)
            redirectAttributes.addFlashAttribute("error",
                "A quick sync is already running for ${account.email}. Wait for it to finish before launching another.")
        } catch (e: Exception) {
            log.error("Failed to start {} for account {}: {}", syncType, account.email, e.message, e)
            redirectAttributes.addFlashAttribute("error",
                "Failed to start $syncType: ${e.message}")
        }

        return syncResponse(id, account.email, isHtmx, model)
    }

    /**
     * Issue #70: HTMX-aware response shape for `POST /sync`.
     *
     * - HX-Request → return the live `#syncStatusPanel` fragment so the inline Retry
     *   button (and any other in-page caller) can `hx-swap="outerHTML"` directly,
     *   without a redirect round-trip. The fresh fragment reflects whatever the
     *   orchestrator just did — new in-flight execution, refused-duplicate (still
     *   shows the existing running one), or unchanged FAILED status if the guard
     *   short-circuited.
     * - Normal POST → 302 back to the view page so the flash message renders via
     *   the standard layout.html flash region (preserves the existing UX of the
     *   top-of-page "Sync Now" form-submit).
     */
    private fun syncResponse(id: Long, accountEmail: String?, isHtmx: Boolean, model: Model): String {
        if (!isHtmx) return "redirect:/emailAccounts/$id"
        model.addAttribute("accountId", id)
        model.addAttribute("accountEmail", accountEmail)
        model.addAttribute("syncStatus", syncStatusViewService.load(id))
        return "emailAccount/syncStatus :: panel"
    }

    /**
     * Trigger sync for all enabled accounts.
     *
     * @param forceFullSync If true, ignore lastSyncUid and fetch all messages (full recrawl)
     */
    @PostMapping("/syncAll")
    fun syncAll(
        @RequestParam(name = "forceFullSync", required = false, defaultValue = "false") forceFullSync: Boolean,
        @RequestParam(name = "stepThreads", required = false, defaultValue = "1") stepThreads: Int,
        @RequestParam(name = "itemAsync", required = false, defaultValue = "false") itemAsync: Boolean,
        @RequestParam(name = "asyncThreads", required = false, defaultValue = "4") asyncThreads: Int,
        @RequestParam(name = "chunkSize", required = false, defaultValue = "20") chunkSize: Int,
        redirectAttributes: RedirectAttributes
    ): String {
        val syncType = if (forceFullSync) "full sync" else "quick sync"
        val parallelConfig = normalizeParallelConfig(stepThreads, itemAsync, asyncThreads, chunkSize)

        try {
            log.info("Starting {} for all enabled accounts ({})", syncType, parallelConfig)
            val results = emailCrawlOrchestrator.runQuickSync(
                forceFullSync = forceFullSync,
                stepThreads = parallelConfig.stepThreads,
                itemAsync = parallelConfig.itemAsync,
                asyncThreads = parallelConfig.asyncThreads,
                chunkSize = parallelConfig.chunkSize
            )

            if (results["status"] == "disabled") {
                redirectAttributes.addFlashAttribute("error",
                    "Email crawling is disabled in configuration")
            } else {
                val summary = results.entries.joinToString(", ") { "${it.key}: ${it.value}" }
                redirectAttributes.addFlashAttribute("message",
                    "Email $syncType started (${parallelConfig.modeName}, chunkSize=${parallelConfig.chunkSize}). Results: $summary")
            }
        } catch (e: Exception) {
            log.error("Failed to start {}: {}", syncType, e.message, e)
            redirectAttributes.addFlashAttribute("error",
                "Failed to start $syncType: ${e.message}")
        }

        return "redirect:/emailAccounts"
    }

    /**
     * Clear error status for an account.
     */
    @PostMapping("/{id}/clearError")
    fun clearError(
        @PathVariable id: Long,
        redirectAttributes: RedirectAttributes
    ): String {
        emailAccountService.clearError(id)
        redirectAttributes.addFlashAttribute("message", "Error cleared")
        return "redirect:/emailAccounts/$id"
    }

    /**
     * Pre-flight validation of an IMAP connection. The password is supplied
     * inline by the user (not read from the env var) so they can verify
     * before saving the account.
     *
     * HX-Request → returns inline result fragment. Otherwise → renders the
     * full edit page with the result panel populated (per CLAUDE.md HTMX
     * response-shape rules).
     */
    @PostMapping("/validate")
    fun validate(
        @RequestParam(required = false) imapHost: String?,
        @RequestParam(required = false) imapPort: Int?,
        @RequestParam(required = false) email: String?,
        @RequestParam(required = false) validationPassword: String?,
        @RequestParam(required = false) accountId: Long?,
        @RequestParam(required = false, defaultValue = "true") useSsl: Boolean,
        @RequestHeader(value = "HX-Request", required = false) hxRequest: String?,
        model: Model
    ): String {
        // Thunderbird semantics: if the user left the password field blank AND this is an existing
        // account with a stored encrypted password, test the stored one. They came to edit a saved
        // account, so the saved credential is the source of truth.
        val effectivePassword = validationPassword?.takeIf { it.isNotBlank() }
            ?: accountId?.let { runCatching { emailAccountService.getPassword(it) }.getOrNull() }

        val form = EmailAccountForm(
            host = imapHost,
            port = imapPort,
            username = email,
            password = effectivePassword,
            useSsl = useSsl
        )
        val result = validationService.validate(form)
        model.addAttribute("validationResult", toViewModel(result))

        return if (!hxRequest.isNullOrBlank()) {
            "emailAccount/validate :: result"
        } else {
            model.addAttribute("emailAccount", EmailAccountDTO())
            "emailAccount/add"
        }
    }

    private fun toViewModel(result: ValidationResult): ValidationResultView = when (result) {
        is ValidationResult.Ok -> ValidationResultView(
            outcome = "OK",
            severity = "success",
            heading = "Connection successful",
            message = "Discovered ${result.folderCount} folder(s).",
            capabilities = result.capabilities
        )
        is ValidationResult.AuthFailed -> ValidationResultView(
            outcome = "AUTH_FAILED",
            severity = "danger",
            heading = "Authentication failed",
            message = result.serverMessage
        )
        is ValidationResult.TlsFailed -> ValidationResultView(
            outcome = "TLS_FAILED",
            severity = "danger",
            heading = "TLS handshake failed",
            message = result.reason
        )
        is ValidationResult.Unreachable -> ValidationResultView(
            outcome = "UNREACHABLE",
            severity = "warning",
            heading = "Server unreachable",
            message = result.reason
        )
        is ValidationResult.LoginRejected -> ValidationResultView(
            outcome = "LOGIN_REJECTED",
            severity = "danger",
            heading = "Server rejected login",
            message = result.serverMessage
        )
    }

    private fun normalizeParallelConfig(
        stepThreads: Int,
        itemAsync: Boolean,
        asyncThreads: Int,
        chunkSize: Int
    ): ParallelizationConfig {
        val normalizedStepThreads = stepThreads.coerceIn(1, 16)
        val normalizedAsyncThreads = asyncThreads.coerceIn(1, 32)
        val normalizedChunkSize = chunkSize.coerceIn(10, 500)
        return ParallelizationConfig(
            stepThreads = normalizedStepThreads,
            itemAsync = itemAsync,
            asyncThreads = normalizedAsyncThreads,
            chunkSize = normalizedChunkSize
        )
    }
}

data class ValidationResultView(
    val outcome: String,
    val severity: String,
    val heading: String,
    val message: String,
    val capabilities: List<String>? = null
)
