# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## Project Overview

**Spring Search Tempo** is a Spring Boot 3.5 application written in Kotlin that provides a starting template for a full-text search engine. The primary use case is local file system crawling with extensibility for other data sources.

**Purpose**: Enable fast, intelligent search across diverse content sources (files, browser history, emails) with configurable processing levels from simple metadata indexing to advanced NLP analysis.

**Target Users**: Developers building search applications, personal knowledge management systems, or enterprise content discovery solutions.

---

## Current Status & Roadmap

### ✅ Phase 1: Core Foundation (CURRENT - Mostly Complete)

**Implemented**:
- Core domain model (FSFile, FSFolder, ContentChunks, Annotations)
- JPA repositories with PostgreSQL backend
- Spring Security with basic authentication
- RESTful API with HATEOAS support
- Thymeleaf + HTMX web UI
- Spring Batch integration (example job)
- Spring Modulith modular architecture
- MapStruct DTO mapping
- Custom validation framework
- Entity lifecycle event handling
- Testcontainers-based testing

**In Progress**:
- Multi-crawl configuration system
- Hierarchical pattern matching for file processing
- Processing level implementation (IGNORE, LOCATE, INDEX)

**Remaining Phase 1 Work**:
- Finalize crawl configuration format and loader
- Implement incremental crawl using timestamps
- Configure PostgreSQL full-text search (FTS)

### 📋 Phase 2: Advanced Text Processing (PLANNED)

- Stanford CoreNLP integration for NLP annotations
- Sentence-level chunking with linguistic metadata
- Named entity recognition
- Part-of-speech tagging and dependency parsing
- Firefox browser history and bookmarks indexing
- Enhanced search capabilities with linguistic filters

### 📋 Phase 3: Semantic Search (FUTURE)

- Vector embeddings for semantic search
- Paragraph-level chunking for HTML content
- Multi-level embedding (document, paragraph, sentence)
- Similarity-based retrieval
- Hybrid search (keyword + semantic)

---

## Quick Start

### Development Workflow

```bash
# Start application (auto-starts PostgreSQL via Docker Compose)
./gradlew bootRun
# Accessible at http://localhost:8089

# Run tests
./gradlew test

# Build production artifact
./gradlew clean build
```

### Common Tasks

**Add a new entity**: Extend `FSObject` or create standalone entity → Add repository → Create service interface + impl → Add mapper → Create controller/resource

**Run specific test**: `./gradlew test --tests YourTestClass`

**Change database port**: Edit `docker-compose.yml` and `application.yml` port mappings

**Add custom validation**: Create validator implementing `ConstraintValidator` → Create annotation → Apply to field

---

## Architecture

### Module Structure (Spring Modulith)

The application uses Spring Modulith for modular monolith architecture. Modules are verified by `ModularityTest` which generates documentation in `build/spring-modulith-docs/`.

#### Module: `base`
**Purpose**: Core business logic and domain model
**Responsibilities**:
- File system abstraction (FSFile, FSFolder)
- Text processing and chunking (ContentChunks)
- User management (SpringUser, SpringRole)
- Annotation/tagging system
- Security configuration
- Domain services and repositories

**Key Packages**:
- `domain/`: JPA entities
- `repos/`: Spring Data repositories
- `service/`: Service layer (interfaces + implementations)
- `controller/`: Thymeleaf/HTMX web controllers
- `rest/`: REST API resources with HATEOAS
- `config/`: Configuration classes
- `events/`: Domain event definitions
- `model/`: DTOs and validation annotations
- `util/`: Shared utilities

#### Module: `batch`
**Purpose**: Spring Batch job processing
**Responsibilities**:
- Batch job definitions
- Item readers, processors, writers
- Job scheduling and execution

**Current Jobs**:
- `importUserJob`: Example person data import (runs on startup)

#### Module: `web`
**Purpose**: Public-facing web layer
**Responsibilities**:
- Public controllers (HomeController)
- Public REST endpoints (HomeResource)
- Static content serving

### Spring Modulith Communication Patterns

**✅ DO**:
- Use application events for cross-module communication
- Define clear API boundaries with package-info.java
- Keep module dependencies unidirectional
- Expose only necessary classes via `@ApplicationModule(allowedDependencies = ...)`

**❌ DON'T**:
- Access repositories directly from other modules
- Create circular module dependencies
- Expose internal implementation details
- Share entities across module boundaries (use DTOs/events)

### Domain Model

#### File System Abstraction

```kotlin
@Entity
@Inheritance(strategy = InheritanceType.JOINED)
abstract class FSObject {
    @Id @GeneratedValue
    var id: Long? = null
    var uri: String = ""
    var lastModified: Instant? = null
    // ... common properties
}

@Entity
class FSFolder : FSObject() {
    @OneToMany(mappedBy = "parent")
    var children: MutableSet<FSObject> = mutableSetOf()
}

@Entity
class FSFile : FSObject() {
    @Column(columnDefinition = "TEXT")
    var bodyText: String? = null

    @OneToMany(mappedBy = "fsFile")
    var contentChunks: MutableSet<ContentChunks> = mutableSetOf()
}
```

**Key Relationships**:
- FSFolder → FSObject (one-to-many children)
- FSFile → ContentChunks (one-to-many chunks)
- ContentChunks → ContentChunks (hierarchical parent-child)

#### Text Processing Model

```kotlin
@Entity
class ContentChunks {
    @Id @GeneratedValue
    var id: Long? = null

    // Chunking metadata
    var chunkType: String? = null  // e.g., "Sentence", "Paragraph"
    var chunkNumber: Int? = null
    var startPosition: Int? = null
    var endPosition: Int? = null

    // Content
    @Column(columnDefinition = "TEXT")
    var chunkText: String? = null

    // NLP annotations (Phase 2)
    var namedEntities: String? = null
    var nouns: String? = null
    var verbs: String? = null
    var tokenAnnotations: String? = null
    var parseTree: String? = null
    var conllu: String? = null

    // Search vectors (Phase 1 & 3)
    var ftsVector: String? = null      // PostgreSQL FTS
    var vectorEmbedding: String? = null // Semantic embeddings

    // Relationships
    @ManyToOne
    var fsFile: FSFile? = null

    @ManyToOne
    var parentChunk: ContentChunks? = null
}
```

#### User Management

```kotlin
@Entity
class SpringUser {
    @Id @GeneratedValue
    var id: Long? = null
    var username: String = ""
    var password: String = ""
    var email: String? = null

    @ManyToMany(fetch = FetchType.EAGER)
    var roles: MutableSet<SpringRole> = mutableSetOf()
}

@Entity
class SpringRole {
    @Id @GeneratedValue
    var id: Long? = null
    var name: String = ""
}
```

#### Annotations (Tagging System)

```kotlin
@Entity
class Annotation {
    @Id @GeneratedValue
    var id: Long? = null
    var label: String = ""
    var description: String? = null

    @ManyToMany
    var fsObjects: MutableSet<FSObject> = mutableSetOf()
}
```

### Data Flow

#### Typical Request Lifecycle

```
1. HTTP Request → Web/Base Controller
2. Controller validates input, calls Service
3. Service performs business logic
4. Service accesses Repository (JPA)
5. Repository queries PostgreSQL
6. Results mapped to DTOs via MapStruct
7. Events published for cross-cutting concerns
8. DTOs returned to Controller
9. Response rendered (Thymeleaf/JSON)
```

#### Event-Driven Patterns

```kotlin
// Publishing events
@Component
class FSFileService {
    fun deleteFile(id: Long) {
        // Business logic
        publisher.publishEvent(FSFileDeletedEvent(fileUri))
    }
}

// Listening to events
@Component
class SearchIndexUpdater {
    @EventListener
    fun onFileDeleted(event: FSFileDeletedEvent) {
        // Update search index
    }
}
```

---

## Code Patterns & Examples

### Adding a New Entity

**Step 1**: Create entity extending base class or standalone

```kotlin
@Entity
class FSEmail : FSObject() {
    var subject: String = ""
    var sender: String = ""
    var recipients: String = ""

    @Column(columnDefinition = "TEXT")
    var body: String? = null

    var receivedDate: Instant? = null
}
```

**Step 2**: Create repository

```kotlin
interface FSEmailRepository : JpaRepository<FSEmail, Long> {
    fun findBySender(sender: String): List<FSEmail>
}
```

**Step 3**: Create DTO and mapper

```kotlin
data class FSEmailDTO(
    val id: Long?,
    val uri: String,
    val subject: String,
    val sender: String
)

@Mapper(componentModel = "spring")
interface FSEmailMapper {
    fun toDTO(entity: FSEmail): FSEmailDTO
    fun toEntity(dto: FSEmailDTO): FSEmail
}
```

**Step 4**: Create service interface and implementation

```kotlin
interface FSEmailService {
    fun findById(id: Long): FSEmailDTO
    fun findBySender(sender: String): List<FSEmailDTO>
    fun save(dto: FSEmailDTO): FSEmailDTO
}

@Service
class FSEmailServiceImpl(
    private val repository: FSEmailRepository,
    private val mapper: FSEmailMapper
) : FSEmailService {
    override fun findById(id: Long): FSEmailDTO {
        val entity = repository.findById(id)
            .orElseThrow { NotFoundException("Email not found") }
        return mapper.toDTO(entity)
    }
    // ... other methods
}
```

**Step 5**: Create controller or REST resource

```kotlin
@RestController
@RequestMapping("/api/emails")
class FSEmailResource(private val service: FSEmailService) {

    @GetMapping("/{id}")
    fun getEmail(@PathVariable id: Long): FSEmailDTO {
        return service.findById(id)
    }
}
```

### Custom Validation

**Pattern**: Annotation + Validator

```kotlin
// 1. Create annotation
@Target(AnnotationTarget.FIELD, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [FSEmailSubjectUniqueValidator::class])
annotation class FSEmailSubjectUnique(
    val message: String = "Email subject must be unique",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Any>> = []
)

// 2. Create validator
@Component
class FSEmailSubjectUniqueValidator(
    private val repository: FSEmailRepository
) : ConstraintValidator<FSEmailSubjectUnique, String> {

    override fun isValid(value: String?, context: ConstraintValidatorContext): Boolean {
        if (value == null) return true
        return repository.findBySubject(value).isEmpty()
    }
}

// 3. Apply to DTO
data class FSEmailDTO(
    val id: Long?,
    @field:FSEmailSubjectUnique
    val subject: String
)
```

### Creating a Batch Job

```kotlin
@Configuration
class EmailImportJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val emailRepository: FSEmailRepository
) {

    @Bean
    fun importEmailJob(): Job {
        return JobBuilder("importEmailJob", jobRepository)
            .start(importEmailStep())
            .build()
    }

    @Bean
    fun importEmailStep(): Step {
        return StepBuilder("importEmailStep", jobRepository)
            .chunk<EmailInput, FSEmail>(100, transactionManager)
            .reader(emailReader())
            .processor(emailProcessor())
            .writer(emailWriter())
            .build()
    }

    @Bean
    fun emailReader(): ItemReader<EmailInput> {
        // Read from CSV, JSON, or external API
        return FlatFileItemReaderBuilder<EmailInput>()
            .name("emailReader")
            .resource(ClassPathResource("emails.csv"))
            .delimited()
            .names("subject", "sender", "body")
            .targetType(EmailInput::class.java)
            .build()
    }

    @Bean
    fun emailProcessor(): ItemProcessor<EmailInput, FSEmail> {
        return ItemProcessor { input ->
            FSEmail().apply {
                subject = input.subject
                sender = input.sender
                body = input.body
                uri = "email://${input.sender}/${input.subject}"
            }
        }
    }

    @Bean
    fun emailWriter(): ItemWriter<FSEmail> {
        return ItemWriter { items ->
            emailRepository.saveAll(items)
        }
    }
}
```

### Publishing and Handling Events

```kotlin
// Event definition
data class FSFileIndexedEvent(
    val fileId: Long,
    val uri: String,
    val timestamp: Instant = Instant.now()
)

// Publishing in service
@Service
class FSFileServiceImpl(
    private val publisher: ApplicationEventPublisher
) {
    fun indexFile(id: Long) {
        // ... indexing logic
        publisher.publishEvent(FSFileIndexedEvent(id, file.uri))
    }
}

// Listening in another component
@Component
class NotificationService {

    @EventListener
    @Async
    fun onFileIndexed(event: FSFileIndexedEvent) {
        logger.info("File indexed: ${event.uri}")
        // Send notification, update UI, etc.
    }
}
```

---

## Configuration

### Configuration Files Hierarchy

Configuration is loaded in this order (later overrides earlier):

1. `application.yml` (base configuration)
2. `application-{profile}.yml` (profile-specific)
3. Environment variables
4. Command-line arguments

**Available Profiles**:
- `local`: Development on developer machine (default for bootRun)
- `dev`: Shared development environment
- `production`: Production deployment

### Database Configuration

```yaml
# application.yml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5433/spring_search_tempo
    username: postgres
    password: postgres
  jpa:
    hibernate:
      ddl-auto: update  # Use 'validate' in production
    show-sql: false     # Set true for debugging
  session:
    jdbc:
      initialize-schema: always
```

**PostgreSQL via Docker Compose**: Port 5433 (not default 5432)

### Security Configuration

```yaml
spring:
  security:
    user:
      name: user
      password: password
```

**Security Rules** (see `SecurityConfig.kt`):
- Basic authentication enabled
- CSRF disabled for: `/home`, `/api/**`, `/actuator/**`
- `/springUsers` GET requires `LOGIN` authority
- Password encoding: BCrypt (delegating encoder)

### Batch Job Configuration

```yaml
spring:
  batch:
    jdbc:
      initialize-schema: always
    job:
      name: importUserJob  # Specific job to run
      enabled: true        # Run on startup
```

**To disable startup jobs**: Set `spring.batch.job.enabled=false`

### Server Configuration

```yaml
server:
  port: 8089
```

### Crawl Configuration (In Development)

**Planned Structure** (not yet implemented):

```yaml
crawl:
  configs:
    - name: "user-content"
      enabled: true
      schedule: "0 2 * * *"  # Daily at 2 AM
      tasks:
        - startPath: "~/Documents"
          processing: "INDEX"
          include:
            files: ".*\\.(txt|md|pdf|docx)$"
            folders: ".*"
          exclude:
            files: ".*\\.(tmp|bak)$"
            folders: ".*(node_modules|.git).*"

        - startPath: "~/Pictures"
          processing: "LOCATE"
          include:
            files: ".*\\.(jpg|png|gif)$"

    - name: "system-files"
      enabled: false
      tasks:
        - startPath: "/usr"
          processing: "LOCATE"
          exclude:
            folders: ".*(tmp|cache).*"
```

**Processing Levels**:
- `IGNORE`: Skip entirely (not stored)
- `LOCATE`: Store metadata only (path, size, timestamps) - like `plocate`
- `INDEX`: Extract and index full text content
- `ANALYZE`: NLP processing with chunking (Phase 2)
- `SEMANTIC`: Vector embeddings for semantic search (Phase 3)

---

## Extension Points

### Adding New File System Types

**Location**: `com.oconeco.springsearchtempo.base.domain`

**Pattern**: Extend `FSObject` abstract class

**Example**: See `FSFile`, `FSFolder` for inheritance pattern

**Requirements**:
- Add `@Entity` annotation
- Define discriminator value if using `JOINED` inheritance
- Add specific properties for the new type
- Create corresponding repository, service, mapper

### Adding New Processing Levels

**Location**: `com.oconeco.springsearchtempo.base.service.crawl` (to be created)

**Pattern**:
1. Create processor interface: `FileProcessor`
2. Implement for each level: `IgnoreProcessor`, `LocateProcessor`, `IndexProcessor`
3. Register in processor factory/registry
4. Update configuration schema to support new level

**Example** (future implementation):

```kotlin
interface FileProcessor {
    fun canProcess(file: Path, level: ProcessingLevel): Boolean
    fun process(file: Path): ProcessingResult
}

@Component
class IndexProcessor : FileProcessor {
    override fun canProcess(file: Path, level: ProcessingLevel): Boolean {
        return level == ProcessingLevel.INDEX
    }

    override fun process(file: Path): ProcessingResult {
        // Use Tika to extract text
        // Store in FSFile.bodyText
        // Return result
    }
}
```

### Adding New Batch Jobs

**Location**: `com.oconeco.springsearchtempo.batch`

**Pattern**: See "Creating a Batch Job" in Code Patterns section

**Requirements**:
- Create `@Configuration` class with job definition
- Define step(s) with reader, processor, writer
- Register job name in `application.yml` if auto-start desired
- Add tests using `@SpringBatchTest`

### Adding Custom Validators

**Location**: `com.oconeco.springsearchtempo.base.util`

**Pattern**: See "Custom Validation" in Code Patterns section

**Steps**:
1. Create annotation with `@Constraint`
2. Implement `ConstraintValidator<YourAnnotation, FieldType>`
3. Inject dependencies (repositories) via constructor
4. Apply annotation to DTO fields

### Adding New REST Endpoints

**Location**: `com.oconeco.springsearchtempo.base.rest` or `com.oconeco.springsearchtempo.web.rest`

**Pattern**: Use HATEOAS with model assemblers

```kotlin
@RestController
@RequestMapping("/api/emails")
class FSEmailResource(
    private val service: FSEmailService,
    private val assembler: FSEmailModelAssembler
) {

    @GetMapping
    fun getAll(): CollectionModel<EntityModel<FSEmailDTO>> {
        val emails = service.findAll()
        return assembler.toCollectionModel(emails)
    }
}

@Component
class FSEmailModelAssembler :
    RepresentationModelAssemblerSupport<FSEmailDTO, EntityModel<FSEmailDTO>>(
        FSEmailResource::class.java,
        EntityModel::class.java
    ) {

    override fun toModel(entity: FSEmailDTO): EntityModel<FSEmailDTO> {
        return EntityModel.of(entity,
            linkTo(methodOn(FSEmailResource::class.java)
                .getEmail(entity.id!!)).withSelfRel(),
            linkTo(methodOn(FSEmailResource::class.java)
                .getAll()).withRel("emails")
        )
    }
}
```

---

## Build & Run Commands

### Development

```bash
# Start application (default 'local' profile)
./gradlew bootRun

# Start with specific profile
./gradlew bootRun --args='--spring.profiles.active=dev'

# Access application
open http://localhost:8089
```

**Custom Local Config**: Create `src/main/resources/application-local.yml` to override defaults

### Testing

```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests ModularityTest

# Run specific test method
./gradlew test --tests "FSFileServiceTest.testFindById"

# Run tests with specific category
./gradlew test --tests "*Repository*"

# Generate test coverage report
./gradlew test jacocoTestReport
# Report: build/reports/jacoco/test/html/index.html
```

### Building

```bash
# Clean build
./gradlew clean build

# Build without tests
./gradlew clean build -x test

# Build Docker image
./gradlew bootBuildImage --imageName=com.oconeco/spring-search-tempo

# Run built JAR
java -Dspring.profiles.active=production \
     -jar build/libs/spring-search-tempo-0.0.1-SNAPSHOT.jar
```

### Database Management

```bash
# Start PostgreSQL manually
docker compose up -d

# Stop PostgreSQL
docker compose down

# View logs
docker compose logs -f postgres

# Connect to database
psql -h localhost -p 5433 -U postgres -d spring_search_tempo
```

### Spring Modulith Documentation

```bash
# Generate module documentation
./gradlew test --tests ModularityTest

# View generated docs
open build/spring-modulith-docs/index.html
```

### Other Utilities

```bash
# Check for dependency updates
./gradlew dependencyUpdates

# List all tasks
./gradlew tasks

# View dependency tree
./gradlew dependencies
```

---

## Testing

### Testing Patterns

#### Repository Tests

```kotlin
@DataJpaTest
@Testcontainers
class FSFileRepositoryTest {

    @Autowired
    lateinit var repository: FSFileRepository

    @Test
    fun `should find file by URI`() {
        val file = FSFile().apply { uri = "/test/file.txt" }
        repository.save(file)

        val found = repository.findByUri("/test/file.txt")

        assertThat(found).isNotNull
        assertThat(found?.uri).isEqualTo("/test/file.txt")
    }
}
```

#### Service Tests

```kotlin
@ExtendWith(MockitoExtension::class)
class FSFileServiceImplTest {

    @Mock
    lateinit var repository: FSFileRepository

    @Mock
    lateinit var mapper: FSFileMapper

    @InjectMocks
    lateinit var service: FSFileServiceImpl

    @Test
    fun `should throw NotFoundException when file not found`() {
        whenever(repository.findById(1L)).thenReturn(Optional.empty())

        assertThrows<NotFoundException> {
            service.findById(1L)
        }
    }
}
```

#### Controller Tests

```kotlin
@WebMvcTest(FSFileController::class)
class FSFileControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockBean
    lateinit var service: FSFileService

    @Test
    fun `should return file list page`() {
        whenever(service.findAll()).thenReturn(listOf())

        mockMvc.perform(get("/files"))
            .andExpect(status().isOk)
            .andExpect(view().name("files/list"))
    }
}
```

#### Integration Tests

```kotlin
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class FSFileIntegrationTest {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Test
    fun `should create and retrieve file via REST API`() {
        val dto = FSFileDTO(uri = "/test/file.txt")

        val created = restTemplate.postForEntity(
            "/api/files", dto, FSFileDTO::class.java
        )

        assertThat(created.statusCode).isEqualTo(HttpStatus.CREATED)

        val retrieved = restTemplate.getForEntity(
            "/api/files/${created.body?.id}", FSFileDTO::class.java
        )

        assertThat(retrieved.body?.uri).isEqualTo("/test/file.txt")
    }
}
```

### Testcontainers Configuration

**Reuse Enabled**: Containers persist between test runs for faster execution

```properties
# src/test/resources/testcontainers.properties
testcontainers.reuse.enable=true
```

**Stop manually if needed**:
```bash
docker stop $(docker ps -q --filter ancestor=postgres:18.0)
```

---

## Troubleshooting

### Application Startup Issues

**Issue**: Port 8089 already in use
**Solution**:
```bash
# Find process using port
lsof -i :8089
# Kill process
kill -9 <PID>
# Or change port in application.yml
```

**Issue**: Cannot connect to PostgreSQL
**Solution**:
```bash
# Check if container is running
docker ps | grep postgres
# Start if not running
docker compose up -d
# Check logs
docker compose logs postgres
```

**Issue**: Spring Batch job fails with "Job already complete"
**Solution**:
```bash
# Connect to database and clear batch metadata
psql -h localhost -p 5433 -U postgres -d spring_search_tempo
DELETE FROM batch_job_execution;
DELETE FROM batch_job_instance;
```

**Issue**: Hibernate schema validation errors
**Solution**:
```yaml
# In application.yml, temporarily set:
spring:
  jpa:
    hibernate:
      ddl-auto: update  # or 'create' to regenerate
```

### Test Failures

**Issue**: Tests fail with "port already in use"
**Solution**:
```bash
# Stop Testcontainers that weren't cleaned up
docker stop $(docker ps -q --filter ancestor=postgres:18.0)
```

**Issue**: ModularityTest fails with cycle detection
**Solution**: Check for circular dependencies between modules. Use generated docs at `build/spring-modulith-docs/` to visualize dependencies.

**Issue**: Integration tests time out
**Solution**: Increase timeout or check if Docker has enough resources
```kotlin
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class MyTest {
    companion object {
        @Container
        val postgres = PostgreSQLContainer<Nothing>("postgres:18.0").apply {
            withStartupTimeout(Duration.ofMinutes(2))
        }
    }
}
```

### Build Issues

**Issue**: Kapt annotation processing fails
**Solution**:
```bash
# Clean kapt generated files
./gradlew clean
rm -rf build/generated/source/kapt
./gradlew build
```

**Issue**: Out of memory during build
**Solution**:
```bash
# Increase Gradle memory
export GRADLE_OPTS="-Xmx2048m -XX:MaxMetaspaceSize=512m"
./gradlew build
```

### Runtime Issues

**Issue**: Authentication fails with correct credentials
**Solution**: Check password encoding. Default uses BCrypt. Ensure test data uses encoded passwords:
```kotlin
val encoded = passwordEncoder.encode("password")
```

**Issue**: CSRF token errors on POST/PUT/DELETE
**Solution**: CSRF is disabled for `/api/**`. If adding new protected endpoints, update `SecurityConfig.kt` or include CSRF token in requests.

**Issue**: LazyInitializationException in service layer
**Solution**: Add `@Transactional` to service methods or fetch with `JOIN FETCH`:
```kotlin
@Query("SELECT f FROM FSFile f JOIN FETCH f.contentChunks WHERE f.id = :id")
fun findByIdWithChunks(id: Long): FSFile?
```

---

## Anti-Patterns & Gotchas

### ❌ DON'T: Access Repositories Across Modules

```kotlin
// BAD: web module accessing base module repository directly
@Controller
class WebController(
    private val fsFileRepository: FSFileRepository  // Violates modularity
) {
    // ...
}
```

**✅ DO: Use service layer or events**

```kotlin
// GOOD: web module uses base module service
@Controller
class WebController(
    private val fsFileService: FSFileService  // Clean module boundary
) {
    // ...
}
```

### ❌ DON'T: Create Circular Entity References Without Careful Mapping

```kotlin
// BAD: Can cause infinite recursion in JSON serialization
@Entity
class Parent {
    @OneToMany(mappedBy = "parent")
    var children: Set<Child> = setOf()
}

@Entity
class Child {
    @ManyToOne
    var parent: Parent? = null  // Circular reference
}
```

**✅ DO: Use DTOs with controlled relationships**

```kotlin
// GOOD: DTO breaks circular reference
data class ParentDTO(
    val id: Long,
    val name: String,
    val childIds: List<Long>  // IDs only, not full objects
)
```

### ❌ DON'T: Use `ddl-auto: create-drop` in Production

```yaml
# BAD: Will delete all data on restart
spring:
  jpa:
    hibernate:
      ddl-auto: create-drop
```

**✅ DO: Use migrations or validate**

```yaml
# GOOD: For production
spring:
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
```

### ❌ DON'T: Ignore Transaction Boundaries

```kotlin
// BAD: Lazy loading outside transaction
fun getFile(id: Long): FSFile {
    return repository.findById(id).get()
}

// Later, outside transaction:
val file = getFile(1)
file.contentChunks.size  // LazyInitializationException!
```

**✅ DO: Fetch within transaction or use eager loading**

```kotlin
// GOOD: Fetch within transaction
@Transactional(readOnly = true)
fun getFileWithChunks(id: Long): FSFileDTO {
    val file = repository.findByIdWithChunks(id)
    return mapper.toDTO(file)  // DTO breaks entity lifecycle
}
```

### ❌ DON'T: Store Sensitive Data Unencrypted

```kotlin
// BAD: Storing plaintext passwords
val user = SpringUser().apply {
    password = "mypassword123"  // Never do this!
}
```

**✅ DO: Encrypt sensitive data**

```kotlin
// GOOD: Hash passwords before storage
val user = SpringUser().apply {
    password = passwordEncoder.encode("mypassword123")
}
```

### ❌ DON'T: Hard-Code Configuration Values

```kotlin
// BAD: Hard-coded values
class FileService {
    fun process() {
        val maxSize = 10485760  // What is this? Where did it come from?
    }
}
```

**✅ DO: Use configuration properties**

```kotlin
// GOOD: Externalized configuration
@ConfigurationProperties(prefix = "crawl")
data class CrawlProperties(
    val maxFileSize: Long = 10485760,  // 10MB default
    val batchSize: Int = 100
)

@Service
class FileService(private val config: CrawlProperties) {
    fun process() {
        val maxSize = config.maxFileSize
    }
}
```

### ⚠️ GOTCHA: Testcontainers Reuse

**Issue**: Containers persist between test runs. Schema changes may not be reflected.

**Solution**: Manually stop containers when schema changes:
```bash
docker stop $(docker ps -q --filter ancestor=postgres:18.0)
```

### ⚠️ GOTCHA: MapStruct and Kotlin Data Classes

**Issue**: MapStruct may not correctly map nullable fields in Kotlin data classes.

**Solution**: Use explicit mapping or ensure nullability matches:
```kotlin
@Mapper
interface MyMapper {
    @Mapping(target = "id", ignore = true)  // Explicit control
    fun toEntity(dto: MyDTO): MyEntity
}
```

### ⚠️ GOTCHA: Spring Batch Job Names

**Issue**: Spring Batch prevents re-running completed jobs with same name and parameters.

**Solution**:
- Use unique job parameters (e.g., timestamp)
- Clear batch metadata tables between runs
- Use `JobParametersBuilder` with `addLong("timestamp", System.currentTimeMillis())`

---

## Modularity Guidelines (Spring Modulith)

### Module Definition

**Explicit Modules**: Use `@ApplicationModule` in package-info.java

```java
@org.springframework.modulith.ApplicationModule(
    displayName = "File System Crawler",
    allowedDependencies = {"base", "batch"}
)
package com.oconeco.springsearchtempo.crawler;
```

### Module Communication

**✅ Preferred**: Application Events

```kotlin
// Publisher (in any module)
@Service
class MyService(private val publisher: ApplicationEventPublisher) {
    fun doSomething() {
        // Business logic
        publisher.publishEvent(SomethingHappenedEvent(...))
    }
}

// Listener (in different module)
@Component
class MyListener {
    @EventListener
    fun onSomethingHappened(event: SomethingHappenedEvent) {
        // React to event
    }
}
```

**✅ Acceptable**: Public Service Interfaces

```kotlin
// Module A exposes service
interface PublicService {
    fun doSomething(): Result
}

// Module B uses service
@Component
class MyComponent(private val publicService: PublicService)
```

**❌ Forbidden**: Direct Repository Access Across Modules

### Module Verification

**Run modularity tests regularly**:

```bash
./gradlew test --tests ModularityTest
```

**Generated Documentation**: `build/spring-modulith-docs/index.html`

**What's Verified**:
- No cyclic dependencies between modules
- Allowed dependencies are respected
- Package structure follows conventions
- Module boundaries are clean

### Module Structure Conventions

```
src/main/kotlin/com/oconeco/springsearchtempo/
├── base/                    # Core domain module
│   ├── domain/             # Entities (not accessible from outside)
│   ├── repos/              # Repositories (not accessible from outside)
│   ├── service/            # Services (public API)
│   └── package-info.java   # Module definition
├── batch/                   # Batch processing module
├── web/                     # Web UI module
└── SpringSearchTempoApplication.kt
```

**Visibility Rules**:
- Only packages explicitly exposed are accessible
- By convention, `internal` packages are not accessible
- Service interfaces define module boundaries

---

## Design Decisions

### Why Kotlin Over Java?
- Concise syntax reduces boilerplate
- Null safety prevents NPEs at compile time
- Data classes simplify DTOs
- Extension functions enable cleaner code
- Excellent Spring Boot integration

### Why Spring Modulith?
- Enforces modular architecture in monolith
- Enables future microservices extraction
- Verifiable module boundaries
- Event-driven communication patterns
- Documentation generation

### Why PostgreSQL Over Other Databases?
- Robust full-text search (FTS) capabilities
- JSONB support for flexible metadata
- pgvector extension for semantic search (Phase 3)
- ACID compliance for data integrity
- Wide Spring Boot support

### Why Testcontainers Over H2/Embedded?
- Tests against real PostgreSQL (no behavior differences)
- Schema validation matches production
- PostgreSQL-specific features work correctly
- Container reuse keeps tests fast

### Why MapStruct Over Manual Mapping?
- Compile-time verification (type-safe)
- Performance (no reflection)
- Maintainability (changes detected at compile time)
- Integration with Spring via `componentModel = "spring"`

### Why HTMX Over Full SPA Framework?
- Simpler mental model (server-rendered)
- Less JavaScript complexity
- Progressive enhancement
- Faster initial development
- Suitable for CRUD-heavy applications

### Why Basic Auth (Currently)?
- Simple setup for MVP
- Sufficient for single-user local deployment
- Placeholder for future OAuth2/OIDC integration

### Why Port 8089 Instead of 8080?
- Avoids conflicts with other common Spring Boot apps
- Allows running multiple Spring apps simultaneously
- Consistent with project convention

---

## Additional Resources

### Spring Modulith Documentation
- Official: https://docs.spring.io/spring-modulith/reference/
- Modules: https://docs.spring.io/spring-modulith/reference/fundamentals.html

### Technology Documentation
- Spring Boot 3.5: https://docs.spring.io/spring-boot/3.5.x/reference/
- Kotlin for Spring: https://spring.io/guides/tutorials/spring-boot-kotlin
- Spring Batch: https://docs.spring.io/spring-batch/reference/
- MapStruct: https://mapstruct.org/documentation/stable/reference/html/
- HTMX: https://htmx.org/docs/
- Testcontainers: https://testcontainers.com/guides/testing-spring-boot-rest-api-using-testcontainers/

### Project-Specific Documentation
- Modularity verification: `build/spring-modulith-docs/` (after running tests)
- API documentation: http://localhost:8089/swagger-ui.html (if enabled)
- Actuator endpoints: http://localhost:8089/actuator

---

## Developer Notes

**Project Lead**: Sean (learning Spring Modulith)

**Key Focus Areas**:
1. Maintaining clean module boundaries
2. Implementing flexible crawl configuration
3. Optimizing incremental crawl performance
4. Preparing for NLP integration (Phase 2)

**Active Development**:
- Crawl configuration system design and implementation
- Pattern matching for file processing levels
- Incremental crawl using timestamp comparison

**Future Considerations**:
- Migration to OAuth2 for authentication
- Flyway/Liquibase for schema management
- Kubernetes deployment configuration
- Multi-tenant support for enterprise use

---

**Last Updated**: 2025-11-06
**Version**: 0.0.1-SNAPSHOT
**Spring Boot**: 3.5.7
**Kotlin**: 1.9.25
