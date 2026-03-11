package com.oconeco.spring_search_tempo.base.config

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Backfills nullable smart-crawl columns on legacy databases.
 *
 * Some existing instances have NULL values for fsfolder.change_score /
 * fsfolder.crawl_temperature from pre-default schema states, which can
 * break entity hydration for non-null Kotlin properties.
 */
@Component
@Order(50)
class FSFolderSmartCrawlBackfillInitializer(
    private val jdbcTemplate: JdbcTemplate
) : ApplicationRunner {

    companion object {
        private val log = LoggerFactory.getLogger(FSFolderSmartCrawlBackfillInitializer::class.java)
        private val TABLE_CANDIDATES = listOf("fsfolder", "fs_folder")
    }

    override fun run(args: ApplicationArguments?) {
        val tableName = resolveExistingFolderTable() ?: return

        val hasChangeScore = columnExists(tableName, "change_score")
        val hasCrawlTemperature = columnExists(tableName, "crawl_temperature")

        var updatedChangeScore = 0
        var updatedCrawlTemperature = 0

        if (hasChangeScore) {
            updatedChangeScore = jdbcTemplate.update(
                "UPDATE $tableName SET change_score = 0 WHERE change_score IS NULL"
            )
            jdbcTemplate.execute("ALTER TABLE $tableName ALTER COLUMN change_score SET DEFAULT 0")
            jdbcTemplate.execute("ALTER TABLE $tableName ALTER COLUMN change_score SET NOT NULL")
        }

        if (hasCrawlTemperature) {
            updatedCrawlTemperature = jdbcTemplate.update(
                "UPDATE $tableName SET crawl_temperature = 'WARM' WHERE crawl_temperature IS NULL"
            )
            jdbcTemplate.execute("ALTER TABLE $tableName ALTER COLUMN crawl_temperature SET DEFAULT 'WARM'")
            jdbcTemplate.execute("ALTER TABLE $tableName ALTER COLUMN crawl_temperature SET NOT NULL")
        }

        if (updatedChangeScore > 0 || updatedCrawlTemperature > 0) {
            log.warn(
                "Backfilled fs folder smart-crawl nulls on table {}: change_score={}, crawl_temperature={}",
                tableName, updatedChangeScore, updatedCrawlTemperature
            )
        } else {
            log.debug(
                "FS folder smart-crawl backfill checked table {} (no null backfill needed)",
                tableName
            )
        }
    }

    private fun resolveExistingFolderTable(): String? {
        for (tableName in TABLE_CANDIDATES) {
            val exists = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.tables
                    WHERE table_schema = 'public'
                      AND table_name = ?
                )
                """.trimIndent(),
                Boolean::class.java,
                tableName
            ) ?: false
            if (exists) {
                return tableName
            }
        }
        log.debug("FS folder backfill skipped: no fsfolder/fs_folder table found")
        return null
    }

    private fun columnExists(tableName: String, columnName: String): Boolean {
        return jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = ?
                  AND column_name = ?
            )
            """.trimIndent(),
            Boolean::class.java,
            tableName,
            columnName
        ) ?: false
    }
}

