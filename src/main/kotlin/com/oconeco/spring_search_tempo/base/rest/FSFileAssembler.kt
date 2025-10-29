package com.oconeco.spring_search_tempo.base.rest

import com.oconeco.spring_search_tempo.base.model.FSFileDTO
import com.oconeco.spring_search_tempo.base.model.SimpleValue
import com.oconeco.spring_search_tempo.base.util.KotlinUtils
import org.springframework.hateoas.CollectionModel
import org.springframework.hateoas.EntityModel
import org.springframework.hateoas.IanaLinkRelations
import org.springframework.hateoas.server.SimpleRepresentationModelAssembler
import org.springframework.hateoas.server.mvc.WebMvcLinkBuilder
import org.springframework.stereotype.Component


@Component
class FSFileAssembler : SimpleRepresentationModelAssembler<FSFileDTO> {

    override fun addLinks(entityModel: EntityModel<FSFileDTO>) {
        entityModel.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(FSFileResource::class.java).getFSFile(entityModel.content!!.id!!)).withSelfRel())
        entityModel.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(FSFileResource::class.java).getAllFSFiles(null,
                KotlinUtils.nullType())).withRel(IanaLinkRelations.COLLECTION))
        if (entityModel.content!!.fsFolder != null) {
            entityModel.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(FSFolderResource::class.java).getFSFolder(entityModel.content!!.fsFolder!!)).withRel("fsFolder"))
        }
    }

    override fun addLinks(collectionModel: CollectionModel<EntityModel<FSFileDTO>>) {
        collectionModel.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(FSFileResource::class.java).getAllFSFiles(null,
                KotlinUtils.nullType())).withSelfRel())
    }

    fun toSimpleModel(id: Long): EntityModel<SimpleValue<Long>> {
        val simpleModel = SimpleValue.entityModelOf(id)
        simpleModel.add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(FSFileResource::class.java).getFSFile(id)).withSelfRel())
        return simpleModel
    }

}
