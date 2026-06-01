package com.oconeco.spring_search_tempo.batch.mirror

/**
 * One unit of work for `MirrorJob`: a single source UID inside one
 * folder-mapping row of the active `MirrorConfig`. The reader emits
 * these in ascending-UID order within each folder; the processor
 * passes them to `ImapMirrorService.mirrorMessage(...)`.
 */
data class MirrorTask(
    val mirrorConfigId: Long,
    val sourceFolder: String,
    val destFolder: String,
    val sourceUid: Long
)
