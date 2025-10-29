package com.oconeco.spring_search_tempo.base.rest

import com.oconeco.spring_search_tempo.base.model.FSFolderDTO
import com.oconeco.spring_search_tempo.base.model.SimpleValue
import com.oconeco.spring_search_tempo.base.util.KotlinUtils
import org.springframework.hateoas.CollectionModel
import org.springframework.hateoas.EntityModel
import org.springframework.hateoas.IanaLinkRelations
import org.springframework.hateoas.server.SimpleRepresentationModelAssembler
import org.springframework.hateoas.server.mvc.WebMvcLinkBuilder
import org.springframework.stereotype.Component


@Component
class FSFolderAssembler : SimpleRepresentationModelAssembler<FSFolderDTO> {

    override fun addLinks(entityModel: EntityModel<FSFolderDTO>) {
        entityModel.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(FSFolderResource::class.java).getFSFolder(entityModel.content!!.id!!)).withSelfRel())
        entityModel.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(FSFolderResource::class.java).getAllFSFolders(null,
                KotlinUtils.nullType())).withRel(IanaLinkRelations.COLLECTION))
    }

    override fun addLinks(collectionModel: CollectionModel<EntityModel<FSFolderDTO>>) {
        collectionModel.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(FSFolderResource::class.java).getAllFSFolders(null,
                KotlinUtils.nullType())).withSelfRel())
    }

    fun toSimpleModel(id: Long): EntityModel<SimpleValue<Long>> {
        val simpleModel = SimpleValue.entityModelOf(id)
        simpleModel.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(FSFolderResource::class.java).getFSFolder(id)).withSelfRel())
        return simpleModel
    }

}
