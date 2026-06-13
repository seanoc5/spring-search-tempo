package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.FolderAuditRun
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface FolderAuditRunRepository : JpaRepository<FolderAuditRun, Long> {
    fun findAllByOrderByStartedDesc(pageable: Pageable): List<FolderAuditRun>
}
