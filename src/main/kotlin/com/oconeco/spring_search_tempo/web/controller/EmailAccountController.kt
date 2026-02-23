package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import com.oconeco.spring_search_tempo.base.model.EmailAccountDTO
import com.oconeco.spring_search_tempo.batch.emailcrawl.EmailCrawlOrchestrator
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
    private val emailCrawlOrchestrator: EmailCrawlOrchestrator
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ModelAttribute("providers")
    fun providers(): Array<EmailProvider> = EmailProvider.entries.toTypedArray()

    /**
     * List all email accounts.
     */
    @GetMapping
    fun list(model: Model): String {
        val accounts = emailAccountService.findAll()
        val syncStatus = emailCrawlOrchestrator.getSyncStatus()
            .associateBy { it.accountId }

        model.addAttribute("emailAccounts", accounts)
        model.addAttribute("syncStatus", syncStatus)
        return "emailAccount/list"
    }

    /**
     * View a single email account with sync status.
     */
    @GetMapping("/{id}")
    fun view(@PathVariable id: Long, model: Model): String {
        val account = emailAccountService.get(id)
        model.addAttribute("emailAccount", account)
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
     * Trigger quick sync for a specific account.
     */
    @PostMapping("/{id}/sync")
    fun syncAccount(
        @PathVariable id: Long,
        redirectAttributes: RedirectAttributes
    ): String {
        val account = emailAccountService.get(id)

        try {
            log.info("Starting quick sync for account: {}", account.email)
            val status = emailCrawlOrchestrator.runQuickSyncForAccount(id)
            redirectAttributes.addFlashAttribute("message",
                "Email sync started for ${account.email}. Status: $status")
        } catch (e: Exception) {
            log.error("Failed to start sync for account {}: {}", account.email, e.message, e)
            redirectAttributes.addFlashAttribute("error",
                "Failed to start sync: ${e.message}")
        }

        return "redirect:/emailAccounts/$id"
    }

    /**
     * Trigger quick sync for all enabled accounts.
     */
    @PostMapping("/syncAll")
    fun syncAll(redirectAttributes: RedirectAttributes): String {
        try {
            log.info("Starting quick sync for all enabled accounts")
            val results = emailCrawlOrchestrator.runQuickSync()

            if (results["status"] == "disabled") {
                redirectAttributes.addFlashAttribute("error",
                    "Email crawling is disabled in configuration")
            } else {
                val summary = results.entries.joinToString(", ") { "${it.key}: ${it.value}" }
                redirectAttributes.addFlashAttribute("message",
                    "Email sync completed. Results: $summary")
            }
        } catch (e: Exception) {
            log.error("Failed to start sync all: {}", e.message, e)
            redirectAttributes.addFlashAttribute("error",
                "Failed to start sync: ${e.message}")
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
}
