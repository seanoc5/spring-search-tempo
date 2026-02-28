package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.domain.EmailMessage
import com.oconeco.spring_search_tempo.base.model.EmailMessageDTO
import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
import com.oconeco.spring_search_tempo.base.repos.EmailFolderRepository
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
interface EmailMessageMapper {

    @Mapping(target = "emailAccount", ignore = true)
    @Mapping(target = "emailFolder", ignore = true)
    @Mapping(target = "tags", ignore = true)
    fun updateEmailMessageDTO(
        emailMessage: EmailMessage,
        @MappingTarget emailMessageDTO: EmailMessageDTO
    ): EmailMessageDTO

    @AfterMapping
    fun afterUpdateEmailMessageDTO(
        emailMessage: EmailMessage,
        @MappingTarget emailMessageDTO: EmailMessageDTO
    ) {
        emailMessageDTO.emailAccount = emailMessage.emailAccount?.id
        emailMessageDTO.emailFolder = emailMessage.emailFolder?.id
    }

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "emailAccount", ignore = true)
    @Mapping(target = "emailFolder", ignore = true)
    fun updateEmailMessage(
        emailMessageDTO: EmailMessageDTO,
        @MappingTarget emailMessage: EmailMessage,
        @Context emailAccountRepository: EmailAccountRepository,
        @Context emailFolderRepository: EmailFolderRepository
    ): EmailMessage

    @AfterMapping
    fun afterUpdateEmailMessage(
        emailMessageDTO: EmailMessageDTO,
        @MappingTarget emailMessage: EmailMessage,
        @Context emailAccountRepository: EmailAccountRepository,
        @Context emailFolderRepository: EmailFolderRepository
    ) {
        val emailAccount = if (emailMessageDTO.emailAccount == null) null else
            emailAccountRepository.findById(emailMessageDTO.emailAccount!!)
                .orElseThrow { NotFoundException("emailAccount not found") }
        emailMessage.emailAccount = emailAccount

        val emailFolder = if (emailMessageDTO.emailFolder == null) null else
            emailFolderRepository.findById(emailMessageDTO.emailFolder!!)
                .orElseThrow { NotFoundException("emailFolder not found") }
        emailMessage.emailFolder = emailFolder
    }

}
