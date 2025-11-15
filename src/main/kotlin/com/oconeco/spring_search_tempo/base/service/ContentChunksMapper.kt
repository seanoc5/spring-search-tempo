package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.domain.ContentChunks
import com.oconeco.spring_search_tempo.base.model.ContentChunksDTO
import com.oconeco.spring_search_tempo.base.repos.ContentChunksRepository
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.mapstruct.AfterMapping
import org.mapstruct.Context
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingConstants
import org.mapstruct.MappingTarget
import org.mapstruct.ReportingPolicy


@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE
)
interface ContentChunksMapper {

    @Mapping(
        target = "parentChunk",
        ignore = true
    )
    @Mapping(
        target = "concept",
        ignore = true
    )
    fun updateContentChunksDTO(contentChunks: ContentChunks, @MappingTarget
            contentChunksDTO: ContentChunksDTO): ContentChunksDTO

    @AfterMapping
    fun afterUpdateContentChunksDTO(contentChunks: ContentChunks, @MappingTarget
            contentChunksDTO: ContentChunksDTO) {
        contentChunksDTO.parentChunk = contentChunks.parentChunk?.id
        contentChunksDTO.concept = contentChunks.concept?.id
    }

    @Mapping(
        target = "id",
        ignore = true
    )
    @Mapping(
        target = "parentChunk",
        ignore = true
    )
    @Mapping(
        target = "concept",
        ignore = true
    )
    @Mapping(
        target = "ftsVector",
        ignore = true
    )
    fun updateContentChunks(
        contentChunksDTO: ContentChunksDTO,
        @MappingTarget contentChunks: ContentChunks,
        @Context contentChunksRepository: ContentChunksRepository,
        @Context fSFileRepository: FSFileRepository
    ): ContentChunks

    @AfterMapping
    fun afterUpdateContentChunks(
        contentChunksDTO: ContentChunksDTO,
        @MappingTarget contentChunks: ContentChunks,
        @Context contentChunksRepository: ContentChunksRepository,
        @Context fSFileRepository: FSFileRepository
    ) {
        val parentChunk = if (contentChunksDTO.parentChunk == null) null else
                contentChunksRepository.findById(contentChunksDTO.parentChunk!!)
                .orElseThrow { NotFoundException("parentChunk not found") }
        contentChunks.parentChunk = parentChunk
        val concept = if (contentChunksDTO.concept == null) null else
                fSFileRepository.findById(contentChunksDTO.concept!!)
                .orElseThrow { NotFoundException("concept not found") }
        contentChunks.concept = concept
    }

}
