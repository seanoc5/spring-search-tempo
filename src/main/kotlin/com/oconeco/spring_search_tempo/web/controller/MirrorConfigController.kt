package com.oconeco.spring_search_tempo.web.controller

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.EmailFolderService
import com.oconeco.spring_search_tempo.base.MirrorConfigService
import com.oconeco.spring_search_tempo.base.model.FolderMapping
import com.oconeco.spring_search_tempo.base.model.MirrorConfigDTO
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes


/**
 * Web UI for managing IMAP mirror configurations (issue #22, ADR-005).
 *
 * Follows the same URL/template conventions as `/emailAccounts` — camelCase
 * path segment, full template for normal/HX-Boosted requests, fragments for
 * non-boosted HTMX requests.
 */
@Controller
@RequestMapping("/emailMirrors")
class MirrorConfigController(
    private val mirrorConfigService: MirrorConfigService,
    private val emailAccountService: EmailAccountService,
    private val emailFolderService: EmailFolderService,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping
    fun list(model: Model): String {
        val accounts = emailAccountService.findAll()
        val accountEmailById = accounts.mapNotNull { it.id?.let { id -> id to (it.email ?: "?") } }.toMap()
        model.addAttribute("mirrors", mirrorConfigService.findAll())
        model.addAttribute("accounts", accounts)
        model.addAttribute("accountEmailById", accountEmailById)
        return "emailMirror/list"
    }

    @GetMapping("/{id}")
    fun view(@PathVariable id: Long, model: Model): String {
        val mirror = mirrorConfigService.get(id)
        model.addAttribute("mirror", mirror)
        model.addAttribute("sourceAccount", mirror.sourceAccountId?.let { emailAccountService.get(it) })
        model.addAttribute("destAccount", mirror.destAccountId?.let { emailAccountService.get(it) })
        return "emailMirror/view"
    }

    @GetMapping("/add")
    fun add(model: Model): String {
        model.addAttribute("mirror", MirrorConfigDTO())
        model.addAttribute("accounts", emailAccountService.findAll())
        return "emailMirror/add"
    }

    @PostMapping("/add")
    fun create(
        @Valid @ModelAttribute("mirror") mirrorDto: MirrorConfigDTO,
        bindingResult: BindingResult,
        @RequestParam(name = "folderMappingsJson", required = false) folderMappingsJson: String?,
        model: Model,
        redirectAttributes: RedirectAttributes
    ): String {
        mirrorDto.folderMappings = parseMappings(folderMappingsJson)

        if (bindingResult.hasErrors()) {
            model.addAttribute("accounts", emailAccountService.findAll())
            return "emailMirror/add"
        }

        return try {
            val id = mirrorConfigService.create(mirrorDto)
            redirectAttributes.addFlashAttribute("message", "Mirror configuration created")
            "redirect:/emailMirrors/$id"
        } catch (e: IllegalArgumentException) {
            bindingResult.reject("validation", e.message ?: "Invalid mirror configuration")
            model.addAttribute("accounts", emailAccountService.findAll())
            "emailMirror/add"
        }
    }

    @GetMapping("/{id}/edit")
    fun edit(@PathVariable id: Long, model: Model): String {
        model.addAttribute("mirror", mirrorConfigService.get(id))
        model.addAttribute("accounts", emailAccountService.findAll())
        return "emailMirror/edit"
    }

    @PostMapping("/{id}/edit")
    fun update(
        @PathVariable id: Long,
        @Valid @ModelAttribute("mirror") mirrorDto: MirrorConfigDTO,
        bindingResult: BindingResult,
        @RequestParam(name = "folderMappingsJson", required = false) folderMappingsJson: String?,
        model: Model,
        redirectAttributes: RedirectAttributes
    ): String {
        mirrorDto.folderMappings = parseMappings(folderMappingsJson)

        if (bindingResult.hasErrors()) {
            model.addAttribute("accounts", emailAccountService.findAll())
            return "emailMirror/edit"
        }

        return try {
            mirrorConfigService.update(id, mirrorDto)
            redirectAttributes.addFlashAttribute("message", "Mirror configuration updated")
            "redirect:/emailMirrors/$id"
        } catch (e: IllegalArgumentException) {
            bindingResult.reject("validation", e.message ?: "Invalid mirror configuration")
            model.addAttribute("accounts", emailAccountService.findAll())
            "emailMirror/edit"
        }
    }

    @PostMapping("/{id}/delete")
    fun delete(@PathVariable id: Long, redirectAttributes: RedirectAttributes): String {
        try {
            mirrorConfigService.delete(id)
            redirectAttributes.addFlashAttribute("message", "Mirror configuration deleted")
        } catch (e: NotFoundException) {
            redirectAttributes.addFlashAttribute("error", "Mirror not found")
        }
        return "redirect:/emailMirrors"
    }

    /**
     * Build a default folder-mapping list from the source account's enabled folders
     * (matched by name on the destination, with a warning when a name is missing on
     * the destination side — folders may sync in later, per issue #22 acceptance).
     *
     * Returns an HTMX fragment when called via HX-Request; otherwise returns a JSON-
     * compatible payload by way of the same template (the request shape we expect
     * here is HTMX).
     */
    @GetMapping("/folderSuggestions")
    fun folderSuggestions(
        @RequestParam("sourceAccountId") sourceAccountId: Long,
        @RequestParam(name = "destAccountId", required = false) destAccountId: Long?,
        @RequestHeader(value = "HX-Request", required = false) hxRequest: String?,
        model: Model
    ): String {
        val sourceFolders = emailFolderService.findByAccount(sourceAccountId)
            .filter { it.syncEnabled && it.noselect.not() }
            .mapNotNull { it.folderName }
            .distinct()

        val destFolderNames = destAccountId?.let { id ->
            emailFolderService.findByAccount(id)
                .mapNotNull { it.folderName }
                .toSet()
        } ?: emptySet()

        val suggestions = sourceFolders.map { src ->
            FolderMappingSuggestion(
                source = src,
                dest = src,
                enabled = true,
                warning = if (destAccountId != null && src !in destFolderNames) {
                    "Folder '$src' does not yet exist on the destination — it will sync in later or you can rename here."
                } else null
            )
        }

        model.addAttribute("suggestions", suggestions)
        model.addAttribute("suggestionsJson", objectMapper.writeValueAsString(
            suggestions.map { FolderMapping(source = it.source, dest = it.dest, enabled = it.enabled) }
        ))
        return if (!hxRequest.isNullOrBlank()) {
            "emailMirror/folderMappings :: editor"
        } else {
            "emailMirror/folderMappings :: editor"
        }
    }

    private fun parseMappings(json: String?): List<FolderMapping> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            objectMapper.readValue(json, FOLDER_MAPPING_LIST_TYPE)
        } catch (e: Exception) {
            log.warn("Could not parse folderMappingsJson: {}", e.message)
            emptyList()
        }
    }

    companion object {
        private val FOLDER_MAPPING_LIST_TYPE = object : TypeReference<List<FolderMapping>>() {}
    }
}

data class FolderMappingSuggestion(
    val source: String,
    val dest: String,
    val enabled: Boolean,
    val warning: String? = null
)
