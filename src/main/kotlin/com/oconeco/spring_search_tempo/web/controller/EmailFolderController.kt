package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.EmailFolderService
import com.oconeco.spring_search_tempo.base.service.EmailFolderSyncService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * Per-account folder management UI for IMAP accounts.
 *
 * Lets the user trigger `LIST *` enumeration and pick which folders should
 * be visited by the sync job (issue #3).
 */
@Controller
@RequestMapping("/emailAccounts/{accountId}/folders")
class EmailFolderController(
    private val emailAccountService: EmailAccountService,
    private val emailFolderService: EmailFolderService,
    private val emailFolderSyncService: EmailFolderSyncService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping
    fun list(@PathVariable accountId: Long, model: Model): String {
        val account = emailAccountService.get(accountId)
        val folders = emailFolderService.findByAccount(accountId)
            .sortedWith(compareBy({ it.path ?: it.folderName ?: "" }))
        model.addAttribute("emailAccount", account)
        model.addAttribute("folders", folders)
        return "emailFolder/list"
    }

    /**
     * Run `LIST *` against the account and upsert folder rows.
     */
    @PostMapping("/refresh")
    fun refresh(
        @PathVariable accountId: Long,
        redirectAttributes: RedirectAttributes
    ): String {
        val account = emailAccountService.get(accountId)
        try {
            val ids = emailFolderSyncService.enumerateFolders(accountId)
            redirectAttributes.addFlashAttribute(
                "message",
                "Enumerated ${ids.size} folder(s) for ${account.email}."
            )
        } catch (e: Exception) {
            log.error("Folder enumeration failed for {}: {}", account.email, e.message, e)
            redirectAttributes.addFlashAttribute(
                "error",
                "Folder enumeration failed: ${e.message}"
            )
        }
        return "redirect:/emailAccounts/$accountId/folders"
    }

    /**
     * Bulk update of which folders are sync targets.
     *
     * The form posts a `syncEnabledFolderIds` checkbox set; any folder not in
     * the set is treated as disabled. We compare against current state so
     * re-enabling a folder triggers the lastSyncUid reset (see
     * `EmailFolderService.setSyncEnabled`).
     */
    @PostMapping("/update")
    fun update(
        @PathVariable accountId: Long,
        @RequestParam(name = "syncEnabledFolderIds", required = false) syncEnabledFolderIds: List<Long>?,
        redirectAttributes: RedirectAttributes
    ): String {
        val enabledSet = syncEnabledFolderIds?.toSet() ?: emptySet()
        val folders = emailFolderService.findByAccount(accountId)

        var enabled = 0
        var disabled = 0
        folders.forEach { folder ->
            val id = folder.id ?: return@forEach
            val shouldEnable = enabledSet.contains(id)
            if (shouldEnable != folder.syncEnabled) {
                emailFolderService.setSyncEnabled(id, shouldEnable)
                if (shouldEnable) enabled++ else disabled++
            }
        }

        redirectAttributes.addFlashAttribute(
            "message",
            "Updated folder selection: $enabled enabled, $disabled disabled."
        )
        return "redirect:/emailAccounts/$accountId/folders"
    }
}
