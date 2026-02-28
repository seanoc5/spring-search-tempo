package com.oconeco.spring_search_tempo.base.config

import com.oconeco.spring_search_tempo.base.domain.EmailTag
import com.oconeco.spring_search_tempo.base.repos.EmailTagRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration


/**
 * Initializes system email tags on application startup.
 */
@Configuration
class EmailTagDataInitializer {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun emailTagInitRunner(emailTagRepository: EmailTagRepository) = ApplicationRunner {
        // Ensure 'junk' system tag exists
        if (!emailTagRepository.existsByName("junk")) {
            val junkTag = EmailTag().apply {
                name = "junk"
                color = "#dc3545"  // Bootstrap danger red
                isSystem = true
            }
            emailTagRepository.save(junkTag)
            log.info("Created system email tag: junk")
        }
    }

}
