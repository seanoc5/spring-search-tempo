package com.oconeco.spring_search_tempo.base.controller

import com.oconeco.spring_search_tempo.base.FSFolderService
import com.oconeco.spring_search_tempo.base.model.FSFolderDTO
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
@RequestMapping("/fSFolders")
class FSFolderController(
    private val fSFolderService: FSFolderService
) {

    @GetMapping
    fun list(
        @RequestParam(name = "filter", required = false) filter: String?,
        @RequestParam(name = "showSkipped", required = false, defaultValue = "false") showSkipped: Boolean,
        @SortDefault(sort = ["id"]) @PageableDefault(size = 20) pageable: Pageable,
        model: Model
    ): String {
        val fSFolders = fSFolderService.findAll(filter, pageable, showSkipped)
        model.addAttribute("fSFolders", fSFolders)
        model.addAttribute("filter", filter)
        model.addAttribute("showSkipped", showSkipped)
        model.addAttribute("paginationModel", WebUtils.getPaginationModel(fSFolders))
        return "fSFolder/list"
    }

    @GetMapping("/add")
    fun add(@ModelAttribute("fSFolder") fSFolderDTO: FSFolderDTO): String = "fSFolder/add"

    @PostMapping("/add")
    fun add(
        @ModelAttribute("fSFolder") @Valid fSFolderDTO: FSFolderDTO,
        bindingResult: BindingResult,
        redirectAttributes: RedirectAttributes
    ): String {
        if (bindingResult.hasErrors()) {
            return "fSFolder/add"
        }
        fSFolderService.create(fSFolderDTO)
        redirectAttributes.addFlashAttribute(WebUtils.MSG_SUCCESS,
                WebUtils.getMessage("fSFolder.create.success"))
        return "redirect:/fSFolders"
    }

    @GetMapping("/edit/{id}")
    fun edit(@PathVariable(name = "id") id: Long, model: Model): String {
        model.addAttribute("fSFolder", fSFolderService.get(id))
        return "fSFolder/edit"
    }

    @PostMapping("/edit/{id}")
    fun edit(
        @PathVariable(name = "id") id: Long,
        @ModelAttribute("fSFolder") @Valid fSFolderDTO: FSFolderDTO,
        bindingResult: BindingResult,
        redirectAttributes: RedirectAttributes
    ): String {
        if (bindingResult.hasErrors()) {
            return "fSFolder/edit"
        }
        fSFolderService.update(id, fSFolderDTO)
        redirectAttributes.addFlashAttribute(WebUtils.MSG_SUCCESS,
                WebUtils.getMessage("fSFolder.update.success"))
        return "redirect:/fSFolders"
    }

    @PostMapping("/delete/{id}")
    fun delete(@PathVariable(name = "id") id: Long, redirectAttributes: RedirectAttributes):
            String {
        try {
            fSFolderService.delete(id)
            redirectAttributes.addFlashAttribute(WebUtils.MSG_INFO,
                    WebUtils.getMessage("fSFolder.delete.success"))
        } catch (referencedException: ReferencedException) {
            redirectAttributes.addFlashAttribute(WebUtils.MSG_ERROR,
                    WebUtils.getMessage(referencedException.key!!, referencedException.params))
        }
        return "redirect:/fSFolders"
    }

}
