package com.oconeco.spring_search_tempo.base.model

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.Status
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.OffsetDateTime


class MirrorConfigDTO {

    var id: Long? = null

    var uri: String? = null

    var status: Status? = Status.NEW

    var analysisStatus: AnalysisStatus? = AnalysisStatus.LOCATE

    var label: String? = null

    var description: String? = null

    @NotNull
    var version: Long? = 0L

    @NotNull
    var sourceAccountId: Long? = null

    @NotNull
    var destAccountId: Long? = null

    @field:NotBlank
    var name: String? = null

    var enabled: Boolean = true

    /**
     * Structured folder-mapping rows. Convenience for callers; the underlying
     * entity stores these as a JSON string. The service layer is responsible
     * for serialization/deserialization.
     */
    var folderMappings: List<FolderMapping> = emptyList()

    var appendRateLimitPerSecond: Int? = 10

    var lastRunStartedAt: OffsetDateTime? = null

    var lastRunCompletedAt: OffsetDateTime? = null

    var lastError: String? = null

    var dateCreated: OffsetDateTime? = null

    var lastUpdated: OffsetDateTime? = null
}
