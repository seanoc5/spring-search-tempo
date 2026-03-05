package com.oconeco.spring_search_tempo.web.service

import com.oconeco.spring_search_tempo.base.domain.DiscoveryClassificationRule
import com.oconeco.spring_search_tempo.base.domain.DiscoveryRuleGroup
import com.oconeco.spring_search_tempo.base.domain.DiscoveryRuleOperation
import com.oconeco.spring_search_tempo.base.domain.SuggestedStatus
import com.oconeco.spring_search_tempo.base.repos.DiscoveryClassificationRuleRepository
import com.oconeco.spring_search_tempo.base.repos.DiscoverySessionRepository
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DiscoveryRuleAdminService(
    private val ruleRepository: DiscoveryClassificationRuleRepository,
    private val sessionRepository: DiscoverySessionRepository,
    private val discoveryTemplateClassifier: DiscoveryTemplateClassifier
) {

    fun listRules(): List<DiscoveryRuleDTO> =
        ruleRepository.findAll(Sort.by("ruleGroup").ascending().and(Sort.by("id").ascending()))
            .map { it.toDto() }

    fun listSessionOptions(limit: Int = 50): List<DiscoverySessionOptionDTO> =
        sessionRepository.findAll(Sort.by("dateCreated").descending())
            .take(limit)
            .map {
                DiscoverySessionOptionDTO(
                    id = it.id!!,
                    host = it.host ?: "",
                    osType = it.osType ?: "",
                    status = it.status.name,
                    totalFolders = it.totalFolders
                )
            }

    @Transactional(readOnly = true)
    fun previewImpact(sessionId: Long, forcedProfile: DiscoveryUserProfile? = null): DiscoveryRuleImpactPreviewDTO {
        val discoverySession = sessionRepository.findByIdWithFolders(sessionId)
            .orElseThrow { NotFoundException("Discovery session $sessionId not found") }

        val rootPaths = discoverySession.rootPaths
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val plan = discoveryTemplateClassifier.buildPlan(
            osType = discoverySession.osType ?: "",
            rootPaths = rootPaths,
            folders = discoverySession.folders.map {
                TemplateFolderInput(
                    path = it.path ?: "",
                    name = it.name ?: "",
                    depth = it.depth
                )
            },
            forcedProfile = forcedProfile
        )

        val beforeCounts = mutableMapOf<String, Int>()
        val afterCounts = mutableMapOf<String, Int>()
        val transitionCounts = linkedMapOf<String, Int>()
        val sampleChanges = mutableListOf<DiscoveryRulePreviewChangeDTO>()
        var changed = 0

        discoverySession.folders.sortedBy { it.path ?: "" }.forEach { folder ->
            val path = folder.path ?: return@forEach
            val before = folder.suggestedStatus ?: SuggestedStatus.UNKNOWN
            val after = plan.statusByPath[path] ?: SuggestedStatus.LOCATE

            beforeCounts[before.name] = (beforeCounts[before.name] ?: 0) + 1
            afterCounts[after.name] = (afterCounts[after.name] ?: 0) + 1

            if (before != after) {
                changed += 1
                val transition = "${before.name} -> ${after.name}"
                transitionCounts[transition] = (transitionCounts[transition] ?: 0) + 1

                if (sampleChanges.size < 40) {
                    sampleChanges.add(
                        DiscoveryRulePreviewChangeDTO(
                            path = path,
                            before = before.name,
                            after = after.name
                        )
                    )
                }
            }
        }

        val unchanged = discoverySession.folders.size - changed
        val transitions = transitionCounts.entries
            .sortedByDescending { it.value }
            .map {
                DiscoveryRuleTransitionCountDTO(
                    transition = it.key,
                    count = it.value
                )
            }

        return DiscoveryRuleImpactPreviewDTO(
            sessionId = discoverySession.id!!,
            host = discoverySession.host ?: "",
            osType = discoverySession.osType ?: "",
            profile = plan.profile.name,
            confidencePercent = plan.confidencePercent,
            changedCount = changed,
            unchangedCount = unchanged,
            totalFolders = discoverySession.folders.size,
            beforeCounts = beforeCounts.toSortedMap(),
            afterCounts = afterCounts.toSortedMap(),
            transitions = transitions,
            sampleChanges = sampleChanges
        )
    }

    @Transactional
    fun createRule(request: DiscoveryRuleUpsertRequest): DiscoveryRuleDTO {
        val entity = DiscoveryClassificationRule().apply {
            ruleGroup = parseGroup(request.ruleGroup)
            operation = parseOperation(request.operation)
            matchValue = normalizeToken(request.matchValue)
            enabled = request.enabled
            note = normalizeNote(request.note)
        }
        return ruleRepository.save(entity).toDto()
    }

    @Transactional
    fun updateRule(ruleId: Long, request: DiscoveryRuleUpsertRequest): DiscoveryRuleDTO {
        val entity = ruleRepository.findById(ruleId)
            .orElseThrow { NotFoundException("Discovery rule $ruleId not found") }
            .apply {
                ruleGroup = parseGroup(request.ruleGroup)
                operation = parseOperation(request.operation)
                matchValue = normalizeToken(request.matchValue)
                enabled = request.enabled
                note = normalizeNote(request.note)
            }
        return ruleRepository.save(entity).toDto()
    }

    @Transactional
    fun deleteRule(ruleId: Long) {
        if (!ruleRepository.existsById(ruleId)) {
            throw NotFoundException("Discovery rule $ruleId not found")
        }
        ruleRepository.deleteById(ruleId)
    }

    private fun parseGroup(raw: String): DiscoveryRuleGroup =
        runCatching { DiscoveryRuleGroup.valueOf(raw.trim().uppercase()) }
            .getOrElse { throw IllegalArgumentException("Invalid rule group: $raw") }

    private fun parseOperation(raw: String): DiscoveryRuleOperation =
        runCatching { DiscoveryRuleOperation.valueOf(raw.trim().uppercase()) }
            .getOrElse { throw IllegalArgumentException("Invalid rule operation: $raw") }

    private fun normalizeToken(token: String): String {
        val normalized = token.trim().lowercase()
        require(normalized.isNotBlank()) { "matchValue must not be blank" }
        require(normalized.length <= 255) { "matchValue must be <= 255 characters" }
        return normalized
    }

    private fun normalizeNote(note: String?): String? {
        val trimmed = note?.trim().orEmpty()
        return trimmed.ifBlank { null }
    }

    private fun DiscoveryClassificationRule.toDto(): DiscoveryRuleDTO =
        DiscoveryRuleDTO(
            id = id!!,
            ruleGroup = ruleGroup.name,
            operation = operation.name,
            matchValue = matchValue,
            enabled = enabled,
            note = note
        )
}

data class DiscoveryRuleDTO(
    val id: Long,
    val ruleGroup: String,
    val operation: String,
    val matchValue: String,
    val enabled: Boolean,
    val note: String?
)

data class DiscoveryRuleUpsertRequest(
    val ruleGroup: String,
    val operation: String,
    val matchValue: String,
    val enabled: Boolean = true,
    val note: String? = null
)

data class DiscoverySessionOptionDTO(
    val id: Long,
    val host: String,
    val osType: String,
    val status: String,
    val totalFolders: Int
)

data class DiscoveryRuleImpactPreviewDTO(
    val sessionId: Long,
    val host: String,
    val osType: String,
    val profile: String,
    val confidencePercent: Int,
    val changedCount: Int,
    val unchangedCount: Int,
    val totalFolders: Int,
    val beforeCounts: Map<String, Int>,
    val afterCounts: Map<String, Int>,
    val transitions: List<DiscoveryRuleTransitionCountDTO>,
    val sampleChanges: List<DiscoveryRulePreviewChangeDTO>
)

data class DiscoveryRuleTransitionCountDTO(
    val transition: String,
    val count: Int
)

data class DiscoveryRulePreviewChangeDTO(
    val path: String,
    val before: String,
    val after: String
)
