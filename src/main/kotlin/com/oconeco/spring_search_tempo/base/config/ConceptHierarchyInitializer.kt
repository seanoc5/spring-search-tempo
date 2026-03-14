package com.oconeco.spring_search_tempo.base.config

import com.oconeco.spring_search_tempo.base.domain.ConceptHierarchy
import com.oconeco.spring_search_tempo.base.domain.ConceptSourceSystem
import com.oconeco.spring_search_tempo.base.repos.ConceptHierarchyRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
@Order(40)
class ConceptHierarchyInitializer(
    private val conceptHierarchyRepository: ConceptHierarchyRepository
) : ApplicationRunner {

    companion object {
        private val log = LoggerFactory.getLogger(ConceptHierarchyInitializer::class.java)
    }

    @Transactional
    override fun run(args: ApplicationArguments) {
        coreHierarchies().forEach { template ->
            if (conceptHierarchyRepository.existsByCode(requireNotNull(template.code))) {
                return@forEach
            }

            conceptHierarchyRepository.save(template)
            log.info("Created core concept hierarchy {}", template.code)
        }
    }

    private fun coreHierarchies(): List<ConceptHierarchy> = listOf(
        ConceptHierarchy().apply {
            code = "OCONECO"
            uri = "concept-hierarchy:oconeco"
            label = "OconEco"
            description = "Primary OconEco hierarchy with address-bearing concept nodes."
            sourceSystem = ConceptSourceSystem.OCONECO
            core = true
            supportsAddress = true
            expectedNodeCount = 4_500
        },
        ConceptHierarchy().apply {
            code = "OPENALEX_CONCEPTS"
            uri = "concept-hierarchy:openalex-concepts"
            label = "OpenAlex Concepts"
            description = "OpenAlex concept taxonomy imported without postal-style addresses."
            sourceSystem = ConceptSourceSystem.OPENALEX
            core = true
            supportsAddress = false
            expectedNodeCount = 60_000
        },
        ConceptHierarchy().apply {
            code = "OPENALEX_TOPICS"
            uri = "concept-hierarchy:openalex-topics"
            label = "OpenAlex Topics"
            description = "OpenAlex topic hierarchy kept distinct from OpenAlex Concepts."
            sourceSystem = ConceptSourceSystem.OPENALEX
            core = true
            supportsAddress = false
        }
    )
}
