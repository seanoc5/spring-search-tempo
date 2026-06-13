package com.oconeco.spring_search_tempo.base.model

import java.time.OffsetDateTime

/**
 * One group of files sharing the same `(label, size, fsLastModified)`
 * triple — the metadata fingerprint used by the duplicate-finder
 * admin view (issue #120).
 *
 * `count` is the number of files in the group (always > 1 — singletons
 * are excluded at the query level). The controller pulls the actual
 * `FSFile` rows for each group on demand and renders the distinct
 * posix owners / groups / modes to surface UID drift.
 */
data class MetadataDuplicateGroup(
    val label: String,
    val size: Long,
    val fsLastModified: OffsetDateTime,
    val count: Long,
)
