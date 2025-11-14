package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.FSFolder
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository


interface FSFolderRepository : JpaRepository<FSFolder, Long> {

    fun findAllById(id: Long?, pageable: Pageable): Page<FSFolder>

    fun existsByUri(uri: String?): Boolean

    fun findByUri(uri: String?): FSFolder?

    /**
     * Find all folders excluding those with SKIP analysis status.
     * Used by UI to hide skipped items by default.
     */
    fun findByAnalysisStatusNot(analysisStatus: AnalysisStatus, pageable: Pageable): Page<FSFolder>

    /**
     * Find folders by ID filter, excluding SKIP status.
     */
    fun findByIdAndAnalysisStatusNot(id: Long, analysisStatus: AnalysisStatus, pageable: Pageable): Page<FSFolder>

}
