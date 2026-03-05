package com.oconeco.spring_search_tempo.base

/**
 * Service for managing user ownership of resources (sourceHosts and email accounts).
 *
 * Provides the foundation for "default mine only" visibility where users see
 * only their owned resources, while admins can see all.
 */
interface UserOwnershipService {

    /**
     * Get list of sourceHosts owned by a specific user.
     */
    fun getOwnedSourceHosts(userId: Long): List<String>

    /**
     * Get list of sourceHosts owned by the currently authenticated user.
     * Returns empty list if no user is authenticated or user has no owned hosts.
     */
    fun getCurrentUserSourceHosts(): List<String>

    /**
     * Check if the current user owns a specific sourceHost.
     */
    fun currentUserOwnsSourceHost(sourceHost: String): Boolean

    /**
     * Check if the current user has the ADMIN role.
     */
    fun isCurrentUserAdmin(): Boolean

    /**
     * Get the ID of the currently authenticated user, or null if not authenticated.
     */
    fun getCurrentUserId(): Long?

    /**
     * Get the username of the currently authenticated user, or null if not authenticated.
     */
    fun getCurrentUsername(): String?

    /**
     * Assign a sourceHost to a user.
     */
    fun assignSourceHostToUser(userId: Long, sourceHost: String)

    /**
     * Remove a sourceHost assignment from a user.
     */
    fun removeSourceHostFromUser(userId: Long, sourceHost: String)

    /**
     * Get all sourceHost assignments for a user.
     */
    fun getSourceHostAssignments(userId: Long): List<String>
}
