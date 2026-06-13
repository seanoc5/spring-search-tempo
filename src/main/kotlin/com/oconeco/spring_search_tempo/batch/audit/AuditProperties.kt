package com.oconeco.spring_search_tempo.batch.audit

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * Configuration properties for the folder audit (issues #103, #105).
 *
 * - [hiddenGemPeekDepth] — levels to descend below a detected SKIP root
 *   when the visitor returns SKIP_SUBTREE. The cost-control knob.
 * - [weeklyCron] — Spring cron expression for [FolderAuditScheduler].
 * - [weeklyEnabled] — kill switch for the scheduled fire (the REST endpoint
 *   and admin button keep working regardless).
 * - [retainRuns] — snapshot rotation keeps only the latest N runs;
 *   older `folder_audit_run` + `folder_snapshot` rows are pruned after a
 *   successful run. `hidden_gem_resolution` rows are deliberately not
 *   touched (they are durable, see issue #103/B).
 */
@Configuration
@ConfigurationProperties(prefix = "app.audit")
data class AuditProperties(
    var hiddenGemPeekDepth: Int = 1,
    var weeklyCron: String = "0 0 3 * * SUN",
    var weeklyEnabled: Boolean = true,
    var retainRuns: Int = 4
)
