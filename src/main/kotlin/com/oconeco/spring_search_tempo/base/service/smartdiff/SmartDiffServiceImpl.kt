package com.oconeco.spring_search_tempo.base.service.smartdiff

import com.oconeco.spring_search_tempo.base.SmartDiffService
import com.oconeco.spring_search_tempo.base.domain.FSFile
import com.oconeco.spring_search_tempo.base.model.SmartDiffResult
import com.oconeco.spring_search_tempo.base.model.SmartDiffSiblingVersion
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class SmartDiffServiceImpl(
    strategies: List<SmartDiffStrategy>,
    private val fsFileRepository: FSFileRepository,
) : SmartDiffService {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Content-type → strategy lookup. Lowercased keys so callers can pass the
     * MIME type without case-normalising first.
     */
    private val strategyByContentType: Map<String, SmartDiffStrategy> = buildMap {
        for (strategy in strategies) {
            for (ct in strategy.supportedContentTypes()) {
                val key = ct.lowercase()
                val existing = put(key, strategy)
                if (existing != null && existing !== strategy) {
                    log.warn(
                        "Multiple SmartDiffStrategy beans claim content-type {}: {} replaces {}",
                        key, strategy.javaClass.simpleName, existing.javaClass.simpleName,
                    )
                }
            }
        }
    }

    init {
        log.info(
            "SmartDiffService registered {} strategies covering {} content-types",
            strategies.size, strategyByContentType.size,
        )
    }

    override fun findSiblingVersions(fileId: Long, limit: Int): List<SmartDiffSiblingVersion> {
        require(limit in 1..200) { "limit must be in [1, 200], was $limit" }
        val file = fsFileRepository.findById(fileId).orElse(null) ?: return emptyList()
        val label = file.label ?: return emptyList()
        val hash = file.contentHash ?: return emptyList()

        return fsFileRepository
            .findSiblingVersionCandidates(label, file.id!!, hash, PageRequest.of(0, limit))
            .map { sibling ->
                SmartDiffSiblingVersion(
                    id = sibling.id!!,
                    uri = sibling.uri,
                    label = sibling.label,
                    contentHash = sibling.contentHash,
                    size = sibling.size,
                    fsLastModified = sibling.fsLastModified,
                )
            }
    }

    override fun isSupported(contentType: String?): Boolean {
        val key = contentType?.lowercase() ?: return false
        return key in strategyByContentType
    }

    override fun diff(oldFileId: Long, newFileId: Long): SmartDiffResult {
        require(oldFileId != newFileId) { "oldFileId and newFileId must differ" }
        val oldFile = fsFileRepository.findById(oldFileId).orElseThrow {
            IllegalArgumentException("FSFile $oldFileId not found")
        }
        val newFile = fsFileRepository.findById(newFileId).orElseThrow {
            IllegalArgumentException("FSFile $newFileId not found")
        }
        val resolvedContentType = newFile.contentType ?: oldFile.contentType
            ?: throw IllegalArgumentException("Neither file has a contentType set")
        val strategy = strategyByContentType[resolvedContentType.lowercase()]
            ?: throw IllegalArgumentException(
                "No smart-diff strategy registered for content-type '$resolvedContentType'",
            )
        return strategy.diff(oldFile, newFile)
    }

    /** Test hook — surfaces the registered strategies for assertion in unit tests. */
    internal fun registeredContentTypes(): Set<String> = strategyByContentType.keys

    /** Test hook — call sites that already have entities and want to bypass repo lookup. */
    internal fun diffEntities(oldFile: FSFile, newFile: FSFile): SmartDiffResult {
        val ct = newFile.contentType ?: oldFile.contentType
            ?: throw IllegalArgumentException("Neither file has a contentType set")
        val strategy = strategyByContentType[ct.lowercase()]
            ?: throw IllegalArgumentException("No strategy for content-type '$ct'")
        return strategy.diff(oldFile, newFile)
    }
}
