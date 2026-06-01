package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.MirroredMessage
import org.springframework.data.jpa.repository.JpaRepository

interface MirroredMessageRepository : JpaRepository<MirroredMessage, Long> {

    fun countByMirrorConfigIdAndSourceFolder(mirrorConfigId: Long, sourceFolder: String): Long
}
