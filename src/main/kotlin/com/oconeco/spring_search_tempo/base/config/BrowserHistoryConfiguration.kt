package com.oconeco.spring_search_tempo.base.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration


/**
 * Configuration properties for browser history import.
 *
 * Example configuration:
 * ```yaml
 * app:
 *   browser:
 *     history:
 *       enabled: true
 *       retention-days: 365   # ignore history older than this; null = no cutoff
 *       auto-trigger: true    # run history sync after bookmark sync
 * ```
 */
@Configuration
@ConfigurationProperties(prefix = "app.browser.history")
data class BrowserHistoryConfiguration(
    var enabled: Boolean = true,
    var retentionDays: Int? = 365,
    var autoTrigger: Boolean = true
)
