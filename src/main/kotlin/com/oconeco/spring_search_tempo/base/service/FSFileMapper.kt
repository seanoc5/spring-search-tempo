package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.domain.FSFile
import com.oconeco.spring_search_tempo.base.model.FSFileDTO
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
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
interface FSFileMapper {

    @Mapping(
        target = "fsFolder",
        ignore = true
    )
    fun updateFSFileDTO(fSFile: FSFile, @MappingTarget fSFileDTO: FSFileDTO): FSFileDTO

    @AfterMapping
    fun afterUpdateFSFileDTO(fSFile: FSFile, @MappingTarget fSFileDTO: FSFileDTO) {
        fSFileDTO.fsFolder = fSFile.fsFolder?.id
    }

    @Mapping(
        target = "id",
        ignore = true
    )
    @Mapping(
        target = "fsFolder",
        ignore = true
    )
    fun updateFSFile(
        fSFileDTO: FSFileDTO,
        @MappingTarget fSFile: FSFile,
        @Context fSFolderRepository: FSFolderRepository
    ): FSFile

    @AfterMapping
    fun afterUpdateFSFile(
        fSFileDTO: FSFileDTO,
        @MappingTarget fSFile: FSFile,
        @Context fSFolderRepository: FSFolderRepository
    ) {
        val fsFolder = if (fSFileDTO.fsFolder == null) null else
                fSFolderRepository.findById(fSFileDTO.fsFolder!!)
                .orElseThrow { NotFoundException("fsFolder not found") }
        fSFile.fsFolder = fsFolder
    }

}
