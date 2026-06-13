package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.FolderSnapshot
import org.springframework.data.jpa.repository.JpaRepository

interface FolderSnapshotRepository : JpaRepository<FolderSnapshot, Long> {
    fun countByAuditRunId(auditRunId: Long): Long
    fun countByAuditRunIdAndUnderSkipPatternIsNotNull(auditRunId: Long): Long
    fun countByAuditRunIdAndUnderSkipPatternIsNotNullAndMatchedIndexPatternIsNotNull(auditRunId: Long): Long
}
