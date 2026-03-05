package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.UserOwnershipService
import com.oconeco.spring_search_tempo.base.domain.SpringUser
import com.oconeco.spring_search_tempo.base.domain.UserSourceHost
import com.oconeco.spring_search_tempo.base.model.BasicUserDetails
import com.oconeco.spring_search_tempo.base.repos.SpringUserRepository
import com.oconeco.spring_search_tempo.base.repos.UserSourceHostRepository
import com.oconeco.spring_search_tempo.base.util.UserRoles
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserOwnershipServiceImpl(
    private val userSourceHostRepository: UserSourceHostRepository,
    private val springUserRepository: SpringUserRepository
) : UserOwnershipService {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun getOwnedSourceHosts(userId: Long): List<String> {
        return userSourceHostRepository.findSourceHostsByUserId(userId)
    }

    override fun getCurrentUserSourceHosts(): List<String> {
        val userDetails = getCurrentUserDetails() ?: return emptyList()

        // Use cached sourceHosts from BasicUserDetails if available
        if (userDetails.ownedSourceHosts.isNotEmpty()) {
            return userDetails.ownedSourceHosts
        }

        // Fallback to database lookup if not cached
        val userId = userDetails.id ?: return emptyList()
        return getOwnedSourceHosts(userId)
    }

    override fun currentUserOwnsSourceHost(sourceHost: String): Boolean {
        return getCurrentUserSourceHosts().any { it.equals(sourceHost, ignoreCase = true) }
    }

    override fun isCurrentUserAdmin(): Boolean {
        val authentication = SecurityContextHolder.getContext().authentication
            ?: return false

        return authentication.authorities.any {
            it.authority == UserRoles.ADMIN
        }
    }

    override fun getCurrentUserId(): Long? {
        return getCurrentUserDetails()?.id
    }

    override fun getCurrentUsername(): String? {
        return getCurrentUserDetails()?.username
    }

    @Transactional
    override fun assignSourceHostToUser(userId: Long, sourceHost: String) {
        if (userSourceHostRepository.existsBySpringUserIdAndSourceHost(userId, sourceHost)) {
            log.debug("User {} already owns sourceHost {}", userId, sourceHost)
            return
        }

        val user = springUserRepository.findById(userId).orElseThrow {
            IllegalArgumentException("User not found: $userId")
        }

        val assignment = UserSourceHost().apply {
            this.springUser = user
            this.sourceHost = sourceHost
        }
        userSourceHostRepository.save(assignment)
        log.info("Assigned sourceHost '{}' to user {}", sourceHost, userId)
    }

    @Transactional
    override fun removeSourceHostFromUser(userId: Long, sourceHost: String) {
        userSourceHostRepository.deleteBySpringUserIdAndSourceHost(userId, sourceHost)
        log.info("Removed sourceHost '{}' from user {}", sourceHost, userId)
    }

    override fun getSourceHostAssignments(userId: Long): List<String> {
        return userSourceHostRepository.findSourceHostsByUserId(userId)
    }

    private fun getCurrentUserDetails(): BasicUserDetails? {
        val authentication = SecurityContextHolder.getContext().authentication
            ?: return null

        val principal = authentication.principal
        return if (principal is BasicUserDetails) {
            principal
        } else {
            null
        }
    }
}
