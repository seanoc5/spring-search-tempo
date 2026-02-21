package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.BookmarkTag
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query


interface BookmarkTagRepository : JpaRepository<BookmarkTag, Long> {

    fun findByName(name: String): BookmarkTag?

    fun findByNameIn(names: Collection<String>): List<BookmarkTag>

    fun findAllByOrderByUsageCountDesc(pageable: Pageable): List<BookmarkTag>

    @Query("SELECT t FROM BookmarkTag t ORDER BY t.usageCount DESC")
    fun findPopular(pageable: Pageable): List<BookmarkTag>

    fun existsByName(name: String): Boolean

}
