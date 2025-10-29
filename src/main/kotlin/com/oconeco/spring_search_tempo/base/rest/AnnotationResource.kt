package com.oconeco.spring_search_tempo.base.rest

import com.oconeco.spring_search_tempo.base.AnnotationService
import com.oconeco.spring_search_tempo.base.model.AnnotationDTO
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
    value = ["/api/annotations"],
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class AnnotationResource(
    private val annotationService: AnnotationService,
    private val annotationAssembler: AnnotationAssembler,
    private val pagedResourcesAssembler: PagedResourcesAssembler<AnnotationDTO>
) {

    @GetMapping
    fun getAllAnnotations(@RequestParam(name = "filter", required = false) filter: String?,
            @SortDefault(sort = ["id"]) @PageableDefault(size = 20) pageable: Pageable):
            ResponseEntity<PagedModel<EntityModel<AnnotationDTO>>> {
        val annotationDTOs = annotationService.findAll(filter, pageable)
        return ResponseEntity.ok(pagedResourcesAssembler.toModel(annotationDTOs,
                annotationAssembler))
    }

    @GetMapping("/{id}")
    fun getAnnotation(@PathVariable(name = "id") id: Long):
            ResponseEntity<EntityModel<AnnotationDTO>> {
        val annotationDTO = annotationService.get(id)
        return ResponseEntity.ok(annotationAssembler.toModel(annotationDTO))
    }

    @PostMapping
    fun createAnnotation(@RequestBody @Valid annotationDTO: AnnotationDTO):
            ResponseEntity<EntityModel<SimpleValue<Long>>> {
        val createdId = annotationService.create(annotationDTO)
        return ResponseEntity(annotationAssembler.toSimpleModel(createdId), HttpStatus.CREATED)
    }

    @PutMapping("/{id}")
    fun updateAnnotation(@PathVariable(name = "id") id: Long, @RequestBody @Valid
            annotationDTO: AnnotationDTO): ResponseEntity<EntityModel<SimpleValue<Long>>> {
        annotationService.update(id, annotationDTO)
        return ResponseEntity.ok(annotationAssembler.toSimpleModel(id))
    }

    @DeleteMapping("/{id}")
    fun deleteAnnotation(@PathVariable(name = "id") id: Long): ResponseEntity<Void> {
        annotationService.delete(id)
        return ResponseEntity.noContent().build()
    }

}
