package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.model.FSFileDTO
import com.oconeco.spring_search_tempo.base.model.FSFolderDTO

/**
 * Result of processing a combined directory+files item.
 * Contains the folder DTO and list of file DTOs to be persisted.
 *
 * @param folder The processed folder DTO (null if folder should be skipped/ignored)
 * @param files List of processed file DTOs (excludes ignored/unchanged files)
 */
data class CombinedCrawlResult(
    val folder: FSFolderDTO?,
    val files: List<FSFileDTO>
)
