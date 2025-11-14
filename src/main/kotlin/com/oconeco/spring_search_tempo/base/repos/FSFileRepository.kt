package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.FSFile
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository


interface FSFileRepository : JpaRepository<FSFile, Long> {

    fun findAllById(id: Long?, pageable: Pageable): Page<FSFile>

    fun findFirstByFsFolderId(id: Long): FSFile?

    fun existsByUri(uri: String?): Boolean

    fun findByUri(uri: String): FSFile?

    fun findByBodyTextIsNotNull(pageable: Pageable): Page<FSFile>

    /**
     * Find all files excluding those with SKIP analysis status.
     * Used by UI to hide skipped items by default.
     */
    fun findByAnalysisStatusNot(analysisStatus: AnalysisStatus, pageable: Pageable): Page<FSFile>

    /**
     * Find files by ID filter, excluding SKIP status.
     */
    fun findByIdAndAnalysisStatusNot(id: Long, analysisStatus: AnalysisStatus, pageable: Pageable): Page<FSFile>

}
