package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.model.BasicUserDetails
import com.oconeco.spring_search_tempo.base.repos.SpringUserRepository
import com.oconeco.spring_search_tempo.base.repos.UserSourceHostRepository
import com.oconeco.spring_search_tempo.base.util.UserRoles
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional


@Service
class BasicUserDetailsService(
    private val springUserRepository: SpringUserRepository,
    private val userSourceHostRepository: UserSourceHostRepository
) : UserDetailsService {

    @Transactional(readOnly = true)
    override fun loadUserByUsername(username: String): BasicUserDetails {
        val springUser = springUserRepository.findByLabelIgnoreCase(username)
        if (springUser == null) {
            log.warn("user not found: {}", username)
            throw UsernameNotFoundException("User ${username} not found")
        }

        if (springUser.enabled != true) {
            log.warn("user is disabled: {}", username)
            throw UsernameNotFoundException("User ${username} is disabled")
        }

        // Build authorities from user's roles
        val authorities = mutableListOf<SimpleGrantedAuthority>()

        // Add LOGIN authority for all authenticated users
        authorities.add(SimpleGrantedAuthority(UserRoles.LOGIN))

        // Add role-based authorities from SpringRole entities
        for (role in springUser.springRoles) {
            role.label?.let { authorities.add(SimpleGrantedAuthority(it)) }
        }

        // If no roles assigned, default to USER role
        if (springUser.springRoles.isEmpty()) {
            authorities.add(SimpleGrantedAuthority(UserRoles.USER))
        }

        // Load owned sourceHosts for visibility filtering
        val sourceHosts = springUser.id?.let {
            userSourceHostRepository.findSourceHostsByUserId(it)
        } ?: emptyList()

        return BasicUserDetails(springUser.id, username, springUser.password, authorities, sourceHosts)
    }


    companion object {
        val log: Logger = LoggerFactory.getLogger(BasicUserDetailsService::class.java)
    }

}
