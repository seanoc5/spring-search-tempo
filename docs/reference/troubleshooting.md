# Troubleshooting Guide

Common issues and solutions for Spring Search Tempo.

## Application Startup Issues

### Port Already in Use

**Symptom**: `Address already in use: bind`

**Solution**:
```bash
# Find process using port 8089
lsof -i :8089

# Kill the process
kill -9 <PID>

# Or use a different port
./gradlew bootRun --args='--server.port=9090'
```

### Cannot Connect to PostgreSQL

**Symptom**: `Connection refused` or `could not connect to server`

**Diagnosis**:
```bash
# Check if PostgreSQL container is running
docker ps | grep postgres

# View PostgreSQL logs
docker compose logs postgres
```

**Solutions**:
```bash
# Start PostgreSQL if not running
docker compose up -d

# Restart PostgreSQL
docker compose restart postgres

# Check connection manually
psql -h localhost -p 5432 -U tempo -d tempo

# Verify port mapping
docker compose ps
```

### Wrong PostgreSQL Port

## Spring Batch Issues

### Job Already Complete

**Symptom**: `Job instance already complete`

**Cause**: Batch job with same parameters already ran successfully

**Solutions**:

**Option 1**: Clear batch metadata
```bash
psql -h localhost -p 5433 -U postgres -d spring_search_tempo <<EOF
DELETE FROM batch_step_execution_context;
DELETE FROM batch_step_execution;
DELETE FROM batch_job_execution_context;
DELETE FROM batch_job_execution_params;
DELETE FROM batch_job_execution;
DELETE FROM batch_job_instance;
EOF
```

**Option 2**: Add unique parameter (timestamp)
```kotlin
val params = JobParametersBuilder()
    .addString("crawlName", "user-content")
    .addLong("timestamp", System.currentTimeMillis())  // Makes it unique
    .toJobParameters()
```

**Option 3**: Disable job on startup
```yaml
spring:
  batch:
    job:
      enabled: false
```

### Batch Tables Not Created

**Symptom**: `Table "BATCH_JOB_INSTANCE" not found`

**Solution**: Enable schema initialization
```yaml
spring:
  batch:
    jdbc:
      initialize-schema: always  # or 'embedded', 'never'
```

## Database Issues

### Hibernate Schema Validation Errors

**Symptom**: `Schema validation failed`, `Table/column mismatch`

**Temporary Solution** (development):
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: update  # Use 'validate' in production
```

**Permanent Solution**:
1. Use Flyway or Liquibase for migrations
2. Or drop and recreate database:
```bash
docker compose down -v  # Removes volumes
docker compose up -d
```

### LazyInitializationException

**Symptom**: `could not initialize proxy - no Session`

**Cause**: Accessing lazy-loaded relationship outside transaction

**Solutions**:

**Option 1**: Add `@Transactional` to service method
```kotlin
@Transactional(readOnly = true)
fun getFileWithChunks(id: Long): FSFileDTO {
    val file = repository.findById(id).orElseThrow()
    file.contentChunks.size  // Force lazy load
    return mapper.toDTO(file)
}
```

**Option 2**: Use JOIN FETCH
```kotlin
@Query("SELECT f FROM FSFile f JOIN FETCH f.contentChunks WHERE f.id = :id")
fun findByIdWithChunks(id: Long): FSFile?
```

**Option 3**: Use DTOs (recommended)
```kotlin
// DTO breaks entity lifecycle, no lazy loading issues
fun getFile(id: Long): FSFileDTO {
    val entity = repository.findById(id).orElseThrow()
    return mapper.toDTO(entity)  // DTO is detached
}
```

## Testing Issues

### Tests Fail with "Port Already in Use"

**Symptom**: Testcontainers can't bind to port

**Solution**: Stop lingering containers
```bash
# Stop all PostgreSQL Testcontainers
docker stop $(docker ps -q --filter ancestor=postgres:18.0)

# Remove stopped containers
docker container prune -f
```

### Testcontainers Schema Changes Not Applied

**Symptom**: Tests pass locally but fail after schema changes

**Cause**: Testcontainers reuse enabled, old schema cached

**Solution**: Manually stop containers
```bash
docker stop $(docker ps -q --filter ancestor=postgres:18.0)
```

**Or** disable reuse temporarily (testcontainers.properties):
```properties
testcontainers.reuse.enable=false
```

### Integration Tests Timeout

**Symptom**: Tests hang or timeout waiting for containers

**Solution**: Increase startup timeout
```kotlin
@Container
val postgres = PostgreSQLContainer<Nothing>("postgres:18.0").apply {
    withStartupTimeout(Duration.ofMinutes(2))
}
```

**Or**: Check Docker has enough resources (CPU/RAM)

### Tests Pass Individually But Fail Together

**Symptom**: `./gradlew test` fails, but individual tests pass

**Cause**: Shared state or test isolation issues

**Solutions**:
```kotlin
@BeforeEach
fun setUp() {
    repository.deleteAll()  // Clean state before each test
}

// Or use @DirtiesContext for integration tests
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
```

## Build Issues

### Kapt Annotation Processing Fails

**Symptom**: `error: cannot find symbol` or MapStruct mapper not generated

**Solution**:
```bash
# Clean kapt generated files
./gradlew cleanKapt
rm -rf build/generated/source/kapt
./gradlew build
```

**Verify dependencies**:
```kotlin
dependencies {
    implementation("org.mapstruct:mapstruct:1.6.3")
    kapt("org.mapstruct:mapstruct-processor:1.6.3")
}
```

### Out of Memory During Build

**Symptom**: `java.lang.OutOfMemoryError: Java heap space`

**Solution**: Increase Gradle memory
```bash
# Set environment variable
export GRADLE_OPTS="-Xmx2048m -XX:MaxMetaspaceSize=512m"

# Or edit gradle.properties
org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m
```

### Build Cache Issues

**Symptom**: Old code still running after changes

**Solution**:
```bash
# Clean build directory
./gradlew clean build

# Or disable build cache
./gradlew build --no-build-cache
```

## Runtime Issues

### Authentication Fails with Correct Credentials

**Symptom**: Login fails even with correct username/password

**Cause**: Password not BCrypt encoded in database

**Solution**: Ensure test data uses encoded passwords
```kotlin
val encoded = passwordEncoder.encode("password")
user.password = encoded
```

**Check encoding**:
```sql
SELECT username, password FROM spring_user;
-- BCrypt passwords start with $2a$ or $2b$
```

### CSRF Token Errors

**Symptom**: `Invalid CSRF Token` on POST/PUT/DELETE

**Cause**: CSRF protection enabled for endpoint

**Solution**: CSRF is disabled for `/api/**`. Update `SecurityConfig.kt`:
```kotlin
http.csrf { csrf ->
    csrf.ignoringRequestMatchers(
        AntPathRequestMatcher("/home"),
        AntPathRequestMatcher("/api/**"),
        AntPathRequestMatcher("/your-endpoint/**")  // Add here
    )
}
```

### Text Extraction Failures

**Symptom**: `[Text extraction failed: ...]` in bodyText

**Diagnosis**: Check logs for Tika errors
```bash
./gradlew bootRun | grep -i "extraction\|tika"
```

**Common Causes**:
1. **File too large**: Reduce max size or increase limit
2. **Unsupported format**: Check Tika supported formats
3. **Corrupted file**: Verify file integrity
4. **Memory limit**: Increase JVM heap size

**Solutions**:
```kotlin
// Increase max text extraction size
private const val MAX_TEXT_EXTRACT_SIZE = 20 * 1024 * 1024  // 20MB

// Or skip problematic files
if (fileSize > MAX_TEXT_EXTRACT_SIZE) {
    dto.bodyText = "[File too large]"
    dto.contentType = textExtractionService.detectMimeType(item)
}
```

## Spring Modulith Issues

### Cyclic Dependency Detected

**Symptom**: ModularityTest fails with "Module cycle detected"

**Diagnosis**: Check generated docs
```bash
./gradlew test --tests ModularityTest
open build/spring-modulith-docs/index.html
```

**Solution**: Break cycle using events
```kotlin
// Instead of: moduleA → moduleB → moduleA
// Use: moduleA → event → moduleB (listener)

@Component
class ModuleAService(
    private val publisher: ApplicationEventPublisher  // Not moduleB service!
) {
    fun doSomething() {
        publisher.publishEvent(SomethingHappenedEvent())
    }
}

@Component
class ModuleBListener {
    @EventListener
    fun onSomethingHappened(event: SomethingHappenedEvent) {
        // React to event
    }
}
```

### Cannot Access Internal Package

**Symptom**: `cannot access com.oconeco.spring_search_tempo.base.domain`

**Cause**: Accessing internal package from another module

**Solution**: Use service layer
```kotlin
// BAD
@Component
class MyComponent(
    private val fsFileRepository: FSFileRepository  // ❌ Internal
)

// GOOD
@Component
class MyComponent(
    private val fsFileService: FSFileService  // ✅ Public API
)
```

## Performance Issues

### Slow File Crawling

**Diagnosis**: Check batch processing metrics
```bash
# View batch logs
./gradlew bootRun | grep -i "batch\|step\|chunk"
```

**Solutions**:
1. **Increase chunk size** (FileProcessor):
```kotlin
.chunk<Path, FSFileDTO>(500, transactionManager)  // Increase from 100
```

2. **Skip text extraction for large files**:
```kotlin
if (fileSize > MAX_TEXT_EXTRACT_SIZE) {
    dto.bodyText = null  // Don't extract
    dto.analysisStatus = AnalysisStatus.LOCATE
}
```

3. **Use parallel processing** (future enhancement)

### High Memory Usage

**Diagnosis**: Generate heap dump
```bash
jmap -dump:live,format=b,file=heapdump.hprof <PID>
```

**Solutions**:
1. **Reduce chunk size**: Process fewer items per transaction
2. **Increase JVM heap**: `-Xmx2g`
3. **Clear caches**: Review entity caching settings
4. **Stream large results**: Use pagination in queries

### Slow Queries

**Diagnosis**: Enable SQL logging
```yaml
spring:
  jpa:
    show-sql: true
    properties:
      hibernate:
        format_sql: true
        use_sql_comments: true
logging:
  level:
    org.hibernate.SQL: DEBUG
    org.hibernate.type.descriptor.sql.BasicBinder: TRACE
```

**Solutions**:
1. **Add indexes**: On frequently queried columns
2. **Use JOIN FETCH**: Avoid N+1 queries
3. **Pagination**: For large result sets
4. **Query optimization**: Review generated SQL

## Docker Issues

### Container Won't Start

**Symptom**: `docker compose up` fails

**Diagnosis**:
```bash
# View detailed logs
docker compose logs postgres

# Check container status
docker compose ps

# Inspect container
docker inspect <container_id>
```

**Solutions**:
```bash
# Remove old containers and volumes
docker compose down -v

# Rebuild and start fresh
docker compose up -d --force-recreate

# Check disk space
df -h
```

### Port Conflict with Host PostgreSQL

## Git Issues

### Hooks Configuration

**Symptom**: Git hooks failing or blocking operations

**Diagnosis**: Check hooks configuration
```bash
ls -la .git/hooks/
cat .git/hooks/pre-commit
```

**Solution**: Review `.claude/hooks` configuration or disable temporarily
```bash
# Bypass hooks (use with caution)
git commit --no-verify
```

## Getting Help

### Enable Debug Logging

```yaml
logging:
  level:
    root: INFO
    com.oconeco.spring_search_tempo: DEBUG
    org.springframework.batch: DEBUG
    org.springframework.security: DEBUG
```

### Generate Thread Dump

```bash
# Find Java process
jps -l

# Generate thread dump
jstack <PID> > threaddump.txt
```

### Check Actuator Health

```bash
curl http://localhost:8089/actuator/health
curl http://localhost:8089/actuator/metrics
curl http://localhost:8089/actuator/env
```

### Common Log Locations

```bash
# Application logs (console output)
./gradlew bootRun 2>&1 | tee app.log

# PostgreSQL logs
docker compose logs postgres > postgres.log

# Test logs
cat build/reports/tests/test/index.html
```

## Still Stuck?

1. **Check logs**: Application, database, and test logs
2. **Search docs**: Full documentation in `docs/` folder
3. **Review tests**: Existing tests show working examples
4. **Check GitHub issues**: Similar problems may be documented
5. **Clean rebuild**: `./gradlew clean build --no-cache`
6. **Ask for help**: Provide logs and steps to reproduce

## See Also

- [Commands Reference](commands.md)
- [Configuration Reference](configuration.md)
- [Testing Guide](../guides/testing.md)
- [Module Design](../architecture/module-design.md)
