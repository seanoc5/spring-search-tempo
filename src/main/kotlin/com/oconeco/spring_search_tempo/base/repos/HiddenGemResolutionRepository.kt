package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.HiddenGemResolution
import org.springframework.data.jpa.repository.JpaRepository

interface HiddenGemResolutionRepository : JpaRepository<HiddenGemResolution, Long> {
    fun findBySourceRefAndPath(sourceRef: String, path: String): HiddenGemResolution?

    fun findBySourceRef(sourceRef: String): List<HiddenGemResolution>
}
