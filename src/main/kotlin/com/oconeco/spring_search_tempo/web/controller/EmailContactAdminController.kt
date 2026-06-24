package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.EmailContactService
import com.oconeco.spring_search_tempo.base.util.WebUtils
import com.oconeco.spring_search_tempo.batch.contactgraph.EmailContactAggregationOrchestrator
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.data.web.SortDefault
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes


/**
 * Issue #146 Phase 1: admin list page for email-contact aggregates.
 *
 * Sortable headers, pagination, and per-account filter widget per
 * `index-list-pages` conventions.
 */
@Controller
@RequestMapping("/admin/email/contacts")
class EmailContactAdminController(
    private val emailContactService: EmailContactService,
    private val emailAccountService: EmailAccountService,
    private val aggregationOrchestrator: EmailContactAggregationOrchestrator
) {

    companion object {
        private val log = LoggerFactory.getLogger(EmailContactAdminController::class.java)
    }

    @GetMapping
    fun list(
        @RequestParam(name = "accountId", required = false) accountId: Long?,
        @SortDefault(sort = ["lastSeen"], direction = org.springframework.data.domain.Sort.Direction.DESC)
        @PageableDefault(size = 50) pageable: Pageable,
        model: Model
    ): String {
        val contacts = emailContactService.findContacts(accountId, pageable)
        val accounts = emailAccountService.findAllForCurrentUser()

        model.addAttribute("contacts", contacts)
        model.addAttribute("accounts", accounts)
        model.addAttribute("selectedAccountId", accountId)
        model.addAttribute("paginationModel", WebUtils.getPaginationModel(contacts))
        return "admin/email-contacts/list"
    }

    @PostMapping("/recompute")
    fun recompute(
        @RequestParam(name = "accountId", required = false) accountId: Long?,
        redirectAttributes: RedirectAttributes
    ): String {
        try {
            if (accountId != null) {
                val execution = aggregationOrchestrator.runForAccount(accountId)
                redirectAttributes.addFlashAttribute(WebUtils.MSG_SUCCESS,
                    "Contact aggregation started (executionId=${execution.id})")
            } else {
                val results = aggregationOrchestrator.runForCurrentUser()
                redirectAttributes.addFlashAttribute(WebUtils.MSG_SUCCESS,
                    "Contact aggregation dispatched for ${results.size} account(s)")
            }
        } catch (e: Exception) {
            log.warn("Failed to dispatch contact aggregation (accountId={}): {}", accountId, e.message, e)
            redirectAttributes.addFlashAttribute(WebUtils.MSG_ERROR,
                "Failed to start contact aggregation: ${e.message}")
        }
        val target = if (accountId != null) "?accountId=$accountId" else ""
        return "redirect:/admin/email/contacts$target"
    }
}
