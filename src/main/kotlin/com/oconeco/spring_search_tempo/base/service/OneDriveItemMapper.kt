package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.domain.OneDriveItem
import com.oconeco.spring_search_tempo.base.model.OneDriveItemDTO
import org.mapstruct.AfterMapping
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingConstants
import org.mapstruct.MappingTarget
import org.mapstruct.ReportingPolicy


@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE
)
interface OneDriveItemMapper {

    @Mapping(target = "oneDriveAccount", source = "oneDriveAccount.id")
    fun toDto(oneDriveItem: OneDriveItem): OneDriveItemDTO

    @Mapping(target = "oneDriveAccount", ignore = true)
    fun updateOneDriveItemDTO(
        oneDriveItem: OneDriveItem,
        @MappingTarget oneDriveItemDTO: OneDriveItemDTO
    ): OneDriveItemDTO

    @AfterMapping
    fun afterUpdateOneDriveItemDTO(
        oneDriveItem: OneDriveItem,
        @MappingTarget oneDriveItemDTO: OneDriveItemDTO
    ) {
        oneDriveItemDTO.oneDriveAccount = oneDriveItem.oneDriveAccount?.id
    }

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "oneDriveAccount", ignore = true)
    @Mapping(target = "ftsVector", ignore = true)
    fun updateOneDriveItem(
        oneDriveItemDTO: OneDriveItemDTO,
        @MappingTarget oneDriveItem: OneDriveItem
    ): OneDriveItem

}
