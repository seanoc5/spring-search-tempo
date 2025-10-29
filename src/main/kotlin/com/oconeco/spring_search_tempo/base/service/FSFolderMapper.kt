package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.domain.FSFolder
import com.oconeco.spring_search_tempo.base.model.FSFolderDTO
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingConstants
import org.mapstruct.MappingTarget
import org.mapstruct.ReportingPolicy


@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE
)
interface FSFolderMapper {

    fun updateFSFolderDTO(fSFolder: FSFolder, @MappingTarget fSFolderDTO: FSFolderDTO): FSFolderDTO

    @Mapping(
        target = "id",
        ignore = true
    )
    fun updateFSFolder(fSFolderDTO: FSFolderDTO, @MappingTarget fSFolder: FSFolder): FSFolder

}
