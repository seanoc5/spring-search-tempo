package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.MirrorCheckpoint
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface MirrorCheckpointRepository : JpaRepository<MirrorCheckpoint, Long> {

    fun findByMirrorConfigId(mirrorConfigId: Long): MirrorCheckpoint?

    @Modifying
    @Query("DELETE FROM MirrorCheckpoint c WHERE c.mirrorConfigId = :mirrorConfigId")
    fun deleteByMirrorConfigId(mirrorConfigId: Long): Int
}
