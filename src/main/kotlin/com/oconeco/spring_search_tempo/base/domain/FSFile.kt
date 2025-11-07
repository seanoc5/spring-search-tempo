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

    // Document metadata fields (extracted by Tika)
    @Column(columnDefinition = "text")
    var author: String? = null

    @Column(columnDefinition = "text")
    var title: String? = null

    @Column(columnDefinition = "text")
    var subject: String? = null

    @Column(columnDefinition = "text")
    var keywords: String? = null

    @Column(columnDefinition = "text")
    var comments: String? = null

    @Column(columnDefinition = "text")
    var creationDate: String? = null

    @Column(columnDefinition = "text")
    var modifiedDate: String? = null

    @Column(columnDefinition = "text")
    var language: String? = null

    @Column(columnDefinition = "text")
    var contentType: String? = null

    @Column
    var pageCount: Int? = null

    @OneToMany(mappedBy = "concept")
    var contentChunk = mutableSetOf<ContentChunks>()

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fs_folder_id")
    var fsFolder: FSFolder? = null

}
