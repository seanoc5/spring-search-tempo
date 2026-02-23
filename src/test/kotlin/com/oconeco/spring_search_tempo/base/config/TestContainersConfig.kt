package com.oconeco.spring_search_tempo.base.config

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Shared test container configuration for PostgreSQL with pgvector extension.
 * This provides a reusable @ServiceConnection container for all test profiles.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestContainersConfig {

    companion object {
        /**
         * Shared PostgreSQL container with pgvector extension.
         * Using companion object ensures single container instance across tests.
         */
        @JvmStatic
        @ServiceConnection
        @Bean
        fun postgresContainer(): PostgreSQLContainer<*> {
            return PostgreSQLContainer("pgvector/pgvector:pg17").apply {
                withReuse(true)
                start()
            }
        }
    }
}
