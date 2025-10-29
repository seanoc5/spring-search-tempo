package com.oconeco.spring_search_tempo.base.controller

import com.oconeco.spring_search_tempo.base.SpringUserService
import com.oconeco.spring_search_tempo.base.model.SpringUserDTO
import com.oconeco.spring_search_tempo.base.util.ReferencedException
import com.oconeco.spring_search_tempo.base.util.WebUtils
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.data.web.SortDefault
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes


@Controller
@RequestMapping("/springUsers")
class SpringUserController(
    private val springUserService: SpringUserService
) {

    @GetMapping
    fun list(
        @RequestParam(name = "filter", required = false) filter: String?,
        @SortDefault(sort = ["id"]) @PageableDefault(size = 20) pageable: Pageable,
        model: Model
    ): String {
        val springUsers = springUserService.findAll(filter, pageable)
        model.addAttribute("springUsers", springUsers)
        model.addAttribute("filter", filter)
        model.addAttribute("paginationModel", WebUtils.getPaginationModel(springUsers))
        return "springUser/list"
    }

    @GetMapping("/add")
    fun add(@ModelAttribute("springUser") springUserDTO: SpringUserDTO): String = "springUser/add"

    @PostMapping("/add")
    fun add(
        @ModelAttribute("springUser") @Valid springUserDTO: SpringUserDTO,
        bindingResult: BindingResult,
        redirectAttributes: RedirectAttributes
    ): String {
        if (bindingResult.hasErrors()) {
            return "springUser/add"
        }
        springUserService.create(springUserDTO)
        redirectAttributes.addFlashAttribute(WebUtils.MSG_SUCCESS,
                WebUtils.getMessage("springUser.create.success"))
        return "redirect:/springUsers"
    }

    @GetMapping("/edit/{id}")
    fun edit(@PathVariable(name = "id") id: Long, model: Model): String {
        model.addAttribute("springUser", springUserService.get(id))
        return "springUser/edit"
    }

    @PostMapping("/edit/{id}")
    fun edit(
        @PathVariable(name = "id") id: Long,
        @ModelAttribute("springUser") @Valid springUserDTO: SpringUserDTO,
        bindingResult: BindingResult,
        redirectAttributes: RedirectAttributes
    ): String {
        if (bindingResult.hasErrors()) {
            return "springUser/edit"
        }
        springUserService.update(id, springUserDTO)
        redirectAttributes.addFlashAttribute(WebUtils.MSG_SUCCESS,
                WebUtils.getMessage("springUser.update.success"))
        return "redirect:/springUsers"
    }

    @PostMapping("/delete/{id}")
    fun delete(@PathVariable(name = "id") id: Long, redirectAttributes: RedirectAttributes):
            String {
        try {
            springUserService.delete(id)
            redirectAttributes.addFlashAttribute(WebUtils.MSG_INFO,
                    WebUtils.getMessage("springUser.delete.success"))
        } catch (referencedException: ReferencedException) {
            redirectAttributes.addFlashAttribute(WebUtils.MSG_ERROR,
                    WebUtils.getMessage(referencedException.key!!, referencedException.params))
        }
        return "redirect:/springUsers"
    }

}
