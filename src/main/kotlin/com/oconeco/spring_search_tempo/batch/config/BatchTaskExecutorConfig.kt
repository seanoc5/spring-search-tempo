package com.oconeco.spring_search_tempo.batch.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * Configuration for batch processing thread pools.
 *
 * Provides separate executors for:
 * - stepTaskExecutor: Multi-threaded chunk processing (parallel chunks within a step)
 * - asyncItemExecutor: AsyncItemProcessor/AsyncItemWriter (parallel item processing)
 */
@Configuration
class BatchTaskExecutorConfig {

    /**
     * TaskExecutor for step-level parallelism.
     * Used when stepThreads > 1 to process multiple chunks concurrently.
     */
    @Bean("stepTaskExecutor")
    fun stepTaskExecutor(): TaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 4
        maxPoolSize = 8
        queueCapacity = 50
        setThreadNamePrefix("step-exec-")
        initialize()
    }

    /**
     * TaskExecutor for AsyncItemProcessor.
     * Used when itemAsync = true to process items asynchronously.
     */
    @Bean("asyncItemExecutor")
    fun asyncItemExecutor(): TaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 4
        maxPoolSize = 8
        queueCapacity = 100
        setThreadNamePrefix("async-item-")
        initialize()
    }
}
