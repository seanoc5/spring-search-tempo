package com.oconeco.spring_search_tempo

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.docs.Documenter


class ModularityTest {

    private val modules = ApplicationModules.of(SpringSearchTempoApplication::class.java)

    @Test
    fun verifyModuleStructure() {
        modules.verify()
    }

    /**
     * Generates documentation in build/spring-modulith-docs.
     */
    @Test
    fun createModuleDocumentation() {
        Documenter(modules).writeDocumentation()
    }

}
