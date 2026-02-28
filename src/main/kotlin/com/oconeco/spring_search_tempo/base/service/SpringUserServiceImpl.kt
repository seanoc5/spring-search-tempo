package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.SpringUserService
import com.oconeco.spring_search_tempo.base.domain.SpringUser
import com.oconeco.spring_search_tempo.base.events.BeforeDeleteSpringUser
import com.oconeco.spring_search_tempo.base.model.SpringUserDTO
import com.oconeco.spring_search_tempo.base.repos.SpringUserRepository
import com.oconeco.spring_search_tempo.base.util.CustomCollectors
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional


@Service
@Transactional
class SpringUserServiceImpl(
    private val springUserRepository: SpringUserRepository,
    private val publisher: ApplicationEventPublisher,
    private val passwordEncoder: PasswordEncoder,
    private val springUserMapper: SpringUserMapper
) : SpringUserService {

    @Transactional(readOnly = true)
    override fun findAll(filter: String?, pageable: Pageable): Page<SpringUserDTO> {
        var page: Page<SpringUser>
        if (filter != null) {
            page = springUserRepository.findAllById(filter.toLongOrNull(), pageable)
        } else {
            page = springUserRepository.findAll(pageable)
        }
        return PageImpl(page.content
                .map { springUser -> springUserMapper.updateSpringUserDTO(springUser,
                SpringUserDTO()) },
                pageable, page.totalElements)
    }

    @Transactional(readOnly = true)
    override fun `get`(id: Long): SpringUserDTO = springUserRepository.findById(id)
            .map { springUser -> springUserMapper.updateSpringUserDTO(springUser, SpringUserDTO()) }
            .orElseThrow { NotFoundException() }

    @Transactional(readOnly = true)
    override fun findByLabel(label: String): SpringUserDTO? =
            springUserRepository.findByLabelIgnoreCase(label)
                ?.let { springUserMapper.updateSpringUserDTO(it, SpringUserDTO()) }

    override fun create(springUserDTO: SpringUserDTO): Long {
        val springUser = SpringUser()
        springUserMapper.updateSpringUser(springUserDTO, springUser, passwordEncoder)
        return springUserRepository.save(springUser).id!!
    }

    override fun update(id: Long, springUserDTO: SpringUserDTO) {
        val springUser = springUserRepository.findById(id)
                .orElseThrow { NotFoundException() }
        springUserMapper.updateSpringUser(springUserDTO, springUser, passwordEncoder)
        springUserRepository.save(springUser)
    }

    override fun delete(id: Long) {
        val springUser = springUserRepository.findById(id)
                .orElseThrow { NotFoundException() }
        publisher.publishEvent(BeforeDeleteSpringUser(id))
        springUserRepository.delete(springUser)
    }

    @Transactional(readOnly = true)
    override fun labelExists(label: String?): Boolean = springUserRepository.existsByLabel(label)

    @Transactional(readOnly = true)
    override fun getSpringUserValues(): Map<Long, String> =
            springUserRepository.findAll(Sort.by("label"))
            .stream()
            .collect(CustomCollectors.toSortedMap(SpringUser::id) { it.label ?: "Unknown" })

}
