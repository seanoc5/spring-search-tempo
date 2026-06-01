package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.model.FolderMapping
import com.oconeco.spring_search_tempo.base.model.MirrorConfigDTO


/**
 * IMAP mirror configuration service.
 *
 * Manages source → destination IMAP account pairs and folder mapping rules.
 * The actual copy work lives in `ImapMirrorService` / `MirrorJob` (separate
 * tickets per ADR-005); this service is the foundation: persistence + validation.
 */
interface MirrorConfigService {

    fun count(): Long

    fun findAll(): List<MirrorConfigDTO>

    fun findEnabled(): List<MirrorConfigDTO>

    fun get(id: Long): MirrorConfigDTO

    fun findById(id: Long): MirrorConfigDTO? = findByIdOrNull(id)

    fun findByIdOrNull(id: Long): MirrorConfigDTO?

    /**
     * Convenience overload matching the failing-test sketch in issue #22:
     * accept primitive arguments rather than a fully-populated DTO.
     *
     * Returns the persisted DTO (with `id` set).
     */
    fun create(
        name: String,
        sourceAccountId: Long,
        destAccountId: Long,
        folderMappings: List<FolderMapping>,
        enabled: Boolean = true,
        appendRateLimitPerSecond: Int? = 10
    ): MirrorConfigDTO

    fun create(dto: MirrorConfigDTO): Long

    fun update(id: Long, dto: MirrorConfigDTO)

    fun delete(id: Long)
}
