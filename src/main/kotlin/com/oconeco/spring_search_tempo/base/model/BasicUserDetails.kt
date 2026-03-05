package com.oconeco.spring_search_tempo.base.model

import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.User


/**
 * Extension of Spring Security User class to store additional data.
 *
 * Includes ownedSourceHosts for "default mine only" visibility filtering,
 * where users see only resources matching their owned sourceHosts.
 */
class BasicUserDetails(
    val id: Long?,
    username: String,
    hash: String?,
    authorities: Collection<GrantedAuthority>,
    val ownedSourceHosts: List<String> = emptyList()
) : User(username, hash, authorities) {
    companion object {
        // Keep Java serialization stable for Spring Session JDBC attributes across builds.
        private const val serialVersionUID: Long = -5214199333071843395L
    }
}
