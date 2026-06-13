package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.domain.FSFile
import com.oconeco.spring_search_tempo.base.model.MetadataDuplicateGroup
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.util.PosixModeUtil
import com.oconeco.spring_search_tempo.base.util.WebUtils
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping

/**
 * Admin view for the metadata-duplicate finder (issue #120).
 *
 * Surfaces groups of files that share the same `(label, size,
 * fsLastModified)` triple — the metadata fingerprint that catches
 * UID-drift duplicates ("three copies of projects.json with the
 * same size and mtime but different owners — one is from when my
 * account was UID 1001 and one is current at UID 1000").
 */
@Controller
@RequestMapping("/admin/fsfile")
class FSFileMetadataDuplicateAdminController(
    private val fsFileRepository: FSFileRepository,
) {

    @GetMapping("/metadata-duplicates")
    fun list(
        @PageableDefault(size = 25) pageable: Pageable,
        model: Model,
    ): String {
        val groupsPage = fsFileRepository.findMetadataDuplicateGroups(pageable)
        val groupRows = groupsPage.content.map { group ->
            val files = fsFileRepository.findByMetadataTriple(
                group.label, group.size, group.fsLastModified,
            )
            DuplicateGroupRow(group, files)
        }

        model.addAttribute("groupRows", groupRows)
        model.addAttribute("groupsPage", groupsPage)
        model.addAttribute("paginationModel", WebUtils.getPaginationModel(groupsPage))
        return "admin/fsfile/metadata-duplicates"
    }

    /**
     * View model row for one duplicate group. The template uses
     * [ownerMismatch] to flash a UID-drift badge; the underlying
     * [files] list backs the per-row owner / group / mode breakdown.
     */
    data class DuplicateGroupRow(
        val group: MetadataDuplicateGroup,
        val files: List<FSFile>,
    ) {
        val distinctOwners: List<String> =
            files.mapNotNull { it.posixOwner ?: it.owner }.distinct().sorted()
        val distinctGroups: List<String> =
            files.mapNotNull { it.posixGroup ?: it.group }.distinct().sorted()
        val distinctModes: List<String> =
            files.mapNotNull { PosixModeUtil.toOctal(it.posixMode) }.distinct().sorted()

        val ownerMismatch: Boolean = distinctOwners.size > 1
        val groupMismatch: Boolean = distinctGroups.size > 1
        val modeMismatch: Boolean = distinctModes.size > 1
    }
}
