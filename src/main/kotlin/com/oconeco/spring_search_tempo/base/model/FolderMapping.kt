package com.oconeco.spring_search_tempo.base.model

import com.fasterxml.jackson.annotation.JsonInclude


/**
 * One row in a [MirrorConfigDTO.folderMappings] list.
 *
 * Represents a single source-folder → destination-folder copy rule within a
 * mirror configuration. Stored as JSON on `MirrorConfig.folderMappings`
 * (see ADR-005).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class FolderMapping(
    val source: String,
    val dest: String,
    val enabled: Boolean = true
)
