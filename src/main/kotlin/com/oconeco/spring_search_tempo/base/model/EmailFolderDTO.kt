package com.oconeco.spring_search_tempo.base.model

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.Status
import jakarta.validation.constraints.NotNull


class EmailFolderDTO {

    var id: Long? = null

    @NotNull
    var uri: String? = null

    var status: Status? = Status.NEW

    var analysisStatus: AnalysisStatus? = AnalysisStatus.LOCATE

    var label: String? = null

    var description: String? = null

    var type: String? = null

    var crawlDepth: Int? = null

    var size: Long? = null

    @NotNull
    var version: Long? = null

    var archived: Boolean? = null

    var jobRunId: Long? = null

    // Folder-specific fields
    @NotNull
    var folderName: String? = null

    var fullPath: String? = null

    // Sync state
    var lastSyncUid: Long? = null

    var messageCount: Long = 0

    var uidValidity: Long? = null

    // Folder type flags
    var isInbox: Boolean = false

    var isSent: Boolean = false

    var isDraft: Boolean = false

    var isTrash: Boolean = false

    var isSpam: Boolean = false

    var isArchive: Boolean = false

    // Relationship (as ID)
    var emailAccount: Long? = null

}
