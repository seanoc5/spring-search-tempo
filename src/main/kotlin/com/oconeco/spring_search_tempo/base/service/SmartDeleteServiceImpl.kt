package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository
import com.oconeco.spring_search_tempo.base.repos.CrawlConfigRepository
import com.oconeco.spring_search_tempo.base.repos.CrawlDiscoveryFileSampleRepository
import com.oconeco.spring_search_tempo.base.repos.CrawlDiscoveryFolderObservationRepository
import com.oconeco.spring_search_tempo.base.repos.CrawlDiscoveryRunRepository
import com.oconeco.spring_search_tempo.base.repos.DiscoverySessionRepository
import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
import com.oconeco.spring_search_tempo.base.repos.EmailFolderRepository
import com.oconeco.spring_search_tempo.base.repos.EmailMessageRepository
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import com.oconeco.spring_search_tempo.base.repos.JobRunRepository
import com.oconeco.spring_search_tempo.base.repos.RemoteCrawlTaskRepository
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SmartDeleteServiceImpl(
    private val crawlConfigRepository: CrawlConfigRepository,
    private val discoverySessionRepository: DiscoverySessionRepository,
    private val crawlDiscoveryFolderObservationRepository: CrawlDiscoveryFolderObservationRepository,
    private val crawlDiscoveryFileSampleRepository: CrawlDiscoveryFileSampleRepository,
    private val crawlDiscoveryRunRepository: CrawlDiscoveryRunRepository,
    private val remoteCrawlTaskRepository: RemoteCrawlTaskRepository,
    private val jobRunRepository: JobRunRepository,
    private val fsFolderRepository: FSFolderRepository,
    private val fsFileRepository: FSFileRepository,
    private val contentChunkRepository: ContentChunkRepository,
    private val emailAccountRepository: EmailAccountRepository,
    private val emailFolderRepository: EmailFolderRepository,
    private val emailMessageRepository: EmailMessageRepository
) : SmartDeleteService {

    companion object {
        private val log = LoggerFactory.getLogger(SmartDeleteServiceImpl::class.java)
    }

    @Transactional
    override fun deleteDiscoverySession(id: Long): DiscoverySessionDeleteSummary {
        val session = discoverySessionRepository.findByIdWithFolders(id)
            .orElseThrow { NotFoundException() }
        val sessionId = session.id ?: throw NotFoundException()
        val host = session.host ?: "unknown"
        val discoveredFoldersDeleted = session.folders.size

        discoverySessionRepository.delete(session)
        log.info(
            "Deleted discovery session {} for host {} ({} discovered folders)",
            sessionId,
            host,
            discoveredFoldersDeleted
        )

        return DiscoverySessionDeleteSummary(
            sessionId = sessionId,
            host = host,
            discoveredFoldersDeleted = discoveredFoldersDeleted
        )
    }

    @Transactional
    override fun deleteCrawlConfig(id: Long): CrawlConfigDeleteSummary {
        val crawlConfig = crawlConfigRepository.findById(id)
            .orElseThrow { NotFoundException() }
        val crawlConfigId = crawlConfig.id ?: throw NotFoundException()
        val crawlConfigName = crawlConfig.label ?: crawlConfig.name ?: "crawlConfig-$crawlConfigId"

        val observationIds = crawlDiscoveryFolderObservationRepository.findIdsByCrawlConfigId(crawlConfigId)
        val discoverySamplesDeleted = if (observationIds.isEmpty()) {
            0
        } else {
            crawlDiscoveryFileSampleRepository.deleteByFolderObservationIdIn(observationIds)
        }
        val discoveryObservationsDeleted = crawlDiscoveryFolderObservationRepository.deleteByCrawlConfigId(crawlConfigId)
        val discoveryRunsDeleted = crawlDiscoveryRunRepository.deleteByCrawlConfigId(crawlConfigId)
        val remoteTasksDeleted = remoteCrawlTaskRepository.deleteByCrawlConfigId(crawlConfigId)
        val chunksDeleted = contentChunkRepository.deleteByCrawlConfigId(crawlConfigId)
        val filesDeleted = fsFileRepository.deleteByCrawlConfigId(crawlConfigId)
        val foldersDeleted = fsFolderRepository.deleteByCrawlConfigId(crawlConfigId)
        val discoverySessionsUnlinked = discoverySessionRepository.clearCrawlConfigReference(crawlConfigId)
        val jobRunsDeleted = jobRunRepository.deleteByCrawlConfigId(crawlConfigId)

        crawlConfigRepository.delete(crawlConfig)

        log.info(
            "Deleted crawl config {} ({}) with files={}, folders={}, chunks={}, obs={}, samples={}, runs={}, tasks={}, jobRuns={}, sessionsUnlinked={}",
            crawlConfigId,
            crawlConfigName,
            filesDeleted,
            foldersDeleted,
            chunksDeleted,
            discoveryObservationsDeleted,
            discoverySamplesDeleted,
            discoveryRunsDeleted,
            remoteTasksDeleted,
            jobRunsDeleted,
            discoverySessionsUnlinked
        )

        return CrawlConfigDeleteSummary(
            crawlConfigId = crawlConfigId,
            crawlConfigName = crawlConfigName,
            chunksDeleted = chunksDeleted,
            filesDeleted = filesDeleted,
            foldersDeleted = foldersDeleted,
            discoverySamplesDeleted = discoverySamplesDeleted,
            discoveryObservationsDeleted = discoveryObservationsDeleted,
            discoveryRunsDeleted = discoveryRunsDeleted,
            discoverySessionsUnlinked = discoverySessionsUnlinked,
            remoteTasksDeleted = remoteTasksDeleted,
            jobRunsDeleted = jobRunsDeleted
        )
    }

    @Transactional
    override fun deleteFSFolder(id: Long): FSFolderDeleteSummary {
        val folder = fsFolderRepository.findById(id)
            .orElseThrow { NotFoundException() }
        val folderId = folder.id ?: throw NotFoundException()
        val folderUri = folder.uri ?: throw IllegalArgumentException("Folder $folderId has no URI")
        val escapedPrefix = escapeLike(subtreePrefix(folderUri))

        val folderIds = fsFolderRepository.findIdsInSubtree(folderUri, escapedPrefix)
        val fileIds = fsFileRepository.findIdsByUriPrefix(escapedPrefix)
        val chunksDeleted = if (fileIds.isEmpty()) 0 else contentChunkRepository.deleteByConceptIdIn(fileIds)

        if (fileIds.isNotEmpty()) {
            fsFileRepository.deleteAllByIdInBatch(fileIds)
        }
        if (folderIds.isNotEmpty()) {
            fsFolderRepository.deleteAllByIdInBatch(folderIds)
        }

        log.info(
            "Deleted folder subtree {} (folderId={}) with folders={}, files={}, chunks={}",
            folderUri,
            folderId,
            folderIds.size,
            fileIds.size,
            chunksDeleted
        )

        return FSFolderDeleteSummary(
            folderId = folderId,
            folderUri = folderUri,
            chunksDeleted = chunksDeleted,
            filesDeleted = fileIds.size,
            foldersDeleted = folderIds.size
        )
    }

    @Transactional
    override fun deleteFSFile(id: Long): FSFileDeleteSummary {
        val file = fsFileRepository.findById(id)
            .orElseThrow { NotFoundException() }
        val fileId = file.id ?: throw NotFoundException()
        val fileUri = file.uri ?: "fsFile-$fileId"

        val chunksDeleted = contentChunkRepository.deleteByConceptId(fileId)
        fsFileRepository.delete(file)

        log.info("Deleted file {} (fileId={}) with {} content chunks", fileUri, fileId, chunksDeleted)

        return FSFileDeleteSummary(
            fileId = fileId,
            fileUri = fileUri,
            chunksDeleted = chunksDeleted
        )
    }

    @Transactional
    override fun deleteEmailMessage(id: Long): EmailMessageDeleteSummary {
        val message = emailMessageRepository.findById(id)
            .orElseThrow { NotFoundException() }
        val messageId = message.id ?: throw NotFoundException()
        val messageUri = message.uri ?: "emailMessage-$messageId"

        val chunksDeleted = contentChunkRepository.deleteByEmailMessageId(messageId)
        emailMessageRepository.delete(message)

        log.info("Deleted email message {} with {} content chunks", messageUri, chunksDeleted)

        return EmailMessageDeleteSummary(
            messageId = messageId,
            messageUri = messageUri,
            chunksDeleted = chunksDeleted
        )
    }

    @Transactional
    override fun deleteEmailAccount(id: Long): EmailAccountDeleteSummary {
        val account = emailAccountRepository.findById(id)
            .orElseThrow { NotFoundException() }
        val accountId = account.id ?: throw NotFoundException()
        val accountEmail = account.email ?: "emailAccount-$accountId"

        val chunksDeleted = contentChunkRepository.deleteByEmailAccountId(accountId)
        val messagesDeleted = emailMessageRepository.deleteByEmailAccountId(accountId)
        val foldersDeleted = emailFolderRepository.deleteByEmailAccountId(accountId)
        val jobRunsDeleted = jobRunRepository.deleteEmailSyncJobsByAccountId(accountId)

        emailAccountRepository.delete(account)

        log.info(
            "Deleted email account {} ({}) with messages={}, folders={}, chunks={}, jobRuns={}",
            accountId,
            accountEmail,
            messagesDeleted,
            foldersDeleted,
            chunksDeleted,
            jobRunsDeleted
        )

        return EmailAccountDeleteSummary(
            accountId = accountId,
            accountEmail = accountEmail,
            chunksDeleted = chunksDeleted,
            messagesDeleted = messagesDeleted,
            foldersDeleted = foldersDeleted,
            jobRunsDeleted = jobRunsDeleted
        )
    }

    @Transactional
    override fun purgeSourceHost(sourceHost: String): SourceHostPurgeSummary {
        val normalizedHost = sourceHost.trim().takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("sourceHost is required")

        val crawlConfigs = crawlConfigRepository.findBySourceHostIgnoreCase(normalizedHost)
        val discoverySessions = discoverySessionRepository.findByHostOrSourceHostOrderByDateCreatedDesc(normalizedHost)
        val emailAccounts = emailAccountRepository.findBySourceHostIgnoreCase(normalizedHost)

        var chunksDeleted = 0
        var filesDeleted = 0
        var foldersDeleted = 0
        var discoverySamplesDeleted = 0
        var discoveryObservationsDeleted = 0
        var discoveryRunsDeleted = 0
        var remoteTasksDeleted = 0
        var jobRunsDeleted = 0

        crawlConfigs.forEach { crawlConfig ->
            val configId = crawlConfig.id ?: return@forEach
            val summary = deleteCrawlConfig(configId)
            chunksDeleted += summary.chunksDeleted
            filesDeleted += summary.filesDeleted
            foldersDeleted += summary.foldersDeleted
            discoverySamplesDeleted += summary.discoverySamplesDeleted
            discoveryObservationsDeleted += summary.discoveryObservationsDeleted
            discoveryRunsDeleted += summary.discoveryRunsDeleted
            remoteTasksDeleted += summary.remoteTasksDeleted
            jobRunsDeleted += summary.jobRunsDeleted
        }

        discoverySessions.forEach { session ->
            val sessionId = session.id ?: return@forEach
            deleteDiscoverySession(sessionId)
        }

        var emailChunksDeleted = 0
        var emailMessagesDeleted = 0
        var emailFoldersDeleted = 0
        var emailJobRunsDeleted = 0

        emailAccounts.forEach { account ->
            val accountId = account.id ?: return@forEach
            val summary = deleteEmailAccount(accountId)
            emailChunksDeleted += summary.chunksDeleted
            emailMessagesDeleted += summary.messagesDeleted
            emailFoldersDeleted += summary.foldersDeleted
            emailJobRunsDeleted += summary.jobRunsDeleted
        }

        chunksDeleted += emailChunksDeleted
        jobRunsDeleted += emailJobRunsDeleted

        val orphanChunksDeleted = contentChunkRepository.deleteByFSFileSourceHost(normalizedHost)
        val orphanFilesDeleted = fsFileRepository.deleteBySourceHost(normalizedHost)
        val orphanFoldersDeleted = fsFolderRepository.deleteBySourceHost(normalizedHost)

        chunksDeleted += orphanChunksDeleted
        filesDeleted += orphanFilesDeleted
        foldersDeleted += orphanFoldersDeleted

        log.info(
            "Purged sourceHost {}: crawlConfigs={}, discoverySessions={}, emailAccounts={}, files={}, folders={}, chunks={}, obs={}, samples={}, runs={}, tasks={}, jobRuns={}",
            normalizedHost,
            crawlConfigs.size,
            discoverySessions.size,
            emailAccounts.size,
            filesDeleted,
            foldersDeleted,
            chunksDeleted,
            discoveryObservationsDeleted,
            discoverySamplesDeleted,
            discoveryRunsDeleted,
            remoteTasksDeleted,
            jobRunsDeleted
        )

        return SourceHostPurgeSummary(
            sourceHost = normalizedHost,
            crawlConfigsDeleted = crawlConfigs.size,
            discoverySessionsDeleted = discoverySessions.size,
            emailAccountsDeleted = emailAccounts.size,
            emailMessagesDeleted = emailMessagesDeleted,
            emailFoldersDeleted = emailFoldersDeleted,
            chunksDeleted = chunksDeleted,
            filesDeleted = filesDeleted,
            foldersDeleted = foldersDeleted,
            discoverySamplesDeleted = discoverySamplesDeleted,
            discoveryObservationsDeleted = discoveryObservationsDeleted,
            discoveryRunsDeleted = discoveryRunsDeleted,
            remoteTasksDeleted = remoteTasksDeleted,
            jobRunsDeleted = jobRunsDeleted
        )
    }

    private fun subtreePrefix(uri: String): String =
        if (uri == "/") "/" else uri.trimEnd('/') + "/"

    private fun escapeLike(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
}
