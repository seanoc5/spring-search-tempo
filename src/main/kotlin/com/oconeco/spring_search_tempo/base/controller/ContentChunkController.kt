package com.oconeco.spring_search_tempo.base.controller

import com.oconeco.spring_search_tempo.base.ContentChunkService
import com.oconeco.spring_search_tempo.base.FSFileService
import com.oconeco.spring_search_tempo.base.model.ContentChunkDTO
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
@RequestMapping("/contentChunks")
class ContentChunkController(
    private val contentChunkService: ContentChunkService,
    private val fSFileService: FSFileService
) {

    @ModelAttribute
    fun prepareContext(model: Model) {
        model.addAttribute("parentChunkValues", contentChunkService.getContentChunkValues())
        model.addAttribute("conceptValues", fSFileService.getFSFileValues())
    }

    @GetMapping
    fun list(model: Model): String {
        model.addAttribute("contentChunks", contentChunkService.findAll())
        return "contentChunk/list"
    }

    @GetMapping("/{id}")
    fun view(@PathVariable(name = "id") id: Long, model: Model): String {
        model.addAttribute("contentChunk", contentChunkService.get(id))
        return "contentChunk/view"
    }

    @GetMapping("/add")
    fun add(@ModelAttribute("contentChunks") contentChunkDTO: ContentChunkDTO): String =
            "contentChunk/add"

    @PostMapping("/add")
    fun add(
        @ModelAttribute("contentChunks") @Valid contentChunkDTO: ContentChunkDTO,
        bindingResult: BindingResult,
        redirectAttributes: RedirectAttributes
    ): String {
        if (bindingResult.hasErrors()) {
            return "contentChunk/add"
        }
        contentChunkService.create(contentChunkDTO)
        redirectAttributes.addFlashAttribute(WebUtils.MSG_SUCCESS,
                WebUtils.getMessage("contentChunk.create.success"))
        return "redirect:/contentChunks"
    }

    @GetMapping("/edit/{id}")
    fun edit(@PathVariable(name = "id") id: Long, model: Model): String {
        model.addAttribute("contentChunks", contentChunkService.get(id))
        return "contentChunk/edit"
    }

    @PostMapping("/edit/{id}")
    fun edit(
        @PathVariable(name = "id") id: Long,
        @ModelAttribute("contentChunks") @Valid contentChunkDTO: ContentChunkDTO,
        bindingResult: BindingResult,
        redirectAttributes: RedirectAttributes
    ): String {
        if (bindingResult.hasErrors()) {
            return "contentChunk/edit"
        }
        contentChunkService.update(id, contentChunkDTO)
        redirectAttributes.addFlashAttribute(WebUtils.MSG_SUCCESS,
                WebUtils.getMessage("contentChunk.update.success"))
        return "redirect:/contentChunks"
    }

    @PostMapping("/delete/{id}")
    fun delete(@PathVariable(name = "id") id: Long, redirectAttributes: RedirectAttributes):
            String {
        try {
            contentChunkService.delete(id)
            redirectAttributes.addFlashAttribute(WebUtils.MSG_INFO,
                    WebUtils.getMessage("contentChunk.delete.success"))
        } catch (referencedException: ReferencedException) {
            redirectAttributes.addFlashAttribute(WebUtils.MSG_ERROR,
                    WebUtils.getMessage(referencedException.key!!, referencedException.params))
        }
        return "redirect:/contentChunks"
    }

}
