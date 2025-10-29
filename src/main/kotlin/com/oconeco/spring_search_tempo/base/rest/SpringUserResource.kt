package com.oconeco.spring_search_tempo.base.rest

import com.oconeco.spring_search_tempo.base.SpringUserService
import com.oconeco.spring_search_tempo.base.model.SimpleValue
import com.oconeco.spring_search_tempo.base.model.SpringUserDTO
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping(
    value = ["/api/springUsers"],
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class SpringUserResource(
    private val springUserService: SpringUserService,
    private val springUserAssembler: SpringUserAssembler,
    private val pagedResourcesAssembler: PagedResourcesAssembler<SpringUserDTO>
) {

    @GetMapping
    fun getAllSpringUsers(@RequestParam(name = "filter", required = false) filter: String?,
            @SortDefault(sort = ["id"]) @PageableDefault(size = 20) pageable: Pageable):
            ResponseEntity<PagedModel<EntityModel<SpringUserDTO>>> {
        val springUserDTOs = springUserService.findAll(filter, pageable)
        return ResponseEntity.ok(pagedResourcesAssembler.toModel(springUserDTOs,
                springUserAssembler))
    }

    @GetMapping("/{id}")
    fun getSpringUser(@PathVariable(name = "id") id: Long):
            ResponseEntity<EntityModel<SpringUserDTO>> {
        val springUserDTO = springUserService.get(id)
        return ResponseEntity.ok(springUserAssembler.toModel(springUserDTO))
    }

    @PostMapping
    fun createSpringUser(@RequestBody @Valid springUserDTO: SpringUserDTO):
            ResponseEntity<EntityModel<SimpleValue<Long>>> {
        val createdId = springUserService.create(springUserDTO)
        return ResponseEntity(springUserAssembler.toSimpleModel(createdId), HttpStatus.CREATED)
    }

    @PutMapping("/{id}")
    fun updateSpringUser(@PathVariable(name = "id") id: Long, @RequestBody @Valid
            springUserDTO: SpringUserDTO): ResponseEntity<EntityModel<SimpleValue<Long>>> {
        springUserService.update(id, springUserDTO)
        return ResponseEntity.ok(springUserAssembler.toSimpleModel(id))
    }

    @DeleteMapping("/{id}")
    fun deleteSpringUser(@PathVariable(name = "id") id: Long): ResponseEntity<Void> {
        springUserService.delete(id)
        return ResponseEntity.noContent().build()
    }

}
