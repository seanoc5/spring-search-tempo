package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.SpringUserService
import com.oconeco.spring_search_tempo.base.model.SpringUserDTO
import com.oconeco.spring_search_tempo.base.util.WebUtils
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * Controller for user profile management.
 * Allows users to view and edit their own profile.
 */
@Controller
@RequestMapping("/profile")
class ProfileController(
    private val springUserService: SpringUserService
) {

    @GetMapping
    fun viewProfile(
        @AuthenticationPrincipal userDetails: UserDetails,
        model: Model
    ): String {
        val user = springUserService.findByLabel(userDetails.username)
        if (user == null) {
            model.addAttribute("error", "User not found")
            return "profile/view"
        }
        model.addAttribute("user", user)
        return "profile/view"
    }

    @GetMapping("/edit")
    fun editProfile(
        @AuthenticationPrincipal userDetails: UserDetails,
        model: Model
    ): String {
        val user = springUserService.findByLabel(userDetails.username)
        if (user == null) {
            return "redirect:/profile"
        }
        model.addAttribute("springUser", user)
        return "profile/edit"
    }

    @PostMapping("/edit")
    fun updateProfile(
        @AuthenticationPrincipal userDetails: UserDetails,
        @ModelAttribute("springUser") @Valid springUserDTO: SpringUserDTO,
        bindingResult: BindingResult,
        redirectAttributes: RedirectAttributes
    ): String {
        if (bindingResult.hasErrors()) {
            return "profile/edit"
        }

        val currentUser = springUserService.findByLabel(userDetails.username)
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute(WebUtils.MSG_ERROR, "User not found")
            return "redirect:/profile"
        }

        // Preserve the original username (label) - users can't change their own username
        springUserDTO.label = currentUser.label
        // Preserve enabled status - users can't disable themselves
        springUserDTO.enabled = currentUser.enabled

        springUserService.update(currentUser.id!!, springUserDTO)
        redirectAttributes.addFlashAttribute(WebUtils.MSG_SUCCESS, "Profile updated successfully")
        return "redirect:/profile"
    }
}
