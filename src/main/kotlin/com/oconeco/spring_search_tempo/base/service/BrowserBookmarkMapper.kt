package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.domain.BrowserBookmark
import com.oconeco.spring_search_tempo.base.model.BrowserBookmarkDTO
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingConstants
import org.mapstruct.MappingTarget
import org.mapstruct.ReportingPolicy


@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE
)
interface BrowserBookmarkMapper {

    @Mapping(target = "browserProfileId", source = "browserProfile.id")
    @Mapping(target = "tagNames", ignore = true)
    fun updateBrowserBookmarkDTO(
        browserBookmark: BrowserBookmark,
        @MappingTarget browserBookmarkDTO: BrowserBookmarkDTO
    ): BrowserBookmarkDTO

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "browserProfile", ignore = true)
    @Mapping(target = "tags", ignore = true)
    fun updateBrowserBookmark(
        browserBookmarkDTO: BrowserBookmarkDTO,
        @MappingTarget browserBookmark: BrowserBookmark
    ): BrowserBookmark

}
