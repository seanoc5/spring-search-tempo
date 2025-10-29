package com.oconeco.spring_search_tempo.base.controller

import com.oconeco.spring_search_tempo.base.SpringRoleService
import com.oconeco.spring_search_tempo.base.SpringUserService
import com.oconeco.spring_search_tempo.base.model.SpringRoleDTO
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes


@Controller
@RequestMapping("/springRoles")
class SpringRoleController(
    private val springRoleService: SpringRoleService,
    private val springUserService: SpringUserService
) {

    @ModelAttribute
    fun prepareContext(model: Model) {
        model.addAttribute("springUserValues", springUserService.getSpringUserValues())
    }

    @GetMapping
    fun list(@SortDefault(sort = ["id"]) @PageableDefault(size = 20) pageable: Pageable,
            model: Model): String {
        val springRoles = springRoleService.findAll(pageable)
        model.addAttribute("springRoles", springRoles)
        model.addAttribute("paginationModel", WebUtils.getPaginationModel(springRoles))
        return "springRole/list"
    }

    @GetMapping("/add")
    fun add(@ModelAttribute("springRole") springRoleDTO: SpringRoleDTO): String = "springRole/add"

    @PostMapping("/add")
    fun add(
        @ModelAttribute("springRole") @Valid springRoleDTO: SpringRoleDTO,
        bindingResult: BindingResult,
        redirectAttributes: RedirectAttributes
    ): String {
        if (bindingResult.hasErrors()) {
            return "springRole/add"
        }
        springRoleService.create(springRoleDTO)
        redirectAttributes.addFlashAttribute(WebUtils.MSG_SUCCESS,
                WebUtils.getMessage("springRole.create.success"))
        return "redirect:/springRoles"
    }

    @GetMapping("/edit/{id}")
    fun edit(@PathVariable(name = "id") id: Long, model: Model): String {
        model.addAttribute("springRole", springRoleService.get(id))
        return "springRole/edit"
    }

    @PostMapping("/edit/{id}")
    fun edit(
        @PathVariable(name = "id") id: Long,
        @ModelAttribute("springRole") @Valid springRoleDTO: SpringRoleDTO,
        bindingResult: BindingResult,
        redirectAttributes: RedirectAttributes
    ): String {
        if (bindingResult.hasErrors()) {
            return "springRole/edit"
        }
        springRoleService.update(id, springRoleDTO)
        redirectAttributes.addFlashAttribute(WebUtils.MSG_SUCCESS,
                WebUtils.getMessage("springRole.update.success"))
        return "redirect:/springRoles"
    }

    @PostMapping("/delete/{id}")
    fun delete(@PathVariable(name = "id") id: Long, redirectAttributes: RedirectAttributes):
            String {
        springRoleService.delete(id)
        redirectAttributes.addFlashAttribute(WebUtils.MSG_INFO,
                WebUtils.getMessage("springRole.delete.success"))
        return "redirect:/springRoles"
    }

}
