package com.oconeco.spring_search_tempo.web.service

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.CrawlMode
import com.oconeco.spring_search_tempo.base.domain.FSFolder
import com.oconeco.spring_search_tempo.base.domain.HostCrawlSelectionReason
import com.oconeco.spring_search_tempo.base.domain.HostCrawlSession
import com.oconeco.spring_search_tempo.base.domain.HostCrawlSessionFolder
import com.oconeco.spring_search_tempo.base.domain.HostCrawlSessionFolderStatus
import com.oconeco.spring_search_tempo.base.domain.HostCrawlSessionStatus
import com.oconeco.spring_search_tempo.base.domain.HostCrawlSessionType
import com.oconeco.spring_search_tempo.base.domain.RunStatus
import com.oconeco.spring_search_tempo.base.domain.SourceHost
import com.oconeco.spring_search_tempo.base.repos.CrawlConfigRepository
import com.oconeco.spring_search_tempo.base.repos.HostCrawlSessionFolderRepository
import com.oconeco.spring_search_tempo.base.repos.HostCrawlSessionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
class HostCrawlSessionService(
    private val crawlConfigRepository: CrawlConfigRepository,
    private val hostCrawlSessionRepository: HostCrawlSessionRepository,
    private val hostCrawlSessionFolderRepository: HostCrawlSessionFolderRepository
) {

    @Transactional
    fun ensureSession(
        sourceHost: SourceHost,
        crawlConfigId: Long,
        jobRunId: Long,
        crawlMode: CrawlMode?,
        smartCrawlEnabled: Boolean?
    ): HostCrawlSession {
        val existing = hostCrawlSessionRepository.findByJobRunId(jobRunId)
        if (existing != null) {
            return existing
        }

        val session = HostCrawlSession().apply {
            this.sourceHost = sourceHost
            this.crawlConfig = crawlConfigRepository.getReferenceById(crawlConfigId)
            this.jobRunId = jobRunId
            this.sessionType = when {
                crawlMode == CrawlMode.DISCOVERY -> HostCrawlSessionType.DISCOVERY_REVIEW
                smartCrawlEnabled == true -> HostCrawlSessionType.SMART
                else -> HostCrawlSessionType.FULL
            }
            this.selectionPolicy = when {
                crawlMode == CrawlMode.DISCOVERY -> "DISCOVERY"
                smartCrawlEnabled == true -> "SMART_SCHEDULED"
                else -> "ALL_MATCHING"
            }
            this.selectionReasonSummary = when {
                crawlMode == CrawlMode.DISCOVERY -> "Discovery review crawl session"
                smartCrawlEnabled == true -> "Smart crawl session"
                else -> "Remote crawl session"
            }
        }
        return hostCrawlSessionRepository.save(session)
    }

    @Transactional
    fun recordQueuedFolders(
        jobRunId: Long,
        queueItems: List<QueuedFolderSelection>
    ) {
        if (queueItems.isEmpty()) return
        val session = hostCrawlSessionRepository.findByJobRunId(jobRunId) ?: return
        val existingByPath = hostCrawlSessionFolderRepository
            .findByHostCrawlSessionIdAndSelectedPathIn(session.id!!, queueItems.map { it.selectedPath })
            .associateBy { it.selectedPath!! }

        val now = OffsetDateTime.now()
        val toSave = queueItems.map { item ->
            val existing = existingByPath[item.selectedPath]
            (existing ?: HostCrawlSessionFolder().apply {
                hostCrawlSession = session
                selectedPath = item.selectedPath
                selectedAt = now
            }).apply {
                fsFolder = item.fsFolder
                remoteCrawlTaskId = item.remoteCrawlTaskId
                analysisStatus = item.analysisStatus
                selectionReason = item.selectionReason
                selectionReasonDetail = item.selectionReasonDetail
                resultStatus = HostCrawlSessionFolderStatus.PENDING
                errorMessage = null
            }
        }
        hostCrawlSessionFolderRepository.saveAll(toSave)
    }

    @Transactional
    fun markClaimed(jobRunId: Long, remoteTaskIds: Collection<Long>, claimedAt: OffsetDateTime) {
        if (remoteTaskIds.isEmpty()) return
        val session = hostCrawlSessionRepository.findByJobRunId(jobRunId) ?: return
        val rows = hostCrawlSessionFolderRepository
            .findByHostCrawlSessionIdAndRemoteCrawlTaskIdIn(session.id!!, remoteTaskIds)
        rows.forEach {
            it.claimedAt = claimedAt
            it.resultStatus = HostCrawlSessionFolderStatus.CLAIMED
        }
        if (rows.isNotEmpty()) {
            hostCrawlSessionFolderRepository.saveAll(rows)
        }
    }

    @Transactional
    fun applyResults(jobRunId: Long, results: List<QueuedFolderResult>) {
        if (results.isEmpty()) return
        val session = hostCrawlSessionRepository.findByJobRunId(jobRunId) ?: return
        val rowsByTaskId = hostCrawlSessionFolderRepository
            .findByHostCrawlSessionIdAndRemoteCrawlTaskIdIn(session.id!!, results.map { it.remoteCrawlTaskId })
            .associateBy { it.remoteCrawlTaskId!! }

        val now = OffsetDateTime.now()
        val changed = mutableListOf<HostCrawlSessionFolder>()
        results.forEach { result ->
            val row = rowsByTaskId[result.remoteCrawlTaskId] ?: return@forEach
            row.errorMessage = result.errorMessage
            row.filesSeen = result.filesSeen
            row.filesChanged = result.filesChanged
            row.resultStatus = result.status
            row.completedAt = when (result.status) {
                HostCrawlSessionFolderStatus.RETRY -> null
                else -> now
            }
            if (result.status == HostCrawlSessionFolderStatus.RETRY) {
                row.claimedAt = null
            }
            changed += row
        }
        if (changed.isNotEmpty()) {
            hostCrawlSessionFolderRepository.saveAll(changed)
        }
    }

    @Transactional
    fun complete(jobRunId: Long, runStatus: RunStatus, errorMessage: String?) {
        val session = hostCrawlSessionRepository.findByJobRunId(jobRunId) ?: return
        session.completedAt = OffsetDateTime.now()
        session.errorMessage = errorMessage
        session.status = when (runStatus) {
            RunStatus.COMPLETED -> HostCrawlSessionStatus.COMPLETED
            RunStatus.FAILED -> HostCrawlSessionStatus.FAILED
            RunStatus.CANCELLED -> HostCrawlSessionStatus.CANCELLED
            RunStatus.RUNNING -> HostCrawlSessionStatus.RUNNING
        }
        hostCrawlSessionRepository.save(session)
    }
}

data class QueuedFolderSelection(
    val selectedPath: String,
    val analysisStatus: AnalysisStatus,
    val fsFolder: FSFolder?,
    val remoteCrawlTaskId: Long?,
    val selectionReason: HostCrawlSelectionReason = HostCrawlSelectionReason.REMOTE_QUEUE,
    val selectionReasonDetail: String? = null
)

data class QueuedFolderResult(
    val remoteCrawlTaskId: Long,
    val status: HostCrawlSessionFolderStatus,
    val errorMessage: String? = null,
    val filesSeen: Int? = null,
    val filesChanged: Int? = null
)
