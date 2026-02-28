package com.oconeco.spring_search_tempo.batch.config

import org.springframework.context.annotation.Configuration

/**
 * Configuration for Spring Batch admin operations.
 *
 * Note: JobOperator and JobExplorer are auto-configured by Spring Boot's BatchAutoConfiguration.
 * No additional beans needed - we just inject them where needed.
 *
 * Note on restart functionality: JobOperator.restart() requires jobs to be registered
 * in the JobRegistry. For dynamically created jobs (like our crawl jobs), restart
 * won't work through JobOperator - they'd need to be relaunched through the controller.
 */
@Configuration
class BatchAdminConfiguration
