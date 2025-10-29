package com.oconeco.spring_search_tempo.base.rest

import com.oconeco.spring_search_tempo.base.FSFileService
import com.oconeco.spring_search_tempo.base.model.FSFileDTO
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
    value = ["/api/fSFiles"],
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class FSFileResource(
    private val fSFileService: FSFileService,
    private val fSFileAssembler: FSFileAssembler,
    private val pagedResourcesAssembler: PagedResourcesAssembler<FSFileDTO>
) {

    @GetMapping
    fun getAllFSFiles(@RequestParam(name = "filter", required = false) filter: String?,
            @SortDefault(sort = ["id"]) @PageableDefault(size = 20) pageable: Pageable):
            ResponseEntity<PagedModel<EntityModel<FSFileDTO>>> {
        val fSFileDTOs = fSFileService.findAll(filter, pageable)
        return ResponseEntity.ok(pagedResourcesAssembler.toModel(fSFileDTOs, fSFileAssembler))
    }

    @GetMapping("/{id}")
    fun getFSFile(@PathVariable(name = "id") id: Long): ResponseEntity<EntityModel<FSFileDTO>> {
        val fSFileDTO = fSFileService.get(id)
        return ResponseEntity.ok(fSFileAssembler.toModel(fSFileDTO))
    }

    @PostMapping
    fun createFSFile(@RequestBody @Valid fSFileDTO: FSFileDTO):
            ResponseEntity<EntityModel<SimpleValue<Long>>> {
        val createdId = fSFileService.create(fSFileDTO)
        return ResponseEntity(fSFileAssembler.toSimpleModel(createdId), HttpStatus.CREATED)
    }

    @PutMapping("/{id}")
    fun updateFSFile(@PathVariable(name = "id") id: Long, @RequestBody @Valid fSFileDTO: FSFileDTO):
            ResponseEntity<EntityModel<SimpleValue<Long>>> {
        fSFileService.update(id, fSFileDTO)
        return ResponseEntity.ok(fSFileAssembler.toSimpleModel(id))
    }

    @DeleteMapping("/{id}")
    fun deleteFSFile(@PathVariable(name = "id") id: Long): ResponseEntity<Void> {
        fSFileService.delete(id)
        return ResponseEntity.noContent().build()
    }

}
