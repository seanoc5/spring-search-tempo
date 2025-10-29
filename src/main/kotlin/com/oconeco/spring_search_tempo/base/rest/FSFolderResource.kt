package com.oconeco.spring_search_tempo.base.rest

import com.oconeco.spring_search_tempo.base.FSFolderService
import com.oconeco.spring_search_tempo.base.model.FSFolderDTO
import com.oconeco.spring_search_tempo.base.model.SimpleValue
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
    value = ["/api/fSFolders"],
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class FSFolderResource(
    private val fSFolderService: FSFolderService,
    private val fSFolderAssembler: FSFolderAssembler,
    private val pagedResourcesAssembler: PagedResourcesAssembler<FSFolderDTO>
) {

    @GetMapping
    fun getAllFSFolders(@RequestParam(name = "filter", required = false) filter: String?,
            @SortDefault(sort = ["id"]) @PageableDefault(size = 20) pageable: Pageable):
            ResponseEntity<PagedModel<EntityModel<FSFolderDTO>>> {
        val fSFolderDTOs = fSFolderService.findAll(filter, pageable)
        return ResponseEntity.ok(pagedResourcesAssembler.toModel(fSFolderDTOs, fSFolderAssembler))
    }

    @GetMapping("/{id}")
    fun getFSFolder(@PathVariable(name = "id") id: Long): ResponseEntity<EntityModel<FSFolderDTO>> {
        val fSFolderDTO = fSFolderService.get(id)
        return ResponseEntity.ok(fSFolderAssembler.toModel(fSFolderDTO))
    }

    @PostMapping
    fun createFSFolder(@RequestBody @Valid fSFolderDTO: FSFolderDTO):
            ResponseEntity<EntityModel<SimpleValue<Long>>> {
        val createdId = fSFolderService.create(fSFolderDTO)
        return ResponseEntity(fSFolderAssembler.toSimpleModel(createdId), HttpStatus.CREATED)
    }

    @PutMapping("/{id}")
    fun updateFSFolder(@PathVariable(name = "id") id: Long, @RequestBody @Valid
            fSFolderDTO: FSFolderDTO): ResponseEntity<EntityModel<SimpleValue<Long>>> {
        fSFolderService.update(id, fSFolderDTO)
        return ResponseEntity.ok(fSFolderAssembler.toSimpleModel(id))
    }

    @DeleteMapping("/{id}")
    fun deleteFSFolder(@PathVariable(name = "id") id: Long): ResponseEntity<Void> {
        fSFolderService.delete(id)
        return ResponseEntity.noContent().build()
    }

}
