package com.oconeco.spring_search_tempo.base.rest

import com.oconeco.spring_search_tempo.base.model.SimpleValue
import com.oconeco.spring_search_tempo.base.model.SpringRoleDTO
import com.oconeco.spring_search_tempo.base.util.KotlinUtils
import org.springframework.hateoas.CollectionModel
import org.springframework.hateoas.EntityModel
import org.springframework.hateoas.IanaLinkRelations
import org.springframework.hateoas.server.SimpleRepresentationModelAssembler
import org.springframework.hateoas.server.mvc.WebMvcLinkBuilder
import org.springframework.stereotype.Component


@Component
class SpringRoleAssembler : SimpleRepresentationModelAssembler<SpringRoleDTO> {

    override fun addLinks(entityModel: EntityModel<SpringRoleDTO>) {
        entityModel.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(SpringRoleResource::class.java).getSpringRole(entityModel.content!!.id!!)).withSelfRel())
        entityModel.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(SpringRoleResource::class.java).getAllSpringRoles(KotlinUtils.nullType())).withRel(IanaLinkRelations.COLLECTION))
        if (entityModel.content!!.springUser != null) {
            entityModel.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(SpringUserResource::class.java).getSpringUser(entityModel.content!!.springUser!!)).withRel("springUser"))
        }
    }

    override fun addLinks(collectionModel: CollectionModel<EntityModel<SpringRoleDTO>>) {
        collectionModel.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(SpringRoleResource::class.java).getAllSpringRoles(KotlinUtils.nullType())).withSelfRel())
    }

    fun toSimpleModel(id: Long): EntityModel<SimpleValue<Long>> {
        val simpleModel = SimpleValue.entityModelOf(id)
        simpleModel.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(SpringRoleResource::class.java).getSpringRole(id)).withSelfRel())
        return simpleModel
    }

}
