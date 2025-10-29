package com.oconeco.spring_search_tempo.batch.person

import jakarta.persistence.*

@Entity
@Table(name = "person")
data class Person(
    @Column(name = "first_name")
    val firstName: String = "",
    
    @Column(name = "last_name")
    val lastName: String = "",
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
)
