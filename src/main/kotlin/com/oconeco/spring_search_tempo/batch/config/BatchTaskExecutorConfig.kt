package com.oconeco.spring_search_tempo.batch.config

import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.SimpleAsyncTaskExecutor
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * Configuration for batch processing thread pools and job launcher.
 *
 * Provides:
 * - asyncJobLauncherConfigurer: Customizes the auto-configured JobLauncher to run async
 * - stepTaskExecutor: Multi-threaded chunk processing (parallel chunks within a step)
 * - asyncItemExecutor: AsyncItemProcessor/AsyncItemWriter (parallel item processing)
 */
@Configuration
class BatchTaskExecutorConfig {

    companion object {
        /** Default throttle limit for multi-threaded steps (matches stepTaskExecutor core pool size) */
        const val DEFAULT_THROTTLE_LIMIT = 4
    }

    /**
     * Customizes the auto-configured JobLauncher to use async execution.
     * The auto-configured launcher is a TaskExecutorJobLauncher with a SyncTaskExecutor.
     * This swaps it for a SimpleAsyncTaskExecutor so that HTTP endpoints
     * (file crawl, email sync, NLP, embedding) return immediately
     * instead of blocking until the job completes.
     */
    @Bean
    fun asyncJobLauncherConfigurer(): BeanPostProcessor = object : BeanPostProcessor {
        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
            if (beanName == "jobLauncher" && bean is TaskExecutorJobLauncher) {
                bean.setTaskExecutor(SimpleAsyncTaskExecutor("batch-job-"))
            }
            return bean
        }
    }

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
