package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.domain.EmailTag
import com.oconeco.spring_search_tempo.base.model.EmailTagDTO
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingConstants
import org.mapstruct.MappingTarget
import org.mapstruct.ReportingPolicy


@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE
)
interface EmailTagMapper {

    fun updateEmailTagDTO(
        emailTag: EmailTag,
        @MappingTarget emailTagDTO: EmailTagDTO
    ): EmailTagDTO

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "system", ignore = true)  // Never let user set isSystem
    fun updateEmailTag(
        emailTagDTO: EmailTagDTO,
        @MappingTarget emailTag: EmailTag
    ): EmailTag

}
