package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.SourceHost
import org.springframework.data.jpa.repository.JpaRepository

interface SourceHostRepository : JpaRepository<SourceHost, Long> {
    fun findByNormalizedHost(normalizedHost: String): SourceHost?
}
