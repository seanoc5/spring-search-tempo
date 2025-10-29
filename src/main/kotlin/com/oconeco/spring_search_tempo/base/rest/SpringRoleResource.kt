package com.oconeco.spring_search_tempo.base.rest

import com.oconeco.spring_search_tempo.base.SpringRoleService
import com.oconeco.spring_search_tempo.base.model.SimpleValue
import com.oconeco.spring_search_tempo.base.model.SpringRoleDTO
import jakarta.validation.Valid
import java.lang.Void
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.data.web.PagedResourcesAssembler
import org.springframework.data.web.SortDefault
import org.springframework.hateoas.EntityModel
import org.springframework.hateoas.PagedModel
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping(
    value = ["/api/springRoles"],
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class SpringRoleResource(
    private val springRoleService: SpringRoleService,
    private val springRoleAssembler: SpringRoleAssembler,
    private val pagedResourcesAssembler: PagedResourcesAssembler<SpringRoleDTO>
) {

    @GetMapping
    fun getAllSpringRoles(@SortDefault(sort = ["id"]) @PageableDefault(size = 20)
            pageable: Pageable): ResponseEntity<PagedModel<EntityModel<SpringRoleDTO>>> {
        val springRoleDTOs = springRoleService.findAll(pageable)
        return ResponseEntity.ok(pagedResourcesAssembler.toModel(springRoleDTOs,
                springRoleAssembler))
    }

    @GetMapping("/{id}")
    fun getSpringRole(@PathVariable(name = "id") id: Long):
            ResponseEntity<EntityModel<SpringRoleDTO>> {
        val springRoleDTO = springRoleService.get(id)
        return ResponseEntity.ok(springRoleAssembler.toModel(springRoleDTO))
    }

    @PostMapping
    fun createSpringRole(@RequestBody @Valid springRoleDTO: SpringRoleDTO):
            ResponseEntity<EntityModel<SimpleValue<Long>>> {
        val createdId = springRoleService.create(springRoleDTO)
        return ResponseEntity(springRoleAssembler.toSimpleModel(createdId), HttpStatus.CREATED)
    }

    @PutMapping("/{id}")
    fun updateSpringRole(@PathVariable(name = "id") id: Long, @RequestBody @Valid
            springRoleDTO: SpringRoleDTO): ResponseEntity<EntityModel<SimpleValue<Long>>> {
        springRoleService.update(id, springRoleDTO)
        return ResponseEntity.ok(springRoleAssembler.toSimpleModel(id))
    }

    @DeleteMapping("/{id}")
    fun deleteSpringRole(@PathVariable(name = "id") id: Long): ResponseEntity<Void> {
        springRoleService.delete(id)
        return ResponseEntity.noContent().build()
    }

}
