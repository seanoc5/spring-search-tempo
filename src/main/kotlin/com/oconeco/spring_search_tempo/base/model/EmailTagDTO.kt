package com.oconeco.spring_search_tempo.base.model

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime


class EmailTagDTO {

    var id: Long? = null

    @NotBlank
    @Size(min = 1, max = 100)
    var name: String? = null

    @NotBlank
    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Color must be a valid hex code (e.g., #ff5733)")
    var color: String = "#6c757d"

    var isSystem: Boolean = false

    var dateCreated: OffsetDateTime? = null

    var lastUpdated: OffsetDateTime? = null

    /**
     * Denormalized count of messages with this tag.
     * Only populated by findAllWithCounts().
     */
    var messageCount: Long? = null

}
