package com.oconeco.spring_search_tempo.base.config

import java.time.OffsetDateTime
import java.util.Optional
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.auditing.DateTimeProvider
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.transaction.annotation.EnableTransactionManagement


@Configuration
@EntityScan(
    "com.oconeco.spring_search_tempo.base.domain",
    "com.oconeco.spring_search_tempo.batch.person"
)
@EnableJpaRepositories(
    "com.oconeco.spring_search_tempo.base.repos",
    "com.oconeco.spring_search_tempo.batch.person"
)
@EnableTransactionManagement
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
class DomainConfig {

    @Bean(name = ["auditingDateTimeProvider"])
    fun dateTimeProvider(): DateTimeProvider =
            DateTimeProvider { Optional.of(OffsetDateTime.now()) }

}
