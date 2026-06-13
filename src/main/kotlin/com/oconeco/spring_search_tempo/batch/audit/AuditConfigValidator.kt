package com.oconeco.spring_search_tempo.batch.audit

import com.oconeco.spring_search_tempo.base.config.CrawlConfiguration
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Startup validation for audit configuration (issue #105).
 *
 * Fails the application context if `app.audit.hidden-gem-peek-depth` is
 * outside the legal range bounded by `app.crawl.absolute-max-depth`. A
 * misconfigured peek depth would cause the audit walker to either run
 * no SKIP descent at all (< 1) or attempt to descend past the absolute
 * backstop — caught at startup so an operator gets a clear YAML-pointed
 * error rather than a runtime surprise on the Sunday cron fire.
 */
@Component
class AuditConfigValidator(
    private val auditProperties: AuditProperties,
    private val crawlConfiguration: CrawlConfiguration
) {

    companion object {
        private val log = LoggerFactory.getLogger(AuditConfigValidator::class.java)
    }

    @PostConstruct
    fun validate() {
        val peek = auditProperties.hiddenGemPeekDepth
        val absolute = crawlConfiguration.absoluteMaxDepth

        if (peek < 1) {
            throw IllegalStateException(
                "Invalid audit configuration: app.audit.hidden-gem-peek-depth=$peek " +
                    "must be >= 1 (peek-depth controls how far the audit descends under " +
                    "a SKIP root, so anything below 1 disables SKIP-root recording entirely)."
            )
        }
        if (peek > absolute) {
            throw IllegalStateException(
                "Invalid audit configuration: app.audit.hidden-gem-peek-depth=$peek " +
                    "must be <= app.crawl.absolute-max-depth=$absolute " +
                    "(the absolute cap is the cross-cutting backstop on walker depth; " +
                    "peek-depth descends below SKIP roots and so cannot exceed it). " +
                    "Either lower app.audit.hidden-gem-peek-depth or raise app.crawl.absolute-max-depth."
            )
        }

        log.info(
            "Audit config validated: hidden-gem-peek-depth={}, absolute-max-depth={}, retain-runs={}, weekly-cron='{}'",
            peek, absolute, auditProperties.retainRuns, auditProperties.weeklyCron
        )
    }
}
