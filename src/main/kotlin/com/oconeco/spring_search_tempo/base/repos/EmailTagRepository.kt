package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.EmailTag
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query


interface EmailTagRepository : JpaRepository<EmailTag, Long> {

    fun findByName(name: String): EmailTag?

    fun existsByName(name: String): Boolean

    /**
     * Find all tags with their message counts.
     * Returns pairs of [tag, messageCount].
     */
    @Query("""
        SELECT t, COUNT(m.id)
        FROM EmailTag t
        LEFT JOIN t.messages m
        GROUP BY t
        ORDER BY t.name
    """)
    fun findAllWithMessageCounts(): List<Array<Any>>

    /**
     * Count messages with a specific tag.
     */
    @Query("SELECT COUNT(m) FROM EmailMessage m JOIN m.tags t WHERE t.id = :tagId")
    fun countMessagesByTagId(tagId: Long): Long

}
