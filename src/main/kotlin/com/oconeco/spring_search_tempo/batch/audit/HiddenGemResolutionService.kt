package com.oconeco.spring_search_tempo.batch.audit

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.CrawlConfig
import com.oconeco.spring_search_tempo.base.domain.HiddenGemResolution
import com.oconeco.spring_search_tempo.base.domain.HiddenGemResolutionKind
import com.oconeco.spring_search_tempo.base.repos.CrawlConfigRepository
import com.oconeco.spring_search_tempo.base.repos.FolderAuditRunRepository
import com.oconeco.spring_search_tempo.base.repos.HiddenGemResolutionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.regex.Pattern

/**
 * Records durable resolutions for folder-audit "hidden gem" candidates
 * (issue #104).
 *
 * Two operator actions are supported:
 *
 *   - [dismiss]: write a `DISMISSED` resolution row keyed on
 *     `(source_ref, path)`. The hidden-gem list view's `NOT EXISTS`
 *     filter consults this row, so the path is hidden from every
 *     future audit run against the same source.
 *
 *   - [reclassify]: write a `PROMOTED_TO_INDEX` / `PROMOTED_TO_ANALYZE`
 *     resolution row AND append a path-specific regex to the matching
 *     `CrawlConfig` rule. We prefer path-specific patterns over name-
 *     globs so that promoting one `internal-tool/` under
 *     `node_modules/` does not accidentally drag in sibling tools.
 *
 * The chosen `CrawlConfig` is the first DB entity whose name matches
 * any of the comma-separated crawl names recorded on the audit run's
 * `sourceRef`. If none exists, a minimal entity is created so the
 * decision lands somewhere durable — operators can later sync it to
 * the YAML config if they want it applied at the next crawl.
 */
@Service
class HiddenGemResolutionService(
    private val resolutionRepository: HiddenGemResolutionRepository,
    private val crawlConfigRepository: CrawlConfigRepository,
    private val folderAuditRunRepository: FolderAuditRunRepository,
    private val objectMapper: ObjectMapper
) {

    companion object {
        private val log = LoggerFactory.getLogger(HiddenGemResolutionService::class.java)
    }

    /**
     * Record a `DISMISSED` resolution. Idempotent on `(source_ref, path)`
     * — re-dismissing simply touches `resolved_at`.
     */
    @Transactional
    fun dismiss(runId: Long, path: String, resolvedBy: String?): HiddenGemResolution {
        val sourceRef = resolveSourceRef(runId)
        log.info("Dismiss hidden-gem: runId={} sourceRef={} path={}", runId, sourceRef, path)
        return upsertResolution(sourceRef, path, HiddenGemResolutionKind.DISMISSED, resolvedBy)
    }

    /**
     * Reclassify a hidden-gem candidate. Writes the resolution AND
     * either creates or updates a `CrawlConfig` with the path appended
     * to the appropriate folder-pattern JSON array.
     */
    @Transactional
    fun reclassify(
        runId: Long,
        path: String,
        target: AnalysisStatus,
        resolvedBy: String?
    ): HiddenGemResolution {
        require(target == AnalysisStatus.INDEX || target == AnalysisStatus.ANALYZE) {
            "Reclassify target must be INDEX or ANALYZE, got $target"
        }
        val sourceRef = resolveSourceRef(runId)
        log.info(
            "Reclassify hidden-gem: runId={} sourceRef={} path={} target={}",
            runId, sourceRef, path, target
        )

        promoteCrawlConfigRule(sourceRef, path, target)

        val kind = when (target) {
            AnalysisStatus.INDEX -> HiddenGemResolutionKind.PROMOTED_TO_INDEX
            AnalysisStatus.ANALYZE -> HiddenGemResolutionKind.PROMOTED_TO_ANALYZE
            else -> error("unreachable")
        }
        return upsertResolution(sourceRef, path, kind, resolvedBy)
    }

    private fun resolveSourceRef(runId: Long): String {
        val run = folderAuditRunRepository.findById(runId).orElseThrow {
            IllegalArgumentException("FolderAuditRun #$runId not found")
        }
        return run.sourceRef ?: "(unknown)"
    }

    private fun upsertResolution(
        sourceRef: String,
        path: String,
        kind: HiddenGemResolutionKind,
        resolvedBy: String?
    ): HiddenGemResolution {
        val existing = resolutionRepository.findBySourceRefAndPath(sourceRef, path)
        val row = existing ?: HiddenGemResolution().apply {
            this.sourceRef = sourceRef
            this.path = path
        }
        row.resolution = kind
        row.resolvedAt = OffsetDateTime.now()
        if (resolvedBy != null) row.resolvedBy = resolvedBy
        return resolutionRepository.save(row)
    }

    /**
     * Append a path-specific regex to an existing CrawlConfig's
     * `folderPatternsIndex` / `folderPatternsAnalyze` JSON array, or
     * create a minimal CrawlConfig if none exists for this `source_ref`.
     *
     * Pattern shape: `Pattern.quote`-escaped path with a `/.* ` tail so
     * the rule matches the folder and everything under it, but NOT a
     * sibling folder with a similar prefix.
     */
    private fun promoteCrawlConfigRule(sourceRef: String, path: String, target: AnalysisStatus) {
        val crawlNames = sourceRef.split(',').map { it.trim() }.filter { it.isNotBlank() }
        val existing = crawlNames.asSequence()
            .mapNotNull { name -> findCrawlConfigByName(name) }
            .firstOrNull()

        val config = existing ?: createMinimalCrawlConfig(crawlNames.firstOrNull() ?: "audit-promoted")

        val newPattern = pathToRegex(path)
        when (target) {
            AnalysisStatus.INDEX -> config.folderPatternsIndex =
                appendUnique(config.folderPatternsIndex, newPattern)
            AnalysisStatus.ANALYZE -> config.folderPatternsAnalyze =
                appendUnique(config.folderPatternsAnalyze, newPattern)
            else -> error("unreachable: target=$target")
        }
        crawlConfigRepository.save(config)
        log.info(
            "Promoted path={} to {} on CrawlConfig name={} (id={})",
            path, target, config.name, config.id
        )
    }

    private fun findCrawlConfigByName(name: String): CrawlConfig? {
        // Repository lacks a single-key lookup by name only — case-
        // insensitive findAll filter is fine here since CrawlConfig
        // counts are O(tens), not O(thousands).
        return crawlConfigRepository.findAll()
            .firstOrNull { it.name?.equals(name, ignoreCase = true) == true }
    }

    private fun createMinimalCrawlConfig(name: String): CrawlConfig {
        return CrawlConfig().apply {
            this.name = name
            this.uri = "tempo:crawl-config:audit-promoted/${slugify(name)}"
            this.label = name
            this.type = "CRAWL_CONFIG"
            this.enabled = false
            this.version = 0L
        }
    }

    private fun pathToRegex(path: String): String {
        // Escape the literal path and allow any descendant content. Use
        // `\Q...\E` (Pattern.quote) so on-disk specials (dots, plus,
        // parens) survive the round-trip into Pattern.compile.
        val quoted = Pattern.quote(path)
        return "$quoted/.*"
    }

    private fun appendUnique(existingJson: String?, pattern: String): String {
        val current: List<String> = if (existingJson.isNullOrBlank()) {
            emptyList()
        } else {
            try {
                objectMapper.readValue(existingJson, object : TypeReference<List<String>>() {})
            } catch (e: Exception) {
                log.warn("Could not parse existing pattern JSON; replacing: {}", existingJson)
                emptyList()
            }
        }
        if (pattern in current) return existingJson ?: objectMapper.writeValueAsString(current)
        return objectMapper.writeValueAsString(current + pattern)
    }

    private fun slugify(value: String): String {
        val slug = value.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return slug.ifBlank { "default" }
    }
}
