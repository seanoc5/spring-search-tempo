package com.oconeco.spring_search_tempo.base.controller

import com.oconeco.spring_search_tempo.base.FSFileService
import com.oconeco.spring_search_tempo.base.FSFolderService
import com.oconeco.spring_search_tempo.base.model.FSFileDTO
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
@RequestMapping("/fSFiles")
class FSFileController(
    private val fSFileService: FSFileService,
    private val fSFolderService: FSFolderService
) {

    @ModelAttribute
    fun prepareContext(model: Model) {
        model.addAttribute("fsFolderValues", fSFolderService.getFSFolderValues())
    }

    @GetMapping
    fun list(
        @RequestParam(name = "filter", required = false) filter: String?,
        @SortDefault(sort = ["id"]) @PageableDefault(size = 20) pageable: Pageable,
        model: Model
    ): String {
        val fSFiles = fSFileService.findAll(filter, pageable)
        model.addAttribute("fSFiles", fSFiles)
        model.addAttribute("filter", filter)
        model.addAttribute("paginationModel", WebUtils.getPaginationModel(fSFiles))
        return "fSFile/list"
    }

    @GetMapping("/add")
    fun add(@ModelAttribute("fSFile") fSFileDTO: FSFileDTO): String = "fSFile/add"

    @PostMapping("/add")
    fun add(
        @ModelAttribute("fSFile") @Valid fSFileDTO: FSFileDTO,
        bindingResult: BindingResult,
        redirectAttributes: RedirectAttributes
    ): String {
        if (bindingResult.hasErrors()) {
            return "fSFile/add"
        }
        fSFileService.create(fSFileDTO)
        redirectAttributes.addFlashAttribute(WebUtils.MSG_SUCCESS,
                WebUtils.getMessage("fSFile.create.success"))
        return "redirect:/fSFiles"
    }

    @GetMapping("/edit/{id}")
    fun edit(@PathVariable(name = "id") id: Long, model: Model): String {
        model.addAttribute("fSFile", fSFileService.get(id))
        return "fSFile/edit"
    }

    @PostMapping("/edit/{id}")
    fun edit(
        @PathVariable(name = "id") id: Long,
        @ModelAttribute("fSFile") @Valid fSFileDTO: FSFileDTO,
        bindingResult: BindingResult,
        redirectAttributes: RedirectAttributes
    ): String {
        if (bindingResult.hasErrors()) {
            return "fSFile/edit"
        }
        fSFileService.update(id, fSFileDTO)
        redirectAttributes.addFlashAttribute(WebUtils.MSG_SUCCESS,
                WebUtils.getMessage("fSFile.update.success"))
        return "redirect:/fSFiles"
    }

    @PostMapping("/delete/{id}")
    fun delete(@PathVariable(name = "id") id: Long, redirectAttributes: RedirectAttributes):
            String {
        try {
            fSFileService.delete(id)
            redirectAttributes.addFlashAttribute(WebUtils.MSG_INFO,
                    WebUtils.getMessage("fSFile.delete.success"))
        } catch (referencedException: ReferencedException) {
            redirectAttributes.addFlashAttribute(WebUtils.MSG_ERROR,
                    WebUtils.getMessage(referencedException.key!!, referencedException.params))
        }
        return "redirect:/fSFiles"
    }

}
