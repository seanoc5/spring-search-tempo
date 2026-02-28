package com.oconeco.spring_search_tempo.base.config

import com.oconeco.spring_search_tempo.base.domain.SpringRole
import com.oconeco.spring_search_tempo.base.domain.SpringUser
import com.oconeco.spring_search_tempo.base.repos.SpringRoleRepository
import com.oconeco.spring_search_tempo.base.repos.SpringUserRepository
import com.oconeco.spring_search_tempo.base.util.UserRoles
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Initializes default users and roles on application startup.
 * Only creates data if it doesn't already exist.
 */
@Component
class DataInitializer(
    private val springUserRepository: SpringUserRepository,
    private val springRoleRepository: SpringRoleRepository,
    private val passwordEncoder: PasswordEncoder
) : ApplicationRunner {

    companion object {
        private val log = LoggerFactory.getLogger(DataInitializer::class.java)
    }

    @Transactional
    override fun run(args: ApplicationArguments) {
        createAdminUserIfNotExists()
        createDefaultUserIfNotExists()
    }

    private fun createAdminUserIfNotExists() {
        if (springUserRepository.findByLabelIgnoreCase("admin") != null) {
            log.debug("Admin user already exists, skipping creation")
            return
        }

        log.info("Creating default admin user")

        val adminUser = SpringUser().apply {
            label = "admin"
            firstName = "System"
            lastName = "Administrator"
            email = "admin@localhost"
            enabled = true
            password = passwordEncoder.encode("password")
        }
        springUserRepository.save(adminUser)

        // Create ADMIN role for admin user
        val adminRole = SpringRole().apply {
            label = UserRoles.ADMIN
            description = "Administrator role with full access"
            springUser = adminUser
        }
        springRoleRepository.save(adminRole)

        // Also give admin the USER role
        val userRole = SpringRole().apply {
            label = UserRoles.USER
            description = "Standard user role"
            springUser = adminUser
        }
        springRoleRepository.save(userRole)

        log.info("Admin user created successfully")
    }

    private fun createDefaultUserIfNotExists() {
        if (springUserRepository.findByLabelIgnoreCase("user") != null) {
            log.debug("Default user already exists, skipping creation")
            return
        }

        log.info("Creating default user")

        val defaultUser = SpringUser().apply {
            label = "user"
            firstName = "Default"
            lastName = "User"
            email = "user@localhost"
            enabled = true
            password = passwordEncoder.encode("password")
        }
        springUserRepository.save(defaultUser)

        // Create USER role for default user
        val userRole = SpringRole().apply {
            label = UserRoles.USER
            description = "Standard user role"
            springUser = defaultUser
        }
        springRoleRepository.save(userRole)

        log.info("Default user created successfully")
    }
}
