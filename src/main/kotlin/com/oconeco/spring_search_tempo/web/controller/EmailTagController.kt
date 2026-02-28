package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.EmailTagService
import com.oconeco.spring_search_tempo.base.model.EmailTagDTO
import com.oconeco.spring_search_tempo.base.util.WebUtils
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.data.web.SortDefault
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.support.RedirectAttributes


/**
 * Web controller for managing email tags.
 */
@Controller
@RequestMapping("/emailTags")
class EmailTagController(
    private val emailTagService: EmailTagService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * List all tags with message counts.
     */
    @GetMapping
    fun list(model: Model): String {
        val tags = emailTagService.findAllWithCounts()
        model.addAttribute("emailTags", tags)
        return "emailTag/list"
    }

    /**
     * Show add form.
     */
    @GetMapping("/add")
    fun add(model: Model): String {
        model.addAttribute("emailTag", EmailTagDTO())
        return "emailTag/add"
    }

    /**
     * Create a new tag.
     */
    @PostMapping("/add")
    fun create(
        @Valid @ModelAttribute("emailTag") emailTagDTO: EmailTagDTO,
        bindingResult: BindingResult,
        redirectAttributes: RedirectAttributes
    ): String {
        if (bindingResult.hasErrors()) {
            return "emailTag/add"
        }

        try {
            // Check for duplicate name
            val name = emailTagDTO.name
            if (name != null && emailTagService.nameExists(name)) {
                bindingResult.rejectValue("name", "Exists", "Tag with this name already exists")
                return "emailTag/add"
            }

            val id = emailTagService.create(emailTagDTO)
            redirectAttributes.addFlashAttribute(WebUtils.MSG_SUCCESS, "Tag '${emailTagDTO.name}' created successfully")
            return "redirect:/emailTags"
        } catch (e: IllegalArgumentException) {
            bindingResult.rejectValue("name", "Invalid", e.message ?: "Invalid tag name")
            return "emailTag/add"
        }
    }

    /**
     * Show edit form.
     */
    @GetMapping("/{id}/edit")
    fun edit(@PathVariable id: Long, model: Model, redirectAttributes: RedirectAttributes): String {
        val tag = emailTagService.get(id)

        // Prevent editing system tags
        if (tag.isSystem) {
            redirectAttributes.addFlashAttribute(WebUtils.MSG_ERROR, "System tags cannot be edited")
            return "redirect:/emailTags"
        }

        model.addAttribute("emailTag", tag)
        return "emailTag/edit"
    }

    /**
     * Check if tag name already exists.
     */
    private fun validateUniqueName(name: String?, id: Long?, bindingResult: BindingResult): Boolean {
        if (name.isNullOrBlank()) return true
        val existing = emailTagService.findByName(name)
        if (existing != null && existing.id != id) {
            bindingResult.rejectValue("name", "Exists", "Tag with this name already exists")
            return false
        }
        return true
    }

    /**
     * Update a tag.
     */
    @PostMapping("/{id}/edit")
    fun update(
        @PathVariable id: Long,
        @Valid @ModelAttribute("emailTag") emailTagDTO: EmailTagDTO,
        bindingResult: BindingResult,
        redirectAttributes: RedirectAttributes
    ): String {
        if (bindingResult.hasErrors()) {
            return "emailTag/edit"
        }

        try {
            emailTagService.update(id, emailTagDTO)
            redirectAttributes.addFlashAttribute(WebUtils.MSG_SUCCESS, "Tag updated successfully")
            return "redirect:/emailTags"
        } catch (e: IllegalArgumentException) {
            bindingResult.reject("Invalid", e.message ?: "Invalid operation")
            return "emailTag/edit"
        }
    }

    /**
     * Delete a tag.
     */
    @PostMapping("/{id}/delete")
    fun delete(
        @PathVariable id: Long,
        redirectAttributes: RedirectAttributes
    ): String {
        try {
            val tag = emailTagService.get(id)
            emailTagService.delete(id)
            redirectAttributes.addFlashAttribute(WebUtils.MSG_SUCCESS, "Tag '${tag.name ?: id}' deleted")
        } catch (e: IllegalArgumentException) {
            redirectAttributes.addFlashAttribute(WebUtils.MSG_ERROR, e.message ?: "Cannot delete tag")
        }
        return "redirect:/emailTags"
    }

    /**
     * View messages with a specific tag.
     */
    @GetMapping("/{id}/messages")
    fun messages(
        @PathVariable id: Long,
        @SortDefault(sort = ["receivedDate"], direction = org.springframework.data.domain.Sort.Direction.DESC)
        @PageableDefault(size = 20) pageable: Pageable,
        model: Model
    ): String {
        val tag = emailTagService.get(id)
        val messages = emailTagService.findMessagesWithTag(id, pageable)

        model.addAttribute("emailTag", tag)
        model.addAttribute("emailMessages", messages)
        model.addAttribute("paginationModel", WebUtils.getPaginationModel(messages))
        return "emailTag/messages"
    }

}
