package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.HostCrawlSessionFolder
import org.springframework.data.jpa.repository.JpaRepository

interface HostCrawlSessionFolderRepository : JpaRepository<HostCrawlSessionFolder, Long> {
    fun findByHostCrawlSessionIdAndSelectedPathIn(
        hostCrawlSessionId: Long,
        selectedPaths: Collection<String>
    ): List<HostCrawlSessionFolder>

    fun findByHostCrawlSessionIdAndRemoteCrawlTaskIdIn(
        hostCrawlSessionId: Long,
        remoteTaskIds: Collection<Long>
    ): List<HostCrawlSessionFolder>
}
