package com.oconeco.spring_search_tempo

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.docs.Documenter


class ModularityTest {

    private val modules = ApplicationModules.of(SpringSearchTempoApplication::class.java)

    @Test
//    @Disabled("Temporarily disabled - batch module needs access to base config/service classes for multi-crawl implementation")
    fun verifyModuleStructure() {
        // TODO: Refactor module structure to satisfy Spring Modulith's strict boundaries
        // Options: 1) Move config classes to shared module, 2) Use events for cross-module communication
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
