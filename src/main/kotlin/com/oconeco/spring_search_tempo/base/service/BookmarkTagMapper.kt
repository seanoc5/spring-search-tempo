package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.domain.BookmarkTag
import com.oconeco.spring_search_tempo.base.model.BookmarkTagDTO
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingConstants
import org.mapstruct.MappingTarget
import org.mapstruct.ReportingPolicy


@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE
)
interface BookmarkTagMapper {

    fun updateBookmarkTagDTO(
        bookmarkTag: BookmarkTag,
        @MappingTarget bookmarkTagDTO: BookmarkTagDTO
    ): BookmarkTagDTO

    @Mapping(target = "id", ignore = true)
    fun updateBookmarkTag(
        bookmarkTagDTO: BookmarkTagDTO,
        @MappingTarget bookmarkTag: BookmarkTag
    ): BookmarkTag

}
