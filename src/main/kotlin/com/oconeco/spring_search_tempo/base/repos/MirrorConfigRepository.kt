package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.MirrorConfig
import org.springframework.data.jpa.repository.JpaRepository


interface MirrorConfigRepository : JpaRepository<MirrorConfig, Long> {

    fun findByEnabledTrue(): List<MirrorConfig>

    fun findBySourceAccountId(sourceAccountId: Long): List<MirrorConfig>

    fun findByDestAccountId(destAccountId: Long): List<MirrorConfig>

    fun findByUri(uri: String): MirrorConfig?
}
