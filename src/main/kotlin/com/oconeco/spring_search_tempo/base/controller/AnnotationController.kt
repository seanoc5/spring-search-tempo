package com.oconeco.spring_search_tempo.base.controller

import com.oconeco.spring_search_tempo.base.AnnotationService
import com.oconeco.spring_search_tempo.base.model.AnnotationDTO
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
@RequestMapping("/annotations")
class AnnotationController(
    private val annotationService: AnnotationService
) {

    @GetMapping
    fun list(
        @RequestParam(name = "filter", required = false) filter: String?,
        @SortDefault(sort = ["id"]) @PageableDefault(size = 20) pageable: Pageable,
        model: Model
    ): String {
        val annotations = annotationService.findAll(filter, pageable)
        model.addAttribute("annotations", annotations)
        model.addAttribute("filter", filter)
        model.addAttribute("paginationModel", WebUtils.getPaginationModel(annotations))
        return "annotation/list"
    }

    @GetMapping("/add")
    fun add(@ModelAttribute("annotation") annotationDTO: AnnotationDTO): String = "annotation/add"

    @PostMapping("/add")
    fun add(
        @ModelAttribute("annotation") @Valid annotationDTO: AnnotationDTO,
        bindingResult: BindingResult,
        redirectAttributes: RedirectAttributes
    ): String {
        if (bindingResult.hasErrors()) {
            return "annotation/add"
        }
        annotationService.create(annotationDTO)
        redirectAttributes.addFlashAttribute(WebUtils.MSG_SUCCESS,
                WebUtils.getMessage("annotation.create.success"))
        return "redirect:/annotations"
    }

    @GetMapping("/edit/{id}")
    fun edit(@PathVariable(name = "id") id: Long, model: Model): String {
        model.addAttribute("annotation", annotationService.get(id))
        return "annotation/edit"
    }

    @PostMapping("/edit/{id}")
    fun edit(
        @PathVariable(name = "id") id: Long,
        @ModelAttribute("annotation") @Valid annotationDTO: AnnotationDTO,
        bindingResult: BindingResult,
        redirectAttributes: RedirectAttributes
    ): String {
        if (bindingResult.hasErrors()) {
            return "annotation/edit"
        }
        annotationService.update(id, annotationDTO)
        redirectAttributes.addFlashAttribute(WebUtils.MSG_SUCCESS,
                WebUtils.getMessage("annotation.update.success"))
        return "redirect:/annotations"
    }

    @PostMapping("/delete/{id}")
    fun delete(@PathVariable(name = "id") id: Long, redirectAttributes: RedirectAttributes):
            String {
        annotationService.delete(id)
        redirectAttributes.addFlashAttribute(WebUtils.MSG_INFO,
                WebUtils.getMessage("annotation.delete.success"))
        return "redirect:/annotations"
    }

}
