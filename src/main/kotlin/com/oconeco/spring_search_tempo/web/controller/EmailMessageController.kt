package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.EmailFolderService
import com.oconeco.spring_search_tempo.base.EmailMessageService
import com.oconeco.spring_search_tempo.base.EmailTagService
import com.oconeco.spring_search_tempo.base.domain.FetchStatus
import com.oconeco.spring_search_tempo.base.model.EmailMessageDTO
import com.oconeco.spring_search_tempo.base.util.WebUtils
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.data.web.SortDefault
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * Web controller for viewing and managing email messages.
 */
@Controller
@RequestMapping("/emailMessages")
class EmailMessageController(
    private val emailMessageService: EmailMessageService,
    private val emailAccountService: EmailAccountService,
    private val emailFolderService: EmailFolderService,
    private val emailTagService: EmailTagService
) {

    @ModelAttribute("fetchStatusValues")
    fun fetchStatuses(): Array<FetchStatus> = FetchStatus.entries.toTypedArray()

    @ModelAttribute("emailAccountValues")
    fun emailAccountValues(): Map<Long, Long> = emailAccountService.getEmailAccountValues()

    @ModelAttribute("emailFolderValues")
    fun emailFolderValues(): Map<Long, Long> = emailFolderService.getEmailFolderValues()

    /**
     * List all email messages with pagination, filtering, and sorting.
     */
    @GetMapping
    fun list(
        @RequestParam(name = "filter", required = false) filter: String?,
        @SortDefault(sort = ["receivedDate"], direction = org.springframework.data.domain.Sort.Direction.DESC)
        @PageableDefault(size = 20) pageable: Pageable,
        model: Model,
        request: HttpServletRequest
    ): String {
        val messages = if (filter.isNullOrBlank()) {
            emailMessageService.findAll(pageable)
        } else {
            emailMessageService.search(filter, pageable)
        }

        model.addAttribute("emailMessages", messages)
        model.addAttribute("filter", filter)
        model.addAttribute("paginationModel", WebUtils.getPaginationModel(messages))

        // Handle hx-boost: boosted requests expect full page
        val isHtmx = request.getHeader("HX-Request") == "true"
        val isBoosted = request.getHeader("HX-Boosted") == "true"
        return if (isHtmx && !isBoosted) {
            "emailMessage/list :: table-content"
        } else {
            "emailMessage/list"
        }
    }

    /**
     * View a single email message with tags.
     */
    @GetMapping("/{id}")
    fun view(@PathVariable id: Long, model: Model): String {
        val message = emailMessageService.getWithTags(id)
        val allTags = emailTagService.findAll()
        model.addAttribute("emailMessage", message)
        model.addAttribute("allTags", allTags)
        return "emailMessage/view"
    }

    /**
     * Show add form.
     */
    @GetMapping("/add")
    fun add(@ModelAttribute("emailMessage") emailMessageDTO: EmailMessageDTO): String = "emailMessage/add"

    /**
     * Create a new email message.
     */
    @PostMapping("/add")
    fun create(
        @Valid @ModelAttribute("emailMessage") emailMessageDTO: EmailMessageDTO,
        bindingResult: BindingResult,
        redirectAttributes: RedirectAttributes
    ): String {
        if (bindingResult.hasErrors()) {
            return "emailMessage/add"
        }

        // Set default version
        if (emailMessageDTO.version == null) {
            emailMessageDTO.version = 1L
        }

        val id = emailMessageService.create(emailMessageDTO)
        redirectAttributes.addFlashAttribute(WebUtils.MSG_SUCCESS,
            WebUtils.getMessage("emailMessage.create.success"))
        return "redirect:/emailMessages/$id"
    }

    /**
     * Show edit form.
     */
    @GetMapping("/edit/{id}")
    fun edit(@PathVariable id: Long, model: Model): String {
        model.addAttribute("emailMessage", emailMessageService.get(id))
        return "emailMessage/edit"
    }

    /**
     * Update an email message.
     */
    @PostMapping("/edit/{id}")
    fun update(
        @PathVariable id: Long,
        @Valid @ModelAttribute("emailMessage") emailMessageDTO: EmailMessageDTO,
        bindingResult: BindingResult,
        redirectAttributes: RedirectAttributes
    ): String {
        if (bindingResult.hasErrors()) {
            return "emailMessage/edit"
        }

        emailMessageService.update(id, emailMessageDTO)
        redirectAttributes.addFlashAttribute(WebUtils.MSG_SUCCESS,
            WebUtils.getMessage("emailMessage.update.success"))
        return "redirect:/emailMessages/$id"
    }

    /**
     * Delete an email message.
     */
    @PostMapping("/delete/{id}")
    fun delete(
        @PathVariable id: Long,
        redirectAttributes: RedirectAttributes
    ): String {
        emailMessageService.delete(id)
        redirectAttributes.addFlashAttribute(WebUtils.MSG_SUCCESS,
            WebUtils.getMessage("emailMessage.delete.success"))
        return "redirect:/emailMessages"
    }

    /**
     * Toggle read/unread status.
     */
    @PostMapping("/{id}/toggleRead")
    fun toggleRead(
        @PathVariable id: Long,
        @RequestParam(name = "redirectTo", required = false) redirectTo: String?,
        redirectAttributes: RedirectAttributes
    ): String {
        val newStatus = emailMessageService.toggleReadStatus(id)
        val statusText = if (newStatus) "read" else "unread"
        redirectAttributes.addFlashAttribute(WebUtils.MSG_SUCCESS, "Message marked as $statusText")
        return "redirect:${redirectTo ?: "/emailMessages/$id"}"
    }

    /**
     * Add a tag to a message.
     */
    @PostMapping("/{id}/tags/add")
    fun addTag(
        @PathVariable id: Long,
        @RequestParam(name = "tagId") tagId: Long,
        @RequestParam(name = "redirectTo", required = false) redirectTo: String?,
        redirectAttributes: RedirectAttributes
    ): String {
        emailTagService.addTagToMessage(id, tagId)
        redirectAttributes.addFlashAttribute(WebUtils.MSG_SUCCESS, "Tag added")
        return "redirect:${redirectTo ?: "/emailMessages/$id"}"
    }

    /**
     * Remove a tag from a message.
     */
    @PostMapping("/{id}/tags/remove")
    fun removeTag(
        @PathVariable id: Long,
        @RequestParam(name = "tagId") tagId: Long,
        @RequestParam(name = "redirectTo", required = false) redirectTo: String?,
        redirectAttributes: RedirectAttributes
    ): String {
        emailTagService.removeTagFromMessage(id, tagId)
        redirectAttributes.addFlashAttribute(WebUtils.MSG_SUCCESS, "Tag removed")
        return "redirect:${redirectTo ?: "/emailMessages/$id"}"
    }
}
