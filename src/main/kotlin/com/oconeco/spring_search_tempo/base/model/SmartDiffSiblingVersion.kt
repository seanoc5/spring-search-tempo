package com.oconeco.spring_search_tempo.base.model

import java.time.OffsetDateTime

/**
 * Lightweight projection of a sibling-version candidate surfaced in the
 * "Compare with..." dropdown on the FSFile detail page.
 *
 * Issue #144.
 */
data class SmartDiffSiblingVersion(
    val id: Long,
    val uri: String?,
    val label: String?,
    val contentHash: String?,
    val size: Long?,
    val fsLastModified: OffsetDateTime?,
)
