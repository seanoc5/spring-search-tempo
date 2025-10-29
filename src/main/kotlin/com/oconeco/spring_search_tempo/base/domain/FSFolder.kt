package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Entity
import jakarta.persistence.OneToMany


@Entity
class FSFolder : FSObject() {

    @OneToMany(mappedBy = "fsFolder")
    var fsFiles = mutableSetOf<FSFile>()

}
