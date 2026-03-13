package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.RemoteCrawlTask
import com.oconeco.spring_search_tempo.base.domain.RemoteTaskStatus
import java.time.OffsetDateTime
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface RemoteCrawlTaskRepository : JpaRepository<RemoteCrawlTask, Long> {

    fun findBySessionIdAndRemoteUriIn(sessionId: Long, remoteUris: Collection<String>): List<RemoteCrawlTask>

    fun findBySessionIdAndClaimTokenAndTaskStatusOrderByPriorityDescDepthAscIdAsc(
        sessionId: Long,
        claimToken: String,
        taskStatus: RemoteTaskStatus
    ): List<RemoteCrawlTask>

    fun findBySessionIdAndClaimTokenAndIdIn(
        sessionId: Long,
        claimToken: String,
        ids: Collection<Long>
    ): List<RemoteCrawlTask>

    @Query(
        value = """
            SELECT id
            FROM remote_crawl_task
            WHERE session_id = :sessionId
              AND task_status = 'PENDING'
            ORDER BY priority DESC, depth ASC NULLS LAST, id ASC
            LIMIT :maxTasks
        """,
        nativeQuery = true
    )
    fun findPendingIdsForClaim(
        @Param("sessionId") sessionId: Long,
        @Param("maxTasks") maxTasks: Int
    ): List<Long>

    @Modifying
    @Query(
        """
        UPDATE RemoteCrawlTask t
        SET t.taskStatus = :claimedStatus,
            t.claimToken = :claimToken,
            t.claimedAt = :claimedAt,
            t.attemptCount = t.attemptCount + 1
        WHERE t.id = :id
          AND t.taskStatus = :pendingStatus
        """
    )
    fun claimTask(
        @Param("id") id: Long,
        @Param("claimToken") claimToken: String,
        @Param("claimedAt") claimedAt: OffsetDateTime,
        @Param("pendingStatus") pendingStatus: RemoteTaskStatus,
        @Param("claimedStatus") claimedStatus: RemoteTaskStatus
    ): Int

    @Modifying
    @Query(
        """
        UPDATE RemoteCrawlTask t
        SET t.taskStatus = :pendingStatus,
            t.claimToken = NULL,
            t.claimedAt = NULL
        WHERE t.sessionId = :sessionId
          AND t.taskStatus = :claimedStatus
          AND t.claimedAt < :staleBefore
        """
    )
    fun releaseStaleClaims(
        @Param("sessionId") sessionId: Long,
        @Param("staleBefore") staleBefore: OffsetDateTime,
        @Param("claimedStatus") claimedStatus: RemoteTaskStatus,
        @Param("pendingStatus") pendingStatus: RemoteTaskStatus
    ): Int

    @Query(
        """
        SELECT t.taskStatus, COUNT(t)
        FROM RemoteCrawlTask t
        WHERE t.sessionId = :sessionId
        GROUP BY t.taskStatus
        """
    )
    fun countBySessionGroupedStatus(@Param("sessionId") sessionId: Long): List<Array<Any>>

    @Modifying
    fun deleteByCrawlConfigId(crawlConfigId: Long): Int
}
