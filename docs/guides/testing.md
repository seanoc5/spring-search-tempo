# Testing Guide

This guide covers the testing strategy, infrastructure, and best practices for Spring Search Tempo.

---

## Overview

The project uses a multi-layered testing approach:

| Test Type | Framework | Purpose |
|-----------|-----------|---------|
| Unit Tests | JUnit 5 + Mockito | Isolated component testing |
| Integration Tests | Spring Boot Test + Testcontainers | Full stack testing with real database |
| REST API Tests | RestAssured | HTTP endpoint validation |
| Batch Job Tests | Spring Batch Test | Job execution verification |
| Modularity Tests | Spring Modulith | Module boundary verification |

---

## Quick Start

```bash
# Run all tests
./gradlew test

# Run a specific test class
./gradlew test --tests PatternMatchingServiceTest

# Run tests with coverage report
./gradlew test jacocoTestReport

# Force re-run all tests (skip cache)
./gradlew cleanTest test

# Verify module boundaries
./gradlew test --tests ModularityTest
```

---

## Test Infrastructure

### Testcontainers Setup

Integration tests use PostgreSQL 18 in a Docker container via Testcontainers. The container is **reused across test runs** for faster execution.

**Base class**: `src/test/kotlin/.../base/config/BaseIT.kt`

```kotlin
@ActiveProfiles("it")
@Sql(value = ["/data/clearAll.sql", "/data/springUserData.sql"])
@SqlMergeMode(SqlMergeMode.MergeMode.MERGE)
abstract class BaseIT {

    @LocalServerPort
    var serverPort = 0

    companion object {
        @ServiceConnection
        val postgreSQLContainer = PostgreSQLContainer("postgres:18.0")

        init {
            postgreSQLContainer.withReuse(true).start()
        }
    }
}
```

**Key features**:
- `@ServiceConnection` auto-configures the datasource
- `withReuse(true)` keeps container running between test runs
- `@Sql` annotations clear and seed data before each test
- `@ActiveProfiles("it")` activates test-specific configuration

### Stopping Testcontainers

If you need to reset the container (e.g., after schema changes):

```bash
docker stop $(docker ps -q --filter ancestor=postgres:18.0)
```

---

## Unit Tests

### Service Layer Tests

Unit tests for services use Mockito to mock dependencies.

**Example**: `PatternMatchingServiceTest.kt`

```kotlin
@ExtendWith(MockitoExtension::class)
class PatternMatchingServiceTest {

    @Mock
    private lateinit var crawlConfiguration: CrawlConfiguration

    @InjectMocks
    private lateinit var patternMatchingService: PatternMatchingService

    @Test
    @DisplayName("should return SKIP for hidden directories")
    fun testHiddenDirectory() {
        // Setup mocks
        whenever(crawlConfiguration.crawls).thenReturn(mapOf(...))

        // Execute
        val result = patternMatchingService.getAnalysisStatusForFolder(path, "testCrawl")

        // Verify
        assertEquals(AnalysisStatus.SKIP, result)
    }
}
```

**Best practices**:
- Use `@DisplayName` for readable test names
- One assertion per test when possible
- Test edge cases (null, empty, invalid input)
- Use `whenever()` from Mockito-Kotlin for cleaner syntax

### Text Extraction Tests

**Example**: `TextExtractionServiceTest.kt`

```kotlin
class TextExtractionServiceTest {

    private val textExtractionService = TextExtractionService()

    @Test
    fun `should extract text from plain text file`() {
        val tempFile = createTempFile("test", ".txt")
        tempFile.writeText("Hello World")

        val result = textExtractionService.extractText(tempFile.toPath())

        assertInstanceOf(TextExtractionResult.Success::class.java, result)
        assertEquals("Hello World", (result as TextExtractionResult.Success).text)
    }
}
```

---

## Integration Tests

### REST API Tests

Integration tests use RestAssured for HTTP testing with a running Spring Boot context.

**Example**: `FSFileResourceTest.kt`

```kotlin
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql("/data/fSFileData.sql")
class FSFileResourceTest : BaseIT() {

    @Test
    fun `getAllFSFiles should return 200`() {
        given()
            .auth().basic(LOGIN, PASSWORD)
            .accept(ContentType.JSON)
        .`when`()
            .get("/api/fSFiles")
        .then()
            .statusCode(HttpStatus.SC_OK)
            .body("page.totalElements", equalTo(2))
    }

    @Test
    fun `createFSFile should return 201`() {
        given()
            .auth().basic(LOGIN, PASSWORD)
            .accept(ContentType.JSON)
            .contentType(ContentType.JSON)
            .body(readResource("/requests/fSFileDTORequest.json"))
        .`when`()
            .post("/api/fSFiles")
        .then()
            .statusCode(HttpStatus.SC_CREATED)
    }
}
```

**Key patterns**:
- Extend `BaseIT` for Testcontainers and RestAssured setup
- Use `@Sql` to load test-specific data
- Use `readResource()` helper for JSON request bodies
- Always include authentication for secured endpoints

### Test Data Files

Test data is stored in `src/test/resources/data/`:

| File | Purpose |
|------|---------|
| `clearAll.sql` | Truncates all tables before each test |
| `springUserData.sql` | Creates test users with roles |
| `fSFileData.sql` | Sample FSFile records |
| `fSFolderData.sql` | Sample FSFolder records |
| `contentChunksData.sql` | Sample ContentChunk records |
| `annotationData.sql` | Sample Annotation records |

**Example** (`fSFileData.sql`):
```sql
INSERT INTO fs_file (id, uri, label, version, date_created, last_updated)
VALUES (1000, '/test/file1.txt', 'Test File 1', 0, NOW(), NOW());
```

---

## Spring Batch Tests

### Job Launcher Tests

**Example**: `NLPJobLauncherTest.kt`

```kotlin
@ExtendWith(MockitoExtension::class)
class NLPJobLauncherTest {

    @Mock
    private lateinit var jobLauncher: JobLauncher

    @Mock
    private lateinit var nlpProcessingJob: Job

    @InjectMocks
    private lateinit var nlpJobLauncher: NLPJobLauncher

    @Test
    fun `should launch NLP job with parameters`() {
        whenever(jobLauncher.run(any(), any())).thenReturn(mockExecution(BatchStatus.COMPLETED))

        val result = nlpJobLauncher.launchNLPJob()

        assertTrue(result)
        verify(jobLauncher).run(eq(nlpProcessingJob), any())
    }
}
```

### Chunk Processing Tests

**Example**: `NLPChunkProcessorTest.kt`

```kotlin
@ExtendWith(MockitoExtension::class)
class NLPChunkProcessorTest {

    @Mock
    private lateinit var nlpService: NLPService

    @InjectMocks
    private lateinit var processor: NLPChunkProcessor

    @Test
    fun `should process chunk with NLP annotations`() {
        val chunk = ContentChunk().apply { text = "John works at Google." }
        whenever(nlpService.analyze(any())).thenReturn(NLPResult(...))

        val result = processor.process(chunk)

        assertNotNull(result?.namedEntities)
        assertNotNull(result?.sentiment)
    }
}
```

### Integration Tests with JobLauncherTestUtils

For full job execution testing:

```kotlin
@SpringBootTest
@SpringBatchTest
class ChunkingStepTest : BaseIT() {

    @Autowired
    private lateinit var jobLauncherTestUtils: JobLauncherTestUtils

    @Test
    fun `chunking step should process files`() {
        val execution = jobLauncherTestUtils.launchStep("chunkingStep")

        assertEquals(BatchStatus.COMPLETED, execution.status)
        assertTrue(execution.writeCount > 0)
    }
}
```

---

## Modularity Tests

Spring Modulith enforces module boundaries at test time.

**File**: `ModularityTest.kt`

```kotlin
class ModularityTest {

    private val modules = ApplicationModules.of(SpringSearchTempoApplication::class.java)

    @Test
    @Disabled("Intentional violation for batch performance - see comments")
    fun verifyModuleStructure() {
        modules.verify()
    }

    @Test
    fun createModuleDocumentation() {
        Documenter(modules)
            .writeDocumentation()
            .writeIndividualModulesAsPlantUml()
    }
}
```

**Current status**: The `verifyModuleStructure()` test is disabled because the batch module intentionally accesses base repositories for performance. This is documented and acceptable.

**When to enable**:
- After refactoring batch module to use services
- For validating new module additions

---

## Test Coverage

### Running Coverage Reports

```bash
./gradlew test jacocoTestReport
```

Report location: `build/reports/jacoco/test/html/index.html`

### Coverage Goals

| Module | Target | Current |
|--------|--------|---------|
| base/service | 80% | ~20% |
| base/rest | 70% | ~38% |
| batch/nlp | 70% | ~75% |
| batch/fscrawl | 60% | ~10% |

**Priority areas for improvement**:
1. `FullTextSearchService` - Core search functionality
2. `CombinedCrawlProcessor` - File crawl backbone
3. Web controllers - User-facing features

---

## Best Practices

### Test Naming

Use backtick syntax for readable test names:

```kotlin
@Test
fun `should return 404 when file not found`() { ... }

@Test
fun `should extract metadata from PDF files`() { ... }
```

### Test Isolation

- Each test should be independent
- Use `@Sql` for data setup, not `@BeforeEach` with repository calls
- Don't rely on test execution order

### Mock vs Real Dependencies

| Use Mocks | Use Real |
|-----------|----------|
| External services (NLP, IMAP) | Database (via Testcontainers) |
| File system operations | Spring context |
| Slow operations | Transaction management |

### Assertions

Use AssertJ for fluent assertions:

```kotlin
assertThat(result)
    .isNotNull()
    .hasSize(3)
    .extracting("name")
    .containsExactly("a", "b", "c")
```

---

## Troubleshooting

### Container Won't Start

```bash
# Check if port is in use
docker ps -a | grep postgres

# Stop and remove old containers
docker stop $(docker ps -q --filter ancestor=postgres:18.0)
docker rm $(docker ps -aq --filter ancestor=postgres:18.0)
```

### Tests Pass Locally but Fail in CI

1. Check for timezone-dependent assertions
2. Verify file paths use `Path` not hardcoded strings
3. Ensure no tests depend on execution order

### Slow Test Execution

1. Verify Testcontainers reuse is enabled
2. Check for unnecessary `@SpringBootTest` on unit tests
3. Use `@WebMvcTest` instead of full context for controller tests

### LazyInitializationException in Tests

Add `@Transactional` to test class or use `JOIN FETCH` queries:

```kotlin
@Test
@Transactional
fun `should load related entities`() {
    val file = fileRepository.findByIdWithChunks(id)
    assertNotNull(file.contentChunks)
}
```

---

## Adding New Tests

### For a New Service

1. Create test class in `src/test/kotlin/.../base/service/`
2. Use `@ExtendWith(MockitoExtension::class)`
3. Mock dependencies with `@Mock`
4. Test happy path, edge cases, and error conditions

### For a New REST Endpoint

1. Create test class in `src/test/kotlin/.../base/rest/` or `.../web/rest/`
2. Extend `BaseIT`
3. Add `@Sql` for test data
4. Test all HTTP methods and status codes

### For a New Batch Job

1. Create test class in `src/test/kotlin/.../batch/`
2. Use `@SpringBatchTest` for `JobLauncherTestUtils`
3. Test job execution, step completion, and error handling

---

**Last Updated**: 2026-02-21
