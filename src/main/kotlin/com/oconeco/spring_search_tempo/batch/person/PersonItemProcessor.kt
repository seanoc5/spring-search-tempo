package com.oconeco.spring_search_tempo.batch.person

import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemProcessor

class PersonItemProcessor : ItemProcessor<Person, Person> {

    private val log = LoggerFactory.getLogger(PersonItemProcessor::class.java)

    override fun process(person: Person): Person {
        val transformedPerson = person.copy(
            firstName = person.firstName.uppercase(),
            lastName = person.lastName.uppercase()
        )

        log.info("Converting ({}) into ({})", person, transformedPerson)
        return transformedPerson
    }
}
