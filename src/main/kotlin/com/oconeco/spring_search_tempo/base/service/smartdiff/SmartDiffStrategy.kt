package com.oconeco.spring_search_tempo.base.service.smartdiff

import com.oconeco.spring_search_tempo.base.domain.FSFile
import com.oconeco.spring_search_tempo.base.model.SmartDiffResult

/**
 * Per-format smart-diff strategy. Implementations declare the MIME
 * content-types they support and produce a [SmartDiffResult] when handed
 * two FSFile versions.
 *
 * Issue #144 — shared smart-diff infrastructure.
 */
interface SmartDiffStrategy {

    /**
     * MIME content-types this strategy claims, e.g.
     * `application/vnd.openxmlformats-officedocument.wordprocessingml.document` for
     * `.docx`. Matching is exact (case-insensitive) — no wildcards. The
     * dispatcher picks the first registered strategy whose set contains the
     * file's content-type.
     */
    fun supportedContentTypes(): Set<String>

    /**
     * Produce the diff. Implementations may assume both files share the same
     * content-type and that both are readable from local disk (via their `uri`).
     */
    fun diff(oldFile: FSFile, newFile: FSFile): SmartDiffResult
}
