package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
class AnalysisOverrideService(
    private val fileRepository: FSFileRepository,
    private val folderRepository: FSFolderRepository
) {
    companion object {
        private val log = LoggerFactory.getLogger(AnalysisOverrideService::class.java)
    }

    @Transactional
    fun overrideFileStatus(id: Long, status: AnalysisStatus, reasonInput: String?, user: String): AnalysisOverrideResult? {
        val file = fileRepository.findById(id).orElse(null) ?: return null
        val oldStatus = file.analysisStatus
        val reason = manualReason(reasonInput, user)

        file.analysisStatus = status
        file.analysisStatusReason = reason
        file.analysisStatusSetBy = "MANUAL"
        fileRepository.save(file)

        log.info("File {} analysis status changed {} -> {} by {}", file.uri, oldStatus, status, user)

        return AnalysisOverrideResult(
            id = file.id!!,
            uri = file.uri!!,
            entityType = "FILE",
            oldStatus = oldStatus,
            newStatus = status,
            reason = reason,
            updatedBy = user,
            updatedAt = OffsetDateTime.now()
        )
    }

    @Transactional
    fun overrideFolderStatus(id: Long, status: AnalysisStatus, reasonInput: String?, user: String): AnalysisOverrideResult? {
        val folder = folderRepository.findById(id).orElse(null) ?: return null
        val oldStatus = folder.analysisStatus
        val reason = manualReason(reasonInput, user)

        folder.analysisStatus = status
        folder.analysisStatusReason = reason
        folder.analysisStatusSetBy = "MANUAL"
        folderRepository.save(folder)

        log.info("Folder {} analysis status changed {} -> {} by {}", folder.uri, oldStatus, status, user)

        return AnalysisOverrideResult(
            id = folder.id!!,
            uri = folder.uri!!,
            entityType = "FOLDER",
            oldStatus = oldStatus,
            newStatus = status,
            reason = reason,
            updatedBy = user,
            updatedAt = OffsetDateTime.now()
        )
    }

    @Transactional
    fun bulkOverrideFileStatus(ids: List<Long>, status: AnalysisStatus, reasonInput: String?, user: String): AnalysisOverrideBatchResult {
        val reason = manualReason(reasonInput, user)
        val results = mutableListOf<AnalysisOverrideResult>()
        val errors = mutableListOf<String>()

        val files = fileRepository.findAllById(ids)
        val fileMap = files.associateBy { it.id }

        for (id in ids) {
            val file = fileMap[id]
            if (file == null) {
                errors.add("File $id not found")
                continue
            }

            val oldStatus = file.analysisStatus
            file.analysisStatus = status
            file.analysisStatusReason = reason
            file.analysisStatusSetBy = "MANUAL"

            results.add(
                AnalysisOverrideResult(
                    id = file.id!!,
                    uri = file.uri!!,
                    entityType = "FILE",
                    oldStatus = oldStatus,
                    newStatus = status,
                    reason = reason,
                    updatedBy = user,
                    updatedAt = OffsetDateTime.now()
                )
            )
        }

        fileRepository.saveAll(files)
        log.info("Bulk file override: {} files updated to {} by {}", results.size, status, user)
        return AnalysisOverrideBatchResult(results, errors, ids.size, results.size)
    }

    @Transactional
    fun bulkOverrideFolderStatus(ids: List<Long>, status: AnalysisStatus, reasonInput: String?, user: String): AnalysisOverrideBatchResult {
        val reason = manualReason(reasonInput, user)
        val results = mutableListOf<AnalysisOverrideResult>()
        val errors = mutableListOf<String>()

        val folders = folderRepository.findAllById(ids)
        val folderMap = folders.associateBy { it.id }

        for (id in ids) {
            val folder = folderMap[id]
            if (folder == null) {
                errors.add("Folder $id not found")
                continue
            }

            val oldStatus = folder.analysisStatus
            folder.analysisStatus = status
            folder.analysisStatusReason = reason
            folder.analysisStatusSetBy = "MANUAL"

            results.add(
                AnalysisOverrideResult(
                    id = folder.id!!,
                    uri = folder.uri!!,
                    entityType = "FOLDER",
                    oldStatus = oldStatus,
                    newStatus = status,
                    reason = reason,
                    updatedBy = user,
                    updatedAt = OffsetDateTime.now()
                )
            )
        }

        folderRepository.saveAll(folders)
        log.info("Bulk folder override: {} folders updated to {} by {}", results.size, status, user)
        return AnalysisOverrideBatchResult(results, errors, ids.size, results.size)
    }

    private fun manualReason(reasonInput: String?, user: String): String =
        "MANUAL: ${reasonInput ?: "User override"} (by $user)"
}

data class AnalysisOverrideResult(
    val id: Long,
    val uri: String,
    val entityType: String,
    val oldStatus: AnalysisStatus?,
    val newStatus: AnalysisStatus,
    val reason: String,
    val updatedBy: String,
    val updatedAt: OffsetDateTime
)

data class AnalysisOverrideBatchResult(
    val updated: List<AnalysisOverrideResult>,
    val errors: List<String>,
    val totalRequested: Int,
    val totalUpdated: Int
)
