package com.oconeco.spring_search_tempo.base.rest

import com.oconeco.spring_search_tempo.base.model.SimpleValue
import com.oconeco.spring_search_tempo.base.model.SpringUserDTO
import com.oconeco.spring_search_tempo.base.util.KotlinUtils
import org.springframework.hateoas.CollectionModel
import org.springframework.hateoas.EntityModel
import org.springframework.hateoas.IanaLinkRelations
import org.springframework.hateoas.server.SimpleRepresentationModelAssembler
import org.springframework.hateoas.server.mvc.WebMvcLinkBuilder
import org.springframework.stereotype.Component


@Component
class SpringUserAssembler : SimpleRepresentationModelAssembler<SpringUserDTO> {

    override fun addLinks(entityModel: EntityModel<SpringUserDTO>) {
        entityModel.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(SpringUserResource::class.java).getSpringUser(entityModel.content!!.id!!)).withSelfRel())
        entityModel.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(SpringUserResource::class.java).getAllSpringUsers(null,
                KotlinUtils.nullType())).withRel(IanaLinkRelations.COLLECTION))
    }

    override fun addLinks(collectionModel: CollectionModel<EntityModel<SpringUserDTO>>) {
        collectionModel.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(SpringUserResource::class.java).getAllSpringUsers(null,
                KotlinUtils.nullType())).withSelfRel())
    }

    fun toSimpleModel(id: Long): EntityModel<SimpleValue<Long>> {
        val simpleModel = SimpleValue.entityModelOf(id)
        simpleModel.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(SpringUserResource::class.java).getSpringUser(id)).withSelfRel())
        return simpleModel
    }

}
