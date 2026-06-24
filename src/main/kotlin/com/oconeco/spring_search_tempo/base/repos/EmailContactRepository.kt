package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.EmailContact
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository


interface EmailContactRepository : JpaRepository<EmailContact, Long> {

    fun findByEmailAccountIdAndNormalizedAddress(
        accountId: Long,
        normalizedAddress: String
    ): EmailContact?

    fun findByEmailAccountId(accountId: Long, pageable: Pageable): Page<EmailContact>

    fun countByEmailAccountId(accountId: Long): Long
}
