package com.oconeco.spring_search_tempo.base.controller

import com.oconeco.spring_search_tempo.base.ContentChunksService
import com.oconeco.spring_search_tempo.base.FSFileService
import com.oconeco.spring_search_tempo.base.model.ContentChunksDTO
import com.oconeco.spring_search_tempo.base.util.ReferencedException
import com.oconeco.spring_search_tempo.base.util.WebUtils
import jakarta.validation.Valid
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
@RequestMapping("/contentChunkss")
class ContentChunksController(
    private val contentChunksService: ContentChunksService,
    private val fSFileService: FSFileService
) {

    @ModelAttribute
    fun prepareContext(model: Model) {
        model.addAttribute("parentChunkValues", contentChunksService.getContentChunksValues())
        model.addAttribute("conceptValues", fSFileService.getFSFileValues())
    }

    @GetMapping
    fun list(model: Model): String {
        model.addAttribute("contentChunkses", contentChunksService.findAll())
        return "contentChunks/list"
    }

    @GetMapping("/add")
    fun add(@ModelAttribute("contentChunks") contentChunksDTO: ContentChunksDTO): String =
            "contentChunks/add"

    @PostMapping("/add")
    fun add(
        @ModelAttribute("contentChunks") @Valid contentChunksDTO: ContentChunksDTO,
        bindingResult: BindingResult,
        redirectAttributes: RedirectAttributes
    ): String {
        if (bindingResult.hasErrors()) {
            return "contentChunks/add"
        }
        contentChunksService.create(contentChunksDTO)
        redirectAttributes.addFlashAttribute(WebUtils.MSG_SUCCESS,
                WebUtils.getMessage("contentChunks.create.success"))
        return "redirect:/contentChunkss"
    }

    @GetMapping("/edit/{id}")
    fun edit(@PathVariable(name = "id") id: Long, model: Model): String {
        model.addAttribute("contentChunks", contentChunksService.get(id))
        return "contentChunks/edit"
    }

    @PostMapping("/edit/{id}")
    fun edit(
        @PathVariable(name = "id") id: Long,
        @ModelAttribute("contentChunks") @Valid contentChunksDTO: ContentChunksDTO,
        bindingResult: BindingResult,
        redirectAttributes: RedirectAttributes
    ): String {
        if (bindingResult.hasErrors()) {
            return "contentChunks/edit"
        }
        contentChunksService.update(id, contentChunksDTO)
        redirectAttributes.addFlashAttribute(WebUtils.MSG_SUCCESS,
                WebUtils.getMessage("contentChunks.update.success"))
        return "redirect:/contentChunkss"
    }

    @PostMapping("/delete/{id}")
    fun delete(@PathVariable(name = "id") id: Long, redirectAttributes: RedirectAttributes):
            String {
        try {
            contentChunksService.delete(id)
            redirectAttributes.addFlashAttribute(WebUtils.MSG_INFO,
                    WebUtils.getMessage("contentChunks.delete.success"))
        } catch (referencedException: ReferencedException) {
            redirectAttributes.addFlashAttribute(WebUtils.MSG_ERROR,
                    WebUtils.getMessage(referencedException.key!!, referencedException.params))
        }
        return "redirect:/contentChunkss"
    }

}
