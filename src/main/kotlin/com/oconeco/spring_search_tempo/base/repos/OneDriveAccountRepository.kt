package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.OneDriveAccount
import org.springframework.data.jpa.repository.JpaRepository


interface OneDriveAccountRepository : JpaRepository<OneDriveAccount, Long> {

    fun findByEmail(email: String): OneDriveAccount?

    fun findByMicrosoftAccountId(microsoftAccountId: String): OneDriveAccount?

    fun findByEnabledTrue(): List<OneDriveAccount>

    fun existsByEmail(email: String): Boolean

}
