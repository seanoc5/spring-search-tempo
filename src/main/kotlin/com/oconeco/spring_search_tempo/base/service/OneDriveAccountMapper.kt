package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.domain.OneDriveAccount
import com.oconeco.spring_search_tempo.base.model.OneDriveAccountDTO
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingConstants
import org.mapstruct.MappingTarget
import org.mapstruct.ReportingPolicy


@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE
)
interface OneDriveAccountMapper {

    fun updateOneDriveAccountDTO(
        oneDriveAccount: OneDriveAccount,
        @MappingTarget oneDriveAccountDTO: OneDriveAccountDTO
    ): OneDriveAccountDTO

    @Mapping(target = "id", ignore = true)
    fun updateOneDriveAccount(
        oneDriveAccountDTO: OneDriveAccountDTO,
        @MappingTarget oneDriveAccount: OneDriveAccount
    ): OneDriveAccount

}
