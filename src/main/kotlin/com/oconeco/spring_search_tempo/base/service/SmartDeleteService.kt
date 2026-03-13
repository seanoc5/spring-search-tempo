package com.oconeco.spring_search_tempo.base.service

interface SmartDeleteService {

    fun deleteDiscoverySession(id: Long): DiscoverySessionDeleteSummary

    fun deleteCrawlConfig(id: Long): CrawlConfigDeleteSummary

    fun deleteFSFolder(id: Long): FSFolderDeleteSummary

    fun deleteFSFile(id: Long): FSFileDeleteSummary

    fun deleteEmailMessage(id: Long): EmailMessageDeleteSummary

    fun deleteEmailAccount(id: Long): EmailAccountDeleteSummary

    fun purgeSourceHost(sourceHost: String): SourceHostPurgeSummary
}

data class DiscoverySessionDeleteSummary(
    val sessionId: Long,
    val host: String,
    val discoveredFoldersDeleted: Int
)

data class CrawlConfigDeleteSummary(
    val crawlConfigId: Long,
    val crawlConfigName: String,
    val chunksDeleted: Int,
    val filesDeleted: Int,
    val foldersDeleted: Int,
    val discoverySamplesDeleted: Int,
    val discoveryObservationsDeleted: Int,
    val discoveryRunsDeleted: Int,
    val discoverySessionsUnlinked: Int,
    val remoteTasksDeleted: Int,
    val jobRunsDeleted: Int
)

data class FSFolderDeleteSummary(
    val folderId: Long,
    val folderUri: String,
    val chunksDeleted: Int,
    val filesDeleted: Int,
    val foldersDeleted: Int
)

data class FSFileDeleteSummary(
    val fileId: Long,
    val fileUri: String,
    val chunksDeleted: Int
)

data class EmailMessageDeleteSummary(
    val messageId: Long,
    val messageUri: String,
    val chunksDeleted: Int
)

data class EmailAccountDeleteSummary(
    val accountId: Long,
    val accountEmail: String,
    val chunksDeleted: Int,
    val messagesDeleted: Int,
    val foldersDeleted: Int,
    val jobRunsDeleted: Int
)

data class SourceHostPurgeSummary(
    val sourceHost: String,
    val crawlConfigsDeleted: Int,
    val discoverySessionsDeleted: Int,
    val emailAccountsDeleted: Int,
    val emailMessagesDeleted: Int,
    val emailFoldersDeleted: Int,
    val chunksDeleted: Int,
    val filesDeleted: Int,
    val foldersDeleted: Int,
    val discoverySamplesDeleted: Int,
    val discoveryObservationsDeleted: Int,
    val discoveryRunsDeleted: Int,
    val remoteTasksDeleted: Int,
    val jobRunsDeleted: Int
)
