package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.model.BasicUserDetails
import com.oconeco.spring_search_tempo.base.repos.SpringUserRepository
import com.oconeco.spring_search_tempo.base.util.UserRoles
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service


@Service
class BasicUserDetailsService(
    private val springUserRepository: SpringUserRepository
) : UserDetailsService {

    override fun loadUserByUsername(username: String): BasicUserDetails {
        val springUser = springUserRepository.findByLabelIgnoreCase(username)
        if (springUser == null) {
            log.warn("user not found: {}", username)
            throw UsernameNotFoundException("User ${username} not found")
        }
        val role = UserRoles.LOGIN
        val authorities = listOf(SimpleGrantedAuthority(role))
        return BasicUserDetails(springUser.id, username, springUser.password, authorities)
    }


    companion object {

        val log: Logger = LoggerFactory.getLogger(BasicUserDetailsService::class.java)

    }

}
