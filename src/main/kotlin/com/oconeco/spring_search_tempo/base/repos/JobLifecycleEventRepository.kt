package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.JobLifecycleEvent
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface JobLifecycleEventRepository : JpaRepository<JobLifecycleEvent, Long> {
    fun findAllByOrderByEventTimeDesc(pageable: Pageable): List<JobLifecycleEvent>
}
