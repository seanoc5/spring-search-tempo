package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.domain.BrowserProfile
import com.oconeco.spring_search_tempo.base.model.BrowserProfileDTO
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingConstants
import org.mapstruct.MappingTarget
import org.mapstruct.ReportingPolicy


@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE
)
interface BrowserProfileMapper {

    fun updateBrowserProfileDTO(
        browserProfile: BrowserProfile,
        @MappingTarget browserProfileDTO: BrowserProfileDTO
    ): BrowserProfileDTO

    @Mapping(target = "id", ignore = true)
    fun updateBrowserProfile(
        browserProfileDTO: BrowserProfileDTO,
        @MappingTarget browserProfile: BrowserProfile
    ): BrowserProfile

}
