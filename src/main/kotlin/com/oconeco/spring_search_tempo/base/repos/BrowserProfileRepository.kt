package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.BrowserProfile
import com.oconeco.spring_search_tempo.base.domain.BrowserType
import org.springframework.data.jpa.repository.JpaRepository


interface BrowserProfileRepository : JpaRepository<BrowserProfile, Long> {

    fun findByProfilePath(profilePath: String): BrowserProfile?

    fun findByUri(uri: String): BrowserProfile?

    fun findByEnabledTrue(): List<BrowserProfile>

    fun findByBrowserType(browserType: BrowserType): List<BrowserProfile>

    fun existsByProfilePath(profilePath: String): Boolean

}
