package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.UserOwnershipService
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import com.oconeco.spring_search_tempo.base.model.EmailAccountDTO
import com.oconeco.spring_search_tempo.batch.emailcrawl.EmailCrawlOrchestrator
import com.oconeco.spring_search_tempo.batch.emailcrawl.ParallelizationConfig
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
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
    private val userOwnershipService: UserOwnershipService
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
        model.addAttribute("credentialEnvVarSet", isEnvVarSet(account.credentialEnvVar))
        return "emailAccount/view"
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
        model.addAttribute("credentialEnvVarSet", isEnvVarSet(account.credentialEnvVar))
        return "emailAccount/edit"
    }

    /**
     * Update an email account.
     */
    @PostMapping("/{id}/edit")
    fun update(
        @PathVariable id: Long,
        @Valid @ModelAttribute("emailAccount") emailAccountDTO: EmailAccountDTO,
        bindingResult: BindingResult,
        redirectAttributes: RedirectAttributes
    ): String {
        if (bindingResult.hasErrors()) {
            return "emailAccount/edit"
        }

        emailAccountService.update(id, emailAccountDTO)
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
        redirectAttributes: RedirectAttributes
    ): String {
        val account = emailAccountService.get(id)
        val syncType = if (forceFullSync) "full sync" else "quick sync"
        val parallelConfig = normalizeParallelConfig(stepThreads, itemAsync, asyncThreads, chunkSize)

        try {
            log.info("Starting {} for account: {} ({})", syncType, account.email, parallelConfig)
            val status = emailCrawlOrchestrator.runQuickSyncForAccount(
                accountId = id,
                forceFullSync = forceFullSync,
                parallelConfig = parallelConfig
            )
            redirectAttributes.addFlashAttribute("message",
                "Email $syncType started for ${account.email}. Mode: ${parallelConfig.modeName}, chunkSize=${parallelConfig.chunkSize}. Status: $status")
        } catch (e: Exception) {
            log.error("Failed to start {} for account {}: {}", syncType, account.email, e.message, e)
            redirectAttributes.addFlashAttribute("error",
                "Failed to start $syncType: ${e.message}")
        }

        return "redirect:/emailAccounts/$id"
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

    private fun isEnvVarSet(envVarName: String?): Boolean {
        if (envVarName.isNullOrBlank()) return false
        return System.getenv(envVarName) != null
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
