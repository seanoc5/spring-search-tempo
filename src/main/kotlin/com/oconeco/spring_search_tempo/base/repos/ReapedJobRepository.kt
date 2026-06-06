package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.ReapedJob
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface ReapedJobRepository : JpaRepository<ReapedJob, Long> {
    fun findAllByOrderByReapedAtDesc(pageable: Pageable): List<ReapedJob>
}
