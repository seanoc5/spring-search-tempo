package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.MirrorFolderProgress
import org.springframework.data.jpa.repository.JpaRepository

interface MirrorFolderProgressRepository : JpaRepository<MirrorFolderProgress, Long> {

    fun findByJobRunId(jobRunId: Long): List<MirrorFolderProgress>

    fun findByJobRunIdAndSourceFolder(jobRunId: Long, sourceFolder: String): MirrorFolderProgress?
}
