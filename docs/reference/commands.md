# Build & Run Commands Reference

Complete reference for building, running, and testing Spring Search Tempo.

## Development

### Start Application

```bash
# Start with default 'local' profile
./gradlew bootRun

# Start with specific profile
./gradlew bootRun --args='--spring.profiles.active=dev'

# Start with custom port
./gradlew bootRun --args='--server.port=9090'

# Start with debug enabled
./gradlew bootRun --args='--debug'
```

**Access application**: http://localhost:8082

**Custom local config**: Create `src/main/resources/application-local.yml`

### Stop Application

```bash
# Ctrl+C in terminal
# Or find and kill process:
lsof -i :8082
kill -9 <PID>
```

## Testing

### Run All Tests

```bash
# Run all tests
./gradlew test

# Run tests with info logging
./gradlew test --info

# Run tests in debug mode
./gradlew test --debug

# Run tests and generate coverage report
./gradlew test jacocoTestReport
```

**Coverage report**: `build/reports/jacoco/test/html/index.html`

### Run Specific Tests

```bash
# Run specific test class
./gradlew test --tests ModularityTest

# Run specific test method
./gradlew test --tests "FSFileServiceTest.testFindById"

# Run all tests in a package
./gradlew test --tests "com.oconeco.spring_search_tempo.base.*"

# Run tests matching pattern
./gradlew test --tests "*Repository*"

# Run tests by category
./gradlew test --tests "*IntegrationTest"
```

### Clean Test Cache

```bash
# Force tests to re-run (ignore up-to-date check)
./gradlew cleanTest test

# Clean and test specific class
./gradlew cleanTest test --tests ChunkingStepTest
```

## Building

### Development Builds

```bash
# Quick build (no tests)
./gradlew build -x test

# Full build with tests
./gradlew build

# Clean build (removes old artifacts)
./gradlew clean build
```

**Build output**: `build/libs/spring-search-tempo-0.0.1-SNAPSHOT.jar`

### Production Builds

```bash
# Build production JAR
./gradlew clean build -Pproduction

# Build optimized JAR
./gradlew clean build --no-daemon --max-workers=4

# Build and skip tests
./gradlew clean assemble
```

### Build Docker Image

```bash
# Build Docker image using Spring Boot plugin
./gradlew bootBuildImage --imageName=com.oconeco/spring-search-tempo

# Build with custom image name and tag
./gradlew bootBuildImage \
  --imageName=com.oconeco/spring-search-tempo:1.0.0

# Run built image
docker run -p 8082:8082 com.oconeco/spring-search-tempo
```

## Running Built JAR

```bash
# Run JAR with default profile
java -jar build/libs/spring-search-tempo-0.0.1-SNAPSHOT.jar

# Run with specific profile
java -Dspring.profiles.active=production \
     -jar build/libs/spring-search-tempo-0.0.1-SNAPSHOT.jar

# Run with custom port
java -Dserver.port=9090 \
     -jar build/libs/spring-search-tempo-0.0.1-SNAPSHOT.jar

# Run with increased memory
java -Xmx2g -Xms512m \
     -jar build/libs/spring-search-tempo-0.0.1-SNAPSHOT.jar

# Run with JVM debug enabled
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005 \
     -jar build/libs/spring-search-tempo-0.0.1-SNAPSHOT.jar
```

## Database Management

### Start/Stop PostgreSQL

```bash
# Start PostgreSQL (via Docker Compose)
docker compose up -d

# Stop PostgreSQL
docker compose down

# Stop and remove volumes (deletes data)
docker compose down -v

# Restart PostgreSQL
docker compose restart postgres
```

### View Database Logs

```bash
# Follow logs
docker compose logs -f postgres

# View last 100 lines
docker compose logs --tail=100 postgres
```

### Connect to Database

```bash
# Connect using psql
psql -h localhost -p 5432 -U tempo -d tempo

# Connect and execute query
psql -h localhost -p 5432 -U tempo -d tempo \
     -c "SELECT COUNT(*) FROM fs_file;"

# Connect with password from environment
PGPASSWORD=postgres psql -h localhost -p 5432 -U tempo -d tempo
```

### Database Backup & Restore

```bash
# Backup database
pg_dump -h localhost -p 5432 -U tempo -d tempo \
        > backup_$(date +%Y%m%d).sql

# Restore database
psql -h localhost -p 5432 -U tempo -d tempo \
     < backup_20251107.sql

# Backup with compression
pg_dump -h localhost -p 5432 -U tempo -d tempo \
        | gzip > backup_$(date +%Y%m%d).sql.gz
```

## Batch Jobs

### Run Specific Job

```bash
# Run specific job on startup
./gradlew bootRun --args='--spring.batch.job.name=importUserJob'

# Run with job parameters
./gradlew bootRun --args='--spring.batch.job.name=fsCrawlJob --crawlName=user-content'

# Disable batch jobs on startup
./gradlew bootRun --args='--spring.batch.job.enabled=false'
```

### NLP Processing

```bash
# Trigger NLP processing via REST API
curl -X POST http://localhost:8082/api/nlp/process

# Check NLP status
curl http://localhost:8082/api/nlp/status

# Disable NLP auto-trigger (in application.yml or command line)
./gradlew bootRun --args='--app.nlp.auto-trigger=false'
```

NLP processing runs automatically after file crawl by default. See [NLP Processing Guide](../guides/nlp-processing.md).

### Clear Batch Metadata

```bash
# Connect to database and clear batch tables
psql -h localhost -p 5432 -U tempo -d tempo <<EOF
DELETE FROM batch_step_execution_context;
DELETE FROM batch_step_execution;
DELETE FROM batch_job_execution_context;
DELETE FROM batch_job_execution_params;
DELETE FROM batch_job_execution;
DELETE FROM batch_job_instance;
EOF
```

## Spring Modulith

### Verify Module Structure

```bash
# Run modularity tests
./gradlew test --tests ModularityTest

# View generated documentation
open build/spring-modulith-docs/index.html

# Generate docs without running all tests
./gradlew test --tests ModularityTest -x test
```

## Dependency Management

### View Dependencies

```bash
# View dependency tree
./gradlew dependencies

# View runtime dependencies
./gradlew dependencies --configuration runtimeClasspath

# View compile dependencies
./gradlew dependencies --configuration compileClasspath

# Check for dependency updates
./gradlew dependencyUpdates
```

### Refresh Dependencies

```bash
# Refresh dependencies (clears cache)
./gradlew build --refresh-dependencies

# Clean Gradle cache
rm -rf ~/.gradle/caches/
./gradlew build
```

## Code Quality

### Linting & Formatting

```bash
# Check Kotlin code style (if ktlint configured)
./gradlew ktlintCheck

# Format Kotlin code
./gradlew ktlintFormat
```

### Test Coverage

```bash
# Generate coverage report
./gradlew test jacocoTestReport

# View report
open build/reports/jacoco/test/html/index.html

# Check coverage thresholds
./gradlew jacocoTestCoverageVerification
```

## Gradle Tasks

### List All Tasks

```bash
# List all available tasks
./gradlew tasks

# List tasks with details
./gradlew tasks --all

# List tasks in specific group
./gradlew tasks --group="build"
```

### Clean Tasks

```bash
# Clean build directory
./gradlew clean

# Clean kapt generated files
./gradlew cleanKapt

# Clean test results
./gradlew cleanTest
```

## IDE Integration

### IntelliJ IDEA

```bash
# Generate IntelliJ IDEA project files
./gradlew idea

# Refresh Gradle project in IntelliJ
# File → Reload All from Disk
# Or: Gradle tool window → Refresh
```

### Eclipse

```bash
# Generate Eclipse project files
./gradlew eclipse

# Clean Eclipse files
./gradlew cleanEclipse
```

## Debugging

### Debug Application

```bash
# Start with debug port 5005
./gradlew bootRun --args='--debug-jvm'

# Or manually with JVM debug options
./gradlew bootRun -Dagentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005
```

**Connect debugger**: localhost:5005

### Debug Tests

```bash
# Run tests with debug enabled
./gradlew test --debug-jvm

# Run specific test with debug
./gradlew test --tests MyTest --debug-jvm
```

## Performance & Monitoring

### Heap Dump

```bash
# Generate heap dump while running
jmap -dump:live,format=b,file=heapdump.hprof <PID>

# Find Java process
jps -l
```

### Thread Dump

```bash
# Generate thread dump
jstack <PID> > threaddump.txt

# Or send QUIT signal (SIGQUIT)
kill -3 <PID>
```

### Actuator Endpoints

```bash
# Health check
curl http://localhost:8082/actuator/health

# Application info
curl http://localhost:8082/actuator/info

# Metrics
curl http://localhost:8082/actuator/metrics

# Batch jobs
curl http://localhost:8082/actuator/batch
```

## CI/CD Commands

### Continuous Integration

```bash
# CI build (fast, no tests)
./gradlew clean assemble -x test

# CI with tests
./gradlew clean build

# CI with coverage
./gradlew clean build jacocoTestReport jacocoTestCoverageVerification
```

### Production Deployment

```bash
# Build production-ready JAR
./gradlew clean build -Pproduction

# Build and push Docker image
./gradlew bootBuildImage --imageName=registry.example.com/spring-search-tempo
docker push registry.example.com/spring-search-tempo
```

## Troubleshooting Commands

### Clear Gradle Cache

```bash
# Clear Gradle build cache
./gradlew clean --no-build-cache

# Delete entire cache directory
rm -rf ~/.gradle/caches/
./gradlew build
```

### Fix Kapt Issues

```bash
# Clean kapt generated files
./gradlew cleanKapt
rm -rf build/generated/source/kapt
./gradlew build
```

### Fix Port Conflicts

```bash
# Find process using port 8082
lsof -i :8082

# Kill process
kill -9 <PID>

# Or use different port
./gradlew bootRun --args='--server.port=9090'
```

### Stop Testcontainers

```bash
# Stop all PostgreSQL Testcontainers
docker stop $(docker ps -q --filter ancestor=postgres:18.0)

# Remove all stopped containers
docker container prune -f
```

## Environment Variables

### Common Environment Variables

```bash
# Set Gradle options
export GRADLE_OPTS="-Xmx2048m -XX:MaxMetaspaceSize=512m"

# Set Java home
export JAVA_HOME=/path/to/jdk

# Set Spring profile
export SPRING_PROFILES_ACTIVE=dev

# Set database password
export SPRING_DATASOURCE_PASSWORD=securepassword

# Run with environment variables
SPRING_PROFILES_ACTIVE=production ./gradlew bootRun
```

## Git Operations

### Commit Changes

```bash
# Stage all changes
git add .

# Commit with message
git commit -m "Your commit message"

# Push to remote
git push
```

### View Status

```bash
# Git status
git status

# View diff
git diff

# View staged diff
git diff --staged
```

## Quick Reference

| Task | Command                                                     |
|------|-------------------------------------------------------------|
| Start app | `./gradlew bootRun`                                         |
| Run tests | `./gradlew test`                                            |
| Build JAR | `./gradlew build`                                           |
| Start DB | `docker compose up -d`                                      |
| Connect DB | `psql -h localhost -p 5432 -U tempo -d tempo` |
| View logs | `docker compose logs -f postgres`                           |
| Clean build | `./gradlew clean build`                                     |
| Run specific test | `./gradlew test --tests TestName`                           |
| Module verification | `./gradlew test --tests ModularityTest`                     |
| Coverage report | `./gradlew test jacocoTestReport`                           |
| Trigger NLP | `curl -X POST http://localhost:8082/api/nlp/process`        |

## See Also

- [Troubleshooting Guide](troubleshooting.md)
- [Configuration Reference](configuration.md)
- [Testing Guide](../guides/testing.md)
