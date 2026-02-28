package com.oconeco.spring_search_tempo.base.model

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.OneDriveFetchStatus
import com.oconeco.spring_search_tempo.base.domain.Status
import java.time.OffsetDateTime


class OneDriveItemDTO {

    var id: Long? = null

    var uri: String? = null

    var status: Status? = Status.NEW

    var analysisStatus: AnalysisStatus? = AnalysisStatus.LOCATE

    var label: String? = null

    var description: String? = null

    var type: String? = null

    var crawlDepth: Int? = null

    var size: Long? = null

    var version: Long? = null

    var archived: Boolean? = null

    var jobRunId: Long? = null

    // Graph item identity
    var graphItemId: String? = null

    var parentGraphItemId: String? = null

    var driveId: String? = null

    // Item metadata
    var itemName: String? = null

    var itemPath: String? = null

    var mimeType: String? = null

    var isFolder: Boolean = false

    var isDeleted: Boolean = false

    var fileHash: String? = null

    var hashAlgorithm: String? = null

    // Graph timestamps
    var graphCreatedAt: OffsetDateTime? = null

    var graphModifiedAt: OffsetDateTime? = null

    // Content
    var bodyText: String? = null

    var bodySize: Long? = null

    var contentType: String? = null

    var author: String? = null

    var title: String? = null

    var pageCount: Int? = null

    // Processing state
    var fetchStatus: OneDriveFetchStatus = OneDriveFetchStatus.METADATA_ONLY

    var chunkedAt: OffsetDateTime? = null

    // Relationships (as IDs)
    var oneDriveAccount: Long? = null

}
