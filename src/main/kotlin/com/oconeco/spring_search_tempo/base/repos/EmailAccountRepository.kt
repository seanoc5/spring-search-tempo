package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.EmailAccount
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import org.springframework.data.jpa.repository.JpaRepository


interface EmailAccountRepository : JpaRepository<EmailAccount, Long> {

    fun findByEmail(email: String): EmailAccount?

    fun findByUri(uri: String): EmailAccount?

    fun findByEnabledTrue(): List<EmailAccount>

    fun findByProvider(provider: EmailProvider): List<EmailAccount>

    fun existsByEmail(email: String): Boolean

}
