package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.domain.EmailContact
import com.oconeco.spring_search_tempo.base.model.EmailContactDTO
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
interface EmailContactMapper {

    @Mapping(target = "emailAccountId", ignore = true)
    fun updateEmailContactDTO(
        emailContact: EmailContact,
        @MappingTarget emailContactDTO: EmailContactDTO
    ): EmailContactDTO

    @AfterMapping
    fun afterUpdateEmailContactDTO(
        emailContact: EmailContact,
        @MappingTarget emailContactDTO: EmailContactDTO
    ) {
        emailContactDTO.emailAccountId = emailContact.emailAccount?.id
    }
}
