package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.OneDriveAccountService
import com.oconeco.spring_search_tempo.base.config.OneDriveConfiguration
import com.oconeco.spring_search_tempo.base.model.OneDriveAccountDTO
import com.oconeco.spring_search_tempo.base.service.OneDriveConnectionService
import com.oconeco.spring_search_tempo.batch.onedrivesync.OneDriveSyncOrchestrator
import jakarta.servlet.http.HttpSession
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.util.UUID


/**
 * Web controller for managing OneDrive accounts and triggering sync jobs.
 */
@Controller
@RequestMapping("/oneDriveAccounts")
class OneDriveAccountController(
    private val accountService: OneDriveAccountService,
    private val connectionService: OneDriveConnectionService,
    private val syncOrchestrator: OneDriveSyncOrchestrator,
    private val config: OneDriveConfiguration
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * List all OneDrive accounts with summary data.
     */
    @GetMapping
    fun list(model: Model): String {
        val accounts = accountService.findAllWithSummary()
        model.addAttribute("oneDriveAccounts", accounts)
        model.addAttribute("oneDriveEnabled", config.enabled)
        return "oneDriveAccount/list"
    }

    /**
     * HTMX partial: refresh all account rows (for polling during sync).
     */
    @GetMapping("/rows")
    fun rows(model: Model): String {
        val accounts = accountService.findAllWithSummary()
        model.addAttribute("oneDriveAccounts", accounts)
        return "oneDriveAccount/list :: account-rows"
    }

    /**
     * HTMX partial: refresh a single account row.
     */
    @GetMapping("/{id}/row")
    fun row(@PathVariable id: Long, model: Model): String {
        val account = accountService.getSummary(id)
        model.addAttribute("account", account)
        return "oneDriveAccount/list :: account-row"
    }

    /**
     * View a single OneDrive account.
     */
    @GetMapping("/{id}")
    fun view(@PathVariable id: Long, model: Model): String {
        val account = accountService.getSummary(id)
        model.addAttribute("account", account)
        return "oneDriveAccount/view"
    }

    /**
     * Initiate OAuth2 PKCE flow - redirects to Microsoft login.
     * Stores PKCE code verifier and state in session.
     */
    @GetMapping("/connect")
    fun connect(session: HttpSession): String {
        if (!config.enabled) {
            return "redirect:/oneDriveAccounts?error=OneDrive+integration+is+disabled"
        }

        val state = UUID.randomUUID().toString()
        val codeVerifier = connectionService.generateCodeVerifier()

        session.setAttribute("onedrive_state", state)
        session.setAttribute("onedrive_code_verifier", codeVerifier)

        val authUrl = connectionService.buildAuthorizationUrl(state, codeVerifier)
        log.info("Redirecting to Microsoft OAuth2 login")
        return "redirect:$authUrl"
    }

    /**
     * OAuth2 callback - exchanges code for tokens, creates/updates account.
     */
    @GetMapping("/callback")
    fun callback(
        @RequestParam("code") code: String,
        @RequestParam("state") state: String,
        session: HttpSession,
        redirectAttributes: RedirectAttributes
    ): String {
        // Verify state to prevent CSRF
        val savedState = session.getAttribute("onedrive_state") as? String
        if (savedState != state) {
            redirectAttributes.addFlashAttribute("error", "OAuth2 state mismatch - possible CSRF attack")
            return "redirect:/oneDriveAccounts"
        }

        val codeVerifier = session.getAttribute("onedrive_code_verifier") as? String
            ?: run {
                redirectAttributes.addFlashAttribute("error", "Missing PKCE code verifier in session")
                return "redirect:/oneDriveAccounts"
            }

        // Clean up session
        session.removeAttribute("onedrive_state")
        session.removeAttribute("onedrive_code_verifier")

        try {
            // Exchange code for tokens
            val (accessToken, encryptedRefreshToken, profile) =
                connectionService.exchangeCodeAndEncryptTokens(code, codeVerifier)

            // Check if account already exists
            val existing = accountService.findByMicrosoftAccountId(profile.microsoftAccountId)

            val accountId: Long
            if (existing != null) {
                // Update existing account
                accountId = existing.id!!
                accountService.storeTokens(accountId, encryptedRefreshToken)
                accountService.clearError(accountId)
                redirectAttributes.addFlashAttribute("message",
                    "OneDrive account reconnected: ${profile.email ?: profile.displayName}")
            } else {
                // Create new account
                val dto = OneDriveAccountDTO().apply {
                    this.microsoftAccountId = profile.microsoftAccountId
                    this.email = profile.email
                    this.displayName = profile.displayName
                    this.clientId = config.clientId
                    this.encryptedRefreshToken = encryptedRefreshToken
                    this.enabled = true
                    this.uri = "onedrive://${profile.microsoftAccountId}"
                    this.label = profile.displayName ?: profile.email
                    this.version = 0L
                }
                accountId = accountService.create(dto)
                redirectAttributes.addFlashAttribute("message",
                    "OneDrive account connected: ${profile.email ?: profile.displayName}")
            }

            // Fetch drive info using the access token
            try {
                val client = connectionService.createGraphClient(accessToken)
                val drive = client.me().drive().get()
                accountService.updateDriveInfo(
                    id = accountId,
                    driveId = drive.id,
                    driveType = drive.driveType,
                    quotaTotal = drive.quota?.total,
                    quotaUsed = drive.quota?.used
                )
            } catch (e: Exception) {
                log.warn("Failed to fetch drive info for account {}: {}", accountId, e.message)
            }

            return "redirect:/oneDriveAccounts/$accountId"

        } catch (e: Exception) {
            log.error("OAuth2 callback failed: {}", e.message, e)
            redirectAttributes.addFlashAttribute("error", "Failed to connect OneDrive: ${e.message}")
            return "redirect:/oneDriveAccounts"
        }
    }

    /**
     * Trigger sync for a specific account.
     */
    @PostMapping("/{id}/sync")
    fun syncAccount(
        @PathVariable id: Long,
        @RequestParam(name = "forceFullSync", required = false, defaultValue = "false") forceFullSync: Boolean,
        redirectAttributes: RedirectAttributes
    ): String {
        val account = accountService.get(id)
        val syncType = if (forceFullSync) "full sync" else "sync"

        try {
            log.info("Starting OneDrive {} for account: {}", syncType, account.email)
            val status = syncOrchestrator.runSyncForAccount(id, forceFullSync)
            redirectAttributes.addFlashAttribute("message",
                "OneDrive $syncType completed for ${account.email}. Status: $status")
        } catch (e: Exception) {
            log.error("Failed to start OneDrive {} for account {}: {}", syncType, account.email, e.message, e)
            redirectAttributes.addFlashAttribute("error",
                "Failed to start $syncType: ${e.message}")
        }

        return "redirect:/oneDriveAccounts/$id"
    }

    /**
     * Disconnect (clear tokens and disable) a OneDrive account.
     */
    @PostMapping("/{id}/disconnect")
    fun disconnect(
        @PathVariable id: Long,
        redirectAttributes: RedirectAttributes
    ): String {
        val account = accountService.get(id)
        try {
            accountService.storeTokens(id, "")
            val dto = accountService.get(id)
            dto.enabled = false
            accountService.update(id, dto)
            redirectAttributes.addFlashAttribute("message", "OneDrive account disconnected: ${account.email}")
        } catch (e: Exception) {
            log.error("Failed to disconnect OneDrive account {}: {}", id, e.message, e)
            redirectAttributes.addFlashAttribute("error", "Failed to disconnect: ${e.message}")
        }
        return "redirect:/oneDriveAccounts"
    }

    /**
     * Delete a OneDrive account and all its data.
     */
    @PostMapping("/{id}/delete")
    fun delete(
        @PathVariable id: Long,
        redirectAttributes: RedirectAttributes
    ): String {
        val account = accountService.get(id)
        accountService.delete(id)
        redirectAttributes.addFlashAttribute("message", "OneDrive account '${account.email}' deleted")
        return "redirect:/oneDriveAccounts"
    }

    /**
     * Clear error status for an account.
     */
    @PostMapping("/{id}/clearError")
    fun clearError(
        @PathVariable id: Long,
        redirectAttributes: RedirectAttributes
    ): String {
        accountService.clearError(id)
        redirectAttributes.addFlashAttribute("message", "Error cleared")
        return "redirect:/oneDriveAccounts/$id"
    }
}
