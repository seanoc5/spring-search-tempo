package com.oconeco.spring_search_tempo.base.model

import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.User


/**
 * Extension of Spring Security User class to store additional data.
 */
class BasicUserDetails(
    val id: Long?,
    username: String,
    hash: String?,
    authorities: Collection<GrantedAuthority>
) : User(username, hash, authorities)
