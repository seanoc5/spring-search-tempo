package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.domain.SpringUser
import com.oconeco.spring_search_tempo.base.model.SpringUserDTO
import org.mapstruct.AfterMapping
import org.mapstruct.Context
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingConstants
import org.mapstruct.MappingTarget
import org.mapstruct.ReportingPolicy
import org.springframework.security.crypto.password.PasswordEncoder


@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE
)
interface SpringUserMapper {

    @Mapping(
        target = "password",
        ignore = true
    )
    fun updateSpringUserDTO(springUser: SpringUser, @MappingTarget springUserDTO: SpringUserDTO):
            SpringUserDTO

    @Mapping(
        target = "id",
        ignore = true
    )
    @Mapping(
        target = "password",
        ignore = true
    )
    fun updateSpringUser(
        springUserDTO: SpringUserDTO,
        @MappingTarget springUser: SpringUser,
        @Context passwordEncoder: PasswordEncoder
    ): SpringUser

    @AfterMapping
    fun afterUpdateSpringUser(
        springUserDTO: SpringUserDTO,
        @MappingTarget springUser: SpringUser,
        @Context passwordEncoder: PasswordEncoder
    ) {
        springUser.password = passwordEncoder.encode(springUserDTO.password)
    }

}
