package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.MirrorFolderCheckpoint
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface MirrorFolderCheckpointRepository : JpaRepository<MirrorFolderCheckpoint, Long> {

    fun findByMirrorConfigId(mirrorConfigId: Long): List<MirrorFolderCheckpoint>

    fun findByMirrorConfigIdAndSourceFolder(
        mirrorConfigId: Long,
        sourceFolder: String
    ): MirrorFolderCheckpoint?

    @Modifying
    @Query("DELETE FROM MirrorFolderCheckpoint c WHERE c.mirrorConfigId = :mirrorConfigId")
    fun deleteByMirrorConfigId(mirrorConfigId: Long): Int
}
