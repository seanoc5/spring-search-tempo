---
description: Create a new Spring Batch job
---

Create Spring Batch job: **{{name}}**

**Job configuration:**
- Name: {{name}}
- Purpose: {{purpose}}
- Input: {{input}} (e.g., "CSV file", "database query", "file system scan")
- Processing: {{processing}} (e.g., "transform data", "extract text", "index content")
- Output: {{output}} (e.g., "save to database", "write file", "update index")
- Chunk size: {{chunk_size}} (default: 100)
- Schedule: {{schedule}} (e.g., "on startup", "cron: 0 2 * * *", "manual only")

**Create components:**
1. **Job Configuration** (`batch/{{Name}}JobConfig.kt`):
   ```kotlin
   @Configuration
   class {{Name}}JobConfig(
       private val jobRepository: JobRepository,
       private val transactionManager: PlatformTransactionManager
   ) {
       @Bean
       fun {{name}}Job(): Job { ... }

       @Bean
       fun {{name}}Step(): Step { ... }
   }
   ```

2. **ItemReader**: Read from {{input}}
   - FlatFileItemReader for CSV
   - JdbcCursorItemReader for database
   - Custom reader for file system

3. **ItemProcessor**: Transform/process data
   - Convert input DTO to entity
   - Apply business logic
   - Filter invalid items (return null)

4. **ItemWriter**: Write to {{output}}
   - RepositoryItemWriter for database
   - FlatFileItemWriter for files
   - Custom writer for complex operations

5. **Tests**:
   - @SpringBatchTest configuration
   - Test reader, processor, writer separately
   - Integration test for full job

**Configuration in application.yml:**
```yaml
spring:
  batch:
    job:
      name: {{name}}  # Add to run on startup
      enabled: {{enabled}}  # true/false
```

**Follow patterns from:** `ImportUserJobConfig.kt`

**After creation:**
- Run tests to verify job works
- Optionally run actual job: `./gradlew bootRun`
- Show job execution summary
- Offer to commit
