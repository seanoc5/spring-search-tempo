package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.domain.Annotation
import com.oconeco.spring_search_tempo.base.model.AnnotationDTO
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingConstants
import org.mapstruct.MappingTarget
import org.mapstruct.ReportingPolicy


@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE
)
interface AnnotationMapper {

    fun updateAnnotationDTO(`annotation`: Annotation, @MappingTarget annotationDTO: AnnotationDTO):
            AnnotationDTO

    @Mapping(
        target = "id",
        ignore = true
    )
    fun updateAnnotation(annotationDTO: AnnotationDTO, @MappingTarget `annotation`: Annotation):
            Annotation

}
