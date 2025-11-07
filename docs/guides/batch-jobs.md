# Creating Spring Batch Jobs

This guide covers creating Spring Batch jobs in the Spring Search Tempo application.

## Overview

Spring Batch provides a framework for processing large volumes of data through configurable, reusable components: readers, processors, and writers.

## Batch Job Architecture

```
Job
 └── Step(s)
      ├── ItemReader (reads data)
      ├── ItemProcessor (transforms data)
      └── ItemWriter (writes results)
```

## Complete Example: Email Import Job

### 1. Create Input Model

```kotlin
data class EmailInput(
    val subject: String,
    val sender: String,
    val recipients: String,
    val body: String,
    val receivedDate: String
)
```

### 2. Create Job Configuration

```kotlin
@Configuration
class EmailImportJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val emailRepository: FSEmailRepository,
    private val emailMapper: FSEmailMapper
) {

    companion object {
        private val log = LoggerFactory.getLogger(EmailImportJobConfig::class.java)
    }

    @Bean
    fun importEmailJob(): Job {
        return JobBuilder("importEmailJob", jobRepository)
            .start(importEmailStep())
            .listener(jobExecutionListener())
            .build()
    }

    @Bean
    fun importEmailStep(): Step {
        return StepBuilder("importEmailStep", jobRepository)
            .chunk<EmailInput, FSEmail>(100, transactionManager)
            .reader(emailReader())
            .processor(emailProcessor())
            .writer(emailWriter())
            .faultTolerant()
            .skipLimit(10)
            .skip(ParseException::class.java)
            .build()
    }

    @Bean
    fun emailReader(): ItemReader<EmailInput> {
        return FlatFileItemReaderBuilder<EmailInput>()
            .name("emailReader")
            .resource(ClassPathResource("data/emails.csv"))
            .delimited()
            .names("subject", "sender", "recipients", "body", "receivedDate")
            .targetType(EmailInput::class.java)
            .linesToSkip(1) // Skip header
            .build()
    }

    @Bean
    fun emailProcessor(): ItemProcessor<EmailInput, FSEmail> {
        return ItemProcessor { input ->
            try {
                FSEmail().apply {
                    subject = input.subject
                    sender = input.sender
                    recipients = input.recipients
                    body = input.body
                    uri = "email://${input.sender}/${input.subject}"
                    receivedDate = Instant.parse(input.receivedDate)
                }
            } catch (e: Exception) {
                log.error("Failed to process email: ${input.subject}", e)
                null // Skip this item
            }
        }
    }

    @Bean
    fun emailWriter(): ItemWriter<FSEmail> {
        return ItemWriter { items ->
            emailRepository.saveAll(items)
            log.info("Saved {} emails", items.size)
        }
    }

    @Bean
    fun jobExecutionListener(): JobExecutionListener {
        return object : JobExecutionListener {
            override fun beforeJob(jobExecution: JobExecution) {
                log.info("Starting job: ${jobExecution.jobInstance.jobName}")
            }

            override fun afterJob(jobExecution: JobExecution) {
                log.info(
                    "Job finished: {} with status: {}",
                    jobExecution.jobInstance.jobName,
                    jobExecution.status
                )
            }
        }
    }
}
```

## Common Reader Types

### CSV File Reader

```kotlin
@Bean
fun csvReader(): FlatFileItemReader<InputDTO> {
    return FlatFileItemReaderBuilder<InputDTO>()
        .name("csvReader")
        .resource(ClassPathResource("data/input.csv"))
        .delimited()
        .delimiter(",")
        .names("field1", "field2", "field3")
        .targetType(InputDTO::class.java)
        .linesToSkip(1)
        .build()
}
```

### JSON File Reader

```kotlin
@Bean
fun jsonReader(): JsonItemReader<InputDTO> {
    return JsonItemReaderBuilder<InputDTO>()
        .name("jsonReader")
        .resource(ClassPathResource("data/input.json"))
        .jsonObjectReader(JacksonJsonObjectReader(InputDTO::class.java))
        .build()
}
```

### Database Reader (JPA)

```kotlin
@Bean
fun databaseReader(entityManagerFactory: EntityManagerFactory): JpaPagingItemReader<FSFile> {
    return JpaPagingItemReaderBuilder<FSFile>()
        .name("fileReader")
        .entityManagerFactory(entityManagerFactory)
        .queryString("SELECT f FROM FSFile f WHERE f.bodyText IS NOT NULL")
        .pageSize(100)
        .build()
}
```

### Custom Item Reader

```kotlin
class CustomItemReader(
    private val dataSource: DataSource
) : ItemReader<CustomData> {

    private var initialized = false
    private val items = mutableListOf<CustomData>()
    private var currentIndex = 0

    override fun read(): CustomData? {
        if (!initialized) {
            initialize()
        }

        if (currentIndex >= items.size) {
            return null // Signal end of data
        }

        return items[currentIndex++]
    }

    private fun initialize() {
        // Load data from source
        items.addAll(loadDataFromSource())
        initialized = true
    }

    private fun loadDataFromSource(): List<CustomData> {
        // Implementation
        return emptyList()
    }
}
```

## Common Processor Patterns

### Simple Transformation

```kotlin
@Bean
fun transformProcessor(): ItemProcessor<InputDTO, OutputDTO> {
    return ItemProcessor { input ->
        OutputDTO(
            id = input.id,
            name = input.name.uppercase(),
            processed = Instant.now()
        )
    }
}
```

### Filtering Items

```kotlin
@Bean
fun filterProcessor(): ItemProcessor<InputDTO, OutputDTO> {
    return ItemProcessor { input ->
        // Return null to filter out item
        if (input.isValid()) {
            convertToOutput(input)
        } else {
            null
        }
    }
}
```

### Composite Processor

```kotlin
@Bean
fun compositeProcessor(): CompositeItemProcessor<InputDTO, OutputDTO> {
    val processor = CompositeItemProcessor<InputDTO, OutputDTO>()
    processor.setDelegates(listOf(
        validationProcessor(),
        transformationProcessor(),
        enrichmentProcessor()
    ))
    return processor
}
```

## Common Writer Types

### Database Writer (JPA)

```kotlin
@Bean
fun jpaWriter(entityManagerFactory: EntityManagerFactory): JpaItemWriter<FSFile> {
    val writer = JpaItemWriter<FSFile>()
    writer.setEntityManagerFactory(entityManagerFactory)
    return writer
}
```

### Repository Writer

```kotlin
@Bean
fun repositoryWriter(): RepositoryItemWriter<FSFile> {
    val writer = RepositoryItemWriter<FSFile>()
    writer.setRepository(fileRepository)
    writer.setMethodName("save")
    return writer
}
```

### Custom Batch Writer

```kotlin
@Bean
fun customWriter(): ItemWriter<OutputDTO> {
    return ItemWriter { items ->
        items.chunked(50).forEach { batch ->
            processBatch(batch)
        }
    }
}
```

## Job Configuration

### Auto-Run on Startup

```yaml
# application.yml
spring:
  batch:
    job:
      enabled: true
      name: importEmailJob  # Specific job to run
```

### Disable Auto-Run

```yaml
spring:
  batch:
    job:
      enabled: false
```

### Job Parameters

```kotlin
@Bean
fun jobLauncher(jobRepository: JobRepository): JobLauncher {
    val launcher = TaskExecutorJobLauncher()
    launcher.setJobRepository(jobRepository)
    return launcher
}

fun runJobManually() {
    val params = JobParametersBuilder()
        .addString("inputFile", "emails.csv")
        .addLong("timestamp", System.currentTimeMillis())
        .toJobParameters()

    jobLauncher.run(importEmailJob, params)
}
```

## Error Handling

### Skip Failed Items

```kotlin
@Bean
fun stepWithSkip(): Step {
    return StepBuilder("stepWithSkip", jobRepository)
        .chunk<Input, Output>(100, transactionManager)
        .reader(reader())
        .processor(processor())
        .writer(writer())
        .faultTolerant()
        .skipLimit(10)
        .skip(ValidationException::class.java)
        .skip(ParseException::class.java)
        .skipPolicy(customSkipPolicy())
        .build()
}
```

### Retry Failed Items

```kotlin
@Bean
fun stepWithRetry(): Step {
    return StepBuilder("stepWithRetry", jobRepository)
        .chunk<Input, Output>(100, transactionManager)
        .reader(reader())
        .processor(processor())
        .writer(writer())
        .faultTolerant()
        .retryLimit(3)
        .retry(TransientException::class.java)
        .build()
}
```

### Custom Skip Policy

```kotlin
class CustomSkipPolicy : SkipPolicy {
    override fun shouldSkip(t: Throwable, skipCount: Long): Boolean {
        return when {
            t is FileNotFoundException -> false // Don't skip
            t is ValidationException && skipCount < 10 -> true
            else -> false
        }
    }
}
```

## Step Listeners

### Item Processing Listener

```kotlin
class EmailProcessingListener : ItemProcessListener<EmailInput, FSEmail> {

    override fun beforeProcess(item: EmailInput) {
        log.debug("Processing email: ${item.subject}")
    }

    override fun afterProcess(item: EmailInput, result: FSEmail?) {
        if (result != null) {
            log.debug("Successfully processed: ${result.subject}")
        }
    }

    override fun onProcessError(item: EmailInput, e: Exception) {
        log.error("Failed to process email: ${item.subject}", e)
    }
}
```

### Chunk Listener

```kotlin
class ChunkListener : ChunkListener {
    override fun beforeChunk(context: ChunkContext) {
        log.debug("Starting chunk...")
    }

    override fun afterChunk(context: ChunkContext) {
        log.debug("Chunk completed")
    }

    override fun afterChunkError(context: ChunkContext) {
        log.error("Chunk failed")
    }
}
```

## Testing Batch Jobs

### Basic Job Test

```kotlin
@SpringBatchTest
@SpringBootTest
@Testcontainers
class EmailImportJobTest {

    @Autowired
    lateinit var jobLauncherTestUtils: JobLauncherTestUtils

    @Autowired
    lateinit var jobRepositoryTestUtils: JobRepositoryTestUtils

    @Autowired
    lateinit var emailRepository: FSEmailRepository

    @BeforeEach
    fun setUp() {
        jobRepositoryTestUtils.removeJobExecutions()
        emailRepository.deleteAll()
    }

    @Test
    fun `should import emails successfully`() {
        // Given: test data exists
        val params = JobParametersBuilder()
            .addLong("timestamp", System.currentTimeMillis())
            .toJobParameters()

        // When: job runs
        val execution = jobLauncherTestUtils.launchJob(params)

        // Then: job completes successfully
        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)

        // And: emails are imported
        val emails = emailRepository.findAll()
        assertThat(emails).isNotEmpty
    }

    @Test
    fun `should skip invalid emails`() {
        val params = JobParametersBuilder()
            .addString("inputFile", "emails-with-errors.csv")
            .addLong("timestamp", System.currentTimeMillis())
            .toJobParameters()

        val execution = jobLauncherTestUtils.launchJob(params)

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(execution.stepExecutions.first().skipCount).isGreaterThan(0)
    }
}
```

### Test Individual Step

```kotlin
@Test
fun `should process step correctly`() {
    val execution = jobLauncherTestUtils.launchStep("importEmailStep")

    assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
    assertThat(execution.writeCount).isEqualTo(100)
}
```

## Best Practices

1. **Chunk Size**: Start with 100-500, tune based on performance
2. **Transactions**: Each chunk is a transaction; keep chunks reasonably sized
3. **Error Handling**: Always configure skip/retry policies for production jobs
4. **Job Parameters**: Use timestamps to allow job re-runs
5. **Logging**: Add listeners for visibility into job progress
6. **Idempotency**: Design jobs to be safely re-runnable
7. **Testing**: Test with real-world data volumes
8. **Monitoring**: Track job metrics via Spring Boot Actuator

## Troubleshooting

### Job Won't Re-run

**Problem**: "Job already complete"

**Solution**: Add timestamp to job parameters:
```kotlin
JobParametersBuilder()
    .addLong("timestamp", System.currentTimeMillis())
    .toJobParameters()
```

### Out of Memory Errors

**Problem**: Processing too much data at once

**Solution**: Reduce chunk size or use streaming readers

### Slow Performance

**Problem**: Job takes too long

**Solutions**:
- Increase chunk size
- Use pagination in readers
- Add indexes to database queries
- Consider parallel processing

### Transaction Timeouts

**Problem**: Chunks too large for transaction timeout

**Solution**: Reduce chunk size or increase timeout

## See Also

- [File Crawling Job](crawling.md)
- [Text Extraction](text-extraction.md)
- [Testing Guide](testing.md)
- [Spring Batch Documentation](https://docs.spring.io/spring-batch/reference/)
