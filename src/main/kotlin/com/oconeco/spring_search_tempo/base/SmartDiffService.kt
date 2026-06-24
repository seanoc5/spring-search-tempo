package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.model.SmartDiffResult
import com.oconeco.spring_search_tempo.base.model.SmartDiffSiblingVersion

/**
 * Entry point for the smart-diff feature: pairing FSFile versions and
 * dispatching to the appropriate per-format strategy.
 *
 * Issue #144 (graduates spike #126).
 */
interface SmartDiffService {

    /**
     * Sibling versions of the given file, ordered by [com.oconeco.spring_search_tempo.base.domain.FSObject.fsLastModified]
     * descending then by id descending. A "sibling" is another FSFile that shares
     * the same [com.oconeco.spring_search_tempo.base.SaveableObject.label] (or normalised
     * basename, when label is null) but a different [com.oconeco.spring_search_tempo.base.domain.FSFile.contentHash].
     *
     * The original file is excluded. Files without a contentHash are excluded
     * because we can only assert "different bytes" once both sides are hashed.
     */
    fun findSiblingVersions(fileId: Long, limit: Int = 25): List<SmartDiffSiblingVersion>

    /**
     * True when at least one strategy on the classpath claims to support the
     * given content-type. Used by the UI to decide whether to surface the
     * "Compare with..." panel at all.
     */
    fun isSupported(contentType: String?): Boolean

    /**
     * Diff the two files. The strategy is chosen from the *new* file's
     * contentType (with the old file's contentType as a fallback when the new
     * one is missing).
     *
     * @throws IllegalArgumentException if either id does not resolve, or no
     *   strategy supports the resolved content-type.
     */
    fun diff(oldFileId: Long, newFileId: Long): SmartDiffResult
}
