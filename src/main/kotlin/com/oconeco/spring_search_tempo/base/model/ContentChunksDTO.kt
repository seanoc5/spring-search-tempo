package com.oconeco.spring_search_tempo.base.model

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import org.springframework.format.annotation.DateTimeFormat


class ContentChunksDTO {

    var id: Long? = null

    @NotNull
    var chunkNumber: Int? = null

    @Size(max = 255)
    var chunkType: String? = null

    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX")
    var createdAt: OffsetDateTime? = null

    var endPosition: Long? = null

    @Size(max = 255)
    var ftsVector: String? = null

    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX")
    var lastModifiedDate: OffsetDateTime? = null

    var namedEntities: String? = null

    var nouns: String? = null

    @Size(max = 255)
    var parentType: String? = null

    var startPosition: Long? = null

    @NotNull
    var text: String? = null

    var tokenAnnotations: String? = null

    @Size(max = 255)
    var vectorEmbedding: String? = null

    var verbs: String? = null

    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX")
    var solrIndexedAt: OffsetDateTime? = null

    var textLength: Long? = null

    var userAnnotations: String? = null

    @Size(max = 255)
    var status: String? = null

    var parseNpvp: String? = null

    var parseUd: String? = null

    var parseTree: String? = null

    var conllu: String? = null

    var parentChunk: Long? = null

    var concept: Long? = null

}
