package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.domain.EmailAccount
import com.oconeco.spring_search_tempo.base.model.EmailAccountDTO
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingConstants
import org.mapstruct.MappingTarget
import org.mapstruct.ReportingPolicy


@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE
)
interface EmailAccountMapper {

    fun updateEmailAccountDTO(
        emailAccount: EmailAccount,
        @MappingTarget emailAccountDTO: EmailAccountDTO
    ): EmailAccountDTO

    @Mapping(target = "id", ignore = true)
    fun updateEmailAccount(
        emailAccountDTO: EmailAccountDTO,
        @MappingTarget emailAccount: EmailAccount
    ): EmailAccount

}
