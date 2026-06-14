package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.model.MetadataDuplicateGroup
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

/**
 * Custom repository fragment for the metadata-duplicate finder
 * (issue #120). Kept out of [FSFileRepository] because the
 * GROUP BY / HAVING count query needs a subquery in the FROM
 * clause, which Spring Data JPA's `@Query(countQuery = …)`
 * doesn't handle cleanly across JPQL/native modes.
 */
interface FSFileMetadataDuplicateRepository {
    /**
     * Page over groups of files that share the same
     * `(label, size, fsLastModified)` triple. Singletons are excluded.
     * Largest groups come first.
     */
    fun findMetadataDuplicateGroups(pageable: Pageable): Page<MetadataDuplicateGroup>
}

@Repository
class FSFileMetadataDuplicateRepositoryImpl(
    @PersistenceContext private val em: EntityManager,
) : FSFileMetadataDuplicateRepository {

    override fun findMetadataDuplicateGroups(pageable: Pageable): Page<MetadataDuplicateGroup> {
        // Subquery in the FROM clause needs native SQL; JPQL's COUNT
        // can't reach over a HAVING-bounded grouped set on every
        // Hibernate version we run on.
        val countSql = """
            SELECT COUNT(*) FROM (
                SELECT 1
                FROM fsfile
                WHERE label IS NOT NULL
                AND size IS NOT NULL
                AND fs_last_modified IS NOT NULL
                GROUP BY label, size, fs_last_modified
                HAVING COUNT(*) > 1
            ) g
        """.trimIndent()
        val groupCount = (em.createNativeQuery(countSql).singleResult as Number).toLong()

        if (groupCount == 0L || pageable.offset >= groupCount) {
            return PageImpl(emptyList(), pageable, groupCount)
        }

        val listJpql = """
            SELECT f.label, f.size, f.fsLastModified, COUNT(f.id)
            FROM FSFile f
            WHERE f.label IS NOT NULL
            AND f.size IS NOT NULL
            AND f.fsLastModified IS NOT NULL
            GROUP BY f.label, f.size, f.fsLastModified
            HAVING COUNT(f.id) > 1
            ORDER BY COUNT(f.id) DESC, f.label ASC
        """.trimIndent()
        val rows = em.createQuery(listJpql, Array<Any>::class.java)
            .setFirstResult(pageable.offset.toInt())
            .setMaxResults(pageable.pageSize)
            .resultList

        val content = rows.map { row ->
            MetadataDuplicateGroup(
                label = row[0] as String,
                size = (row[1] as Number).toLong(),
                fsLastModified = row[2] as OffsetDateTime,
                count = (row[3] as Number).toLong(),
            )
        }
        return PageImpl(content, pageable, groupCount)
    }
}
