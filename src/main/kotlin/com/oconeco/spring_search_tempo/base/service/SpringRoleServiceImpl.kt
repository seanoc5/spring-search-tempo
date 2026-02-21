package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.SpringRoleService
import com.oconeco.spring_search_tempo.base.domain.SpringRole
import com.oconeco.spring_search_tempo.base.events.BeforeDeleteSpringUser
import com.oconeco.spring_search_tempo.base.model.SpringRoleDTO
import com.oconeco.spring_search_tempo.base.repos.SpringRoleRepository
import com.oconeco.spring_search_tempo.base.repos.SpringUserRepository
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import com.oconeco.spring_search_tempo.base.util.ReferencedException
import org.springframework.context.event.EventListener
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional


@Service
@Transactional
class SpringRoleServiceImpl(
    private val springRoleRepository: SpringRoleRepository,
    private val springUserRepository: SpringUserRepository,
    private val springRoleMapper: SpringRoleMapper
) : SpringRoleService {

    @Transactional(readOnly = true)
    override fun findAll(pageable: Pageable): Page<SpringRoleDTO> {
        val page = springRoleRepository.findAll(pageable)
        return PageImpl(page.content
                .map { springRole -> springRoleMapper.updateSpringRoleDTO(springRole,
                SpringRoleDTO()) },
                pageable, page.totalElements)
    }

    @Transactional(readOnly = true)
    override fun `get`(id: Long): SpringRoleDTO = springRoleRepository.findById(id)
            .map { springRole -> springRoleMapper.updateSpringRoleDTO(springRole, SpringRoleDTO()) }
            .orElseThrow { NotFoundException() }

    override fun create(springRoleDTO: SpringRoleDTO): Long {
        val springRole = SpringRole()
        springRoleMapper.updateSpringRole(springRoleDTO, springRole, springUserRepository)
        return springRoleRepository.save(springRole).id!!
    }

    override fun update(id: Long, springRoleDTO: SpringRoleDTO) {
        val springRole = springRoleRepository.findById(id)
                .orElseThrow { NotFoundException() }
        springRoleMapper.updateSpringRole(springRoleDTO, springRole, springUserRepository)
        springRoleRepository.save(springRole)
    }

    override fun delete(id: Long) {
        val springRole = springRoleRepository.findById(id)
                .orElseThrow { NotFoundException() }
        springRoleRepository.delete(springRole)
    }

    @Transactional(readOnly = true)
    override fun labelExists(label: String?): Boolean = springRoleRepository.existsByLabel(label)

    @EventListener(BeforeDeleteSpringUser::class)
    fun on(event: BeforeDeleteSpringUser) {
        val referencedException = ReferencedException()
        val springUserSpringRole = springRoleRepository.findFirstBySpringUserId(event.id)
        if (springUserSpringRole != null) {
            referencedException.key = "springUser.springRole.springUser.referenced"
            referencedException.addParam(springUserSpringRole.id)
            throw referencedException
        }
    }

}
