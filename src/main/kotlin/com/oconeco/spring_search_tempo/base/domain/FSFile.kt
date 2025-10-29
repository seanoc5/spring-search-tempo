package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany


@Entity
class FSFile : FSObject() {

    @Column(columnDefinition = "text")
    var bodyText: String? = null

    @Column
    var bodySize: Long? = null

    @OneToMany(mappedBy = "concept")
    var contentChunk = mutableSetOf<ContentChunks>()

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fs_folder_id")
    var fsFolder: FSFolder? = null

}
