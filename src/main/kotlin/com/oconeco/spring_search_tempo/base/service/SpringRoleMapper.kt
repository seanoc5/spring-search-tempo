package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.domain.SpringRole
import com.oconeco.spring_search_tempo.base.model.SpringRoleDTO
import com.oconeco.spring_search_tempo.base.repos.SpringUserRepository
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
interface SpringRoleMapper {

    @Mapping(
        target = "springUser",
        ignore = true
    )
    fun updateSpringRoleDTO(springRole: SpringRole, @MappingTarget springRoleDTO: SpringRoleDTO):
            SpringRoleDTO

    @AfterMapping
    fun afterUpdateSpringRoleDTO(springRole: SpringRole, @MappingTarget
            springRoleDTO: SpringRoleDTO) {
        springRoleDTO.springUser = springRole.springUser?.id
    }

    @Mapping(
        target = "id",
        ignore = true
    )
    @Mapping(
        target = "springUser",
        ignore = true
    )
    fun updateSpringRole(
        springRoleDTO: SpringRoleDTO,
        @MappingTarget springRole: SpringRole,
        @Context springUserRepository: SpringUserRepository
    ): SpringRole

    @AfterMapping
    fun afterUpdateSpringRole(
        springRoleDTO: SpringRoleDTO,
        @MappingTarget springRole: SpringRole,
        @Context springUserRepository: SpringUserRepository
    ) {
        val springUser = if (springRoleDTO.springUser == null) null else
                springUserRepository.findById(springRoleDTO.springUser!!)
                .orElseThrow { NotFoundException("springUser not found") }
        springRole.springUser = springUser
    }

}
