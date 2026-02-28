package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.model.EmailMessageDTO
import com.oconeco.spring_search_tempo.base.model.EmailTagDTO
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable


interface EmailTagService {

    fun count(): Long

    fun findAll(): List<EmailTagDTO>

    /**
     * Find all tags with their message counts.
     */
    fun findAllWithCounts(): List<EmailTagDTO>

    fun get(id: Long): EmailTagDTO

    fun findByName(name: String): EmailTagDTO?

    /**
     * Create a new tag.
     * @throws IllegalArgumentException if name is 'junk' (reserved system tag)
     */
    fun create(emailTagDTO: EmailTagDTO): Long

    /**
     * Update an existing tag.
     * @throws IllegalArgumentException if tag is a system tag
     * @throws IllegalArgumentException if attempting to rename to 'junk'
     */
    fun update(id: Long, emailTagDTO: EmailTagDTO)

    /**
     * Delete a tag.
     * @throws IllegalArgumentException if tag is a system tag
     */
    fun delete(id: Long)

    /**
     * Add a tag to a message.
     */
    fun addTagToMessage(messageId: Long, tagId: Long)

    /**
     * Remove a tag from a message.
     */
    fun removeTagFromMessage(messageId: Long, tagId: Long)

    /**
     * Find messages with a specific tag.
     */
    fun findMessagesWithTag(tagId: Long, pageable: Pageable): Page<EmailMessageDTO>

    /**
     * Count messages with a specific tag.
     */
    fun countMessagesWithTag(tagId: Long): Long

    /**
     * Check if tag name exists (case-insensitive).
     */
    fun nameExists(name: String): Boolean

}
