package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.domain.ContentChunk
import com.oconeco.spring_search_tempo.base.model.ContentChunkDTO
import com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository
import com.oconeco.spring_search_tempo.base.repos.EmailMessageRepository
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
interface ContentChunkMapper {

    /**
     * Map entity to DTO.
     */
    @Mapping(target = "parentChunk", source = "parentChunk.id")
    @Mapping(target = "concept", source = "concept.id")
    @Mapping(target = "emailMessage", source = "emailMessage.id")
    fun toDto(contentChunk: ContentChunk): ContentChunkDTO

    @Mapping(
        target = "parentChunk",
        ignore = true
    )
    @Mapping(
        target = "concept",
        ignore = true
    )
    @Mapping(
        target = "emailMessage",
        ignore = true
    )
    fun updateContentChunkDTO(contentChunk: ContentChunk, @MappingTarget
            contentChunkDTO: ContentChunkDTO): ContentChunkDTO

    @AfterMapping
    fun afterUpdateContentChunkDTO(contentChunk: ContentChunk, @MappingTarget
            contentChunkDTO: ContentChunkDTO) {
        contentChunkDTO.parentChunk = contentChunk.parentChunk?.id
        contentChunkDTO.concept = contentChunk.concept?.id
        contentChunkDTO.emailMessage = contentChunk.emailMessage?.id
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
        target = "emailMessage",
        ignore = true
    )
    @Mapping(
        target = "ftsVector",
        ignore = true
    )
    @Mapping(
        target = "embedding",
        ignore = true
    )
    fun updateContentChunk(
        contentChunkDTO: ContentChunkDTO,
        @MappingTarget contentChunk: ContentChunk,
        @Context contentChunkRepository: ContentChunkRepository,
        @Context fSFileRepository: FSFileRepository,
        @Context emailMessageRepository: EmailMessageRepository
    ): ContentChunk

    @AfterMapping
    fun afterUpdateContentChunk(
        contentChunkDTO: ContentChunkDTO,
        @MappingTarget contentChunk: ContentChunk,
        @Context contentChunkRepository: ContentChunkRepository,
        @Context fSFileRepository: FSFileRepository,
        @Context emailMessageRepository: EmailMessageRepository
    ) {
        val parentChunk = if (contentChunkDTO.parentChunk == null) null else
                contentChunkRepository.findById(contentChunkDTO.parentChunk!!)
                .orElseThrow { NotFoundException("parentChunk not found") }
        contentChunk.parentChunk = parentChunk

        val concept = if (contentChunkDTO.concept == null) null else
                fSFileRepository.findById(contentChunkDTO.concept!!)
                .orElseThrow { NotFoundException("concept not found") }
        contentChunk.concept = concept

        val emailMessage = if (contentChunkDTO.emailMessage == null) null else
                emailMessageRepository.findById(contentChunkDTO.emailMessage!!)
                .orElseThrow { NotFoundException("emailMessage not found") }
        contentChunk.emailMessage = emailMessage
    }

}
