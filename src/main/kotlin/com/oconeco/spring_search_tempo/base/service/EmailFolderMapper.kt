package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.domain.EmailFolder
import com.oconeco.spring_search_tempo.base.model.EmailFolderDTO
import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
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
interface EmailFolderMapper {

    @Mapping(target = "emailAccount", ignore = true)
    fun updateEmailFolderDTO(
        emailFolder: EmailFolder,
        @MappingTarget emailFolderDTO: EmailFolderDTO
    ): EmailFolderDTO

    @AfterMapping
    fun afterUpdateEmailFolderDTO(
        emailFolder: EmailFolder,
        @MappingTarget emailFolderDTO: EmailFolderDTO
    ) {
        emailFolderDTO.emailAccount = emailFolder.emailAccount?.id
    }

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "emailAccount", ignore = true)
    fun updateEmailFolder(
        emailFolderDTO: EmailFolderDTO,
        @MappingTarget emailFolder: EmailFolder,
        @Context emailAccountRepository: EmailAccountRepository
    ): EmailFolder

    @AfterMapping
    fun afterUpdateEmailFolder(
        emailFolderDTO: EmailFolderDTO,
        @MappingTarget emailFolder: EmailFolder,
        @Context emailAccountRepository: EmailAccountRepository
    ) {
        val emailAccount = if (emailFolderDTO.emailAccount == null) null else
            emailAccountRepository.findById(emailFolderDTO.emailAccount!!)
                .orElseThrow { NotFoundException("emailAccount not found") }
        emailFolder.emailAccount = emailAccount
    }

}
