package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.AnnotationService
import com.oconeco.spring_search_tempo.base.domain.Annotation
import com.oconeco.spring_search_tempo.base.model.AnnotationDTO
import com.oconeco.spring_search_tempo.base.repos.AnnotationRepository
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service


@Service
class AnnotationServiceImpl(
    private val annotationRepository: AnnotationRepository,
    private val annotationMapper: AnnotationMapper
) : AnnotationService {

    override fun findAll(filter: String?, pageable: Pageable): Page<AnnotationDTO> {
        var page: Page<Annotation>
        if (filter != null) {
            page = annotationRepository.findAllById(filter.toLongOrNull(), pageable)
        } else {
            page = annotationRepository.findAll(pageable)
        }
        return PageImpl(page.content
                .map { annotation -> annotationMapper.updateAnnotationDTO(annotation,
                AnnotationDTO()) },
                pageable, page.totalElements)
    }

    override fun `get`(id: Long): AnnotationDTO = annotationRepository.findById(id)
            .map { annotation -> annotationMapper.updateAnnotationDTO(annotation, AnnotationDTO()) }
            .orElseThrow { NotFoundException() }

    override fun create(annotationDTO: AnnotationDTO): Long {
        val annotation = Annotation()
        annotationMapper.updateAnnotation(annotationDTO, annotation)
        return annotationRepository.save(annotation).id!!
    }

    override fun update(id: Long, annotationDTO: AnnotationDTO) {
        val annotation = annotationRepository.findById(id)
                .orElseThrow { NotFoundException() }
        annotationMapper.updateAnnotation(annotationDTO, annotation)
        annotationRepository.save(annotation)
    }

    override fun delete(id: Long) {
        val annotation = annotationRepository.findById(id)
                .orElseThrow { NotFoundException() }
        annotationRepository.delete(annotation)
    }

    override fun labelExists(label: String?): Boolean = annotationRepository.existsByLabel(label)

}
