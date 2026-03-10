package com.oconeco.spring_search_tempo.base.domain

/**
 * Temperature tiers for smart crawl scheduling.
 * Folders are classified based on their modification patterns:
 * - HOT: Frequently changing, crawl every session
 * - WARM: Moderately active, crawl daily
 * - COLD: Rarely changing, crawl weekly
 */
enum class CrawlTemperature {
    HOT,    // Modified in last 7 days (or high change score) → crawl every session
    WARM,   // Modified in last 30 days → crawl daily
    COLD    // No changes in 30+ days → crawl weekly
}
