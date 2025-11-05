package com.oconeco.spring_search_tempo.base.rest

import com.oconeco.spring_search_tempo.base.util.KotlinUtils
import org.springframework.hateoas.RepresentationModel
import org.springframework.hateoas.server.mvc.WebMvcLinkBuilder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController


@RestController
class HomeResource {

    @GetMapping("/home")
    fun index(): RepresentationModel<*> = RepresentationModel.of(null)
            .add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(FSFileResource::class.java).getAllFSFiles(null,
            KotlinUtils.nullType())).withRel("fSFiles"))
            .add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(FSFolderResource::class.java).getAllFSFolders(null,
            KotlinUtils.nullType())).withRel("fSFolders"))
            .add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(AnnotationResource::class.java).getAllAnnotations(null,
            KotlinUtils.nullType())).withRel("annotations"))
            .add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(SpringUserResource::class.java).getAllSpringUsers(null,
            KotlinUtils.nullType())).withRel("springUsers"))
            .add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(SpringRoleResource::class.java).getAllSpringRoles(KotlinUtils.nullType())).withRel("springRoles"))

}
