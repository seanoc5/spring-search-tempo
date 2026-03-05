package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.UserSourceHost
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface UserSourceHostRepository : JpaRepository<UserSourceHost, Long> {

    fun findBySpringUserId(userId: Long): List<UserSourceHost>

    @Query("SELECT ush.sourceHost FROM UserSourceHost ush WHERE ush.springUser.id = :userId")
    fun findSourceHostsByUserId(userId: Long): List<String>

    fun existsBySpringUserIdAndSourceHost(userId: Long, sourceHost: String): Boolean

    fun deleteBySpringUserIdAndSourceHost(userId: Long, sourceHost: String)

    fun deleteBySpringUserId(userId: Long)
}
