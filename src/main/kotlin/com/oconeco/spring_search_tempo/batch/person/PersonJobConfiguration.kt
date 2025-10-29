package com.oconeco.spring_search_tempo.batch.person

import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.launch.support.RunIdIncrementer
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.item.ItemProcessor
import org.springframework.batch.item.ItemReader
import org.springframework.batch.item.ItemWriter
import org.springframework.batch.item.support.ListItemReader
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

/**
 * simple/silly batch job as a proof of concept/setup
 * note: this is not a real job, just a simple example
 * note: bean names start to matter with multiple jobs, keeping this config in the code as a crude sanity/integration check
 */
@Configuration
class PersonJobConfiguration(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val personRepository: PersonRepository
) {
    companion object {
        private val log = LoggerFactory.getLogger(PersonJobConfiguration::class.java)
    }

    @Bean
    fun personReader(): ItemReader<Person> = ListItemReader(
        listOf(
            Person("John", "Doe"),
            Person("Jane", "Smith"),
            Person("Peter", "Jones")
        )
    )

    @Bean
    fun personProcessor(): ItemProcessor<Person, Person> =
        ItemProcessor { person ->
            person.copy(
                firstName = person.firstName.uppercase(),
                lastName = person.lastName.uppercase()
            )
        }

    @Bean
    fun personWriter(): ItemWriter<Person> =
        ItemWriter { items ->
            val results = personRepository.saveAll(items)
            log.info("++++ Saved {} person(s) to database _> results:{}", items.size(), results)
        }

    @Bean
    fun processPersonStep(
        reader: ItemReader<Person>,
        processor: ItemProcessor<Person, Person>,
        writer: ItemWriter<Person>
    ): Step =
        StepBuilder("processPersonStep", jobRepository)
            .chunk<Person, Person>(10, transactionManager)
            .reader(reader)
            .processor(processor)
            .writer(writer)
            .allowStartIfComplete(true) // Allow step to re-run even if completed
            .build()

    @Bean
    fun importUserJob(processPersonStep: Step): Job =
        JobBuilder("importUserJob", jobRepository)
            .incrementer(RunIdIncrementer()) // Generate unique run.id for each execution
            .start(processPersonStep)
            .build()
}
