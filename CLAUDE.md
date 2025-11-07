# CLAUDE.md

Concise guidance for Claude Code when working with Spring Search Tempo.

---

## Project Overview

**Spring Search Tempo** is a Spring Boot 3.5 Kotlin application providing a full-text search engine template for local file system crawling, extensible to other data sources (browser history, emails).

**Target**: Developers building search applications, personal knowledge management, or enterprise content discovery.

**Tech Stack**: Spring Boot 3.5.7, Kotlin 1.9.25, PostgreSQL 18, Apache Tika 2.9.1, Spring Batch, Spring Modulith

---

## Current Status

**Active Phase**: Phase 1 - Core Foundation (90% complete)

**In Progress**:
- Crawl configuration format and loader
- Incremental crawl using timestamps
- PostgreSQL full-text search (FTS) setup

**Just Completed**: Apache Tika integration for text extraction from 400+ file formats

**Next Phase**: NLP integration (Stanford CoreNLP) for linguistic analysis

[Full Roadmap →](docs/roadmap.md)

---

## Quick Start

```bash
./gradlew bootRun          # Start app (http://localhost:8089)
./gradlew test             # Run all tests
./gradlew test --tests ModularityTest  # Verify module boundaries
docker compose up -d       # Start PostgreSQL manually if needed
```

[Complete Commands →](docs/reference/commands.md)

---

## Architecture

### Module Structure (Spring Modulith)

```
base/                      # Core domain & business logic
├── service/              # Public API (FSFileService, etc.)
├── model/                # DTOs
├── domain/               # JPA entities (internal)
├── repos/                # Repositories (internal)
└── config/               # Configuration

batch/                     # Spring Batch processing
└── fscrawl/              # File crawling jobs

web/                       # Public web layer
├── controller/           # Web UI
└── rest/                 # REST endpoints
```

**Key Rule**: Modules communicate via **services** and **events**, never direct repository access.

[Module Design Details →](docs/architecture/module-design.md)

### Domain Model (Core Entities)

```kotlin
@Entity
abstract class FSObject {
    @Id @GeneratedValue var id: Long?
    var uri: String
    var lastModified: Instant?
}

@Entity
class FSFile : FSObject() {
    var bodyText: String?           // Extracted text content
    var bodySize: Long?

    // Tika-extracted metadata
    var author: String?
    var title: String?
    var contentType: String?
    var pageCount: Int?
    // ... more metadata fields

    @OneToMany
    var contentChunks: MutableSet<ContentChunks>
}

@Entity
class FSFolder : FSObject() {
    @OneToMany
    var children: MutableSet<FSObject>
}

@Entity
class ContentChunks {
    var chunkType: String?          // "Sentence", "Paragraph"
    var chunkNumber: Int?
    var chunkText: String?

    // Future: NLP annotations
    var namedEntities: String?
    var parseTree: String?
    var ftsVector: String?

    @ManyToOne var fsFile: FSFile?
    @ManyToOne var parentChunk: ContentChunks?
}
```

### Text Extraction

**Service**: `TextExtractionService` (base module)

**Capabilities**:
- Extract text from 400+ formats (PDF, DOCX, XLSX, HTML, etc.)
- Extract metadata (author, title, dates, page count)
- Detect MIME types
- PostgreSQL-safe text sanitization

```kotlin
when (val result = textExtractionService.extractTextAndMetadata(path, maxSize)) {
    is TextAndMetadataResult.Success -> {
        file.bodyText = result.text
        file.author = result.metadata.author
        // ... populate other fields
    }
    is TextAndMetadataResult.Failure -> {
        file.bodyText = "[Extraction failed: ${result.error}]"
    }
}
```

[Text Extraction Details →](docs/architecture/decisions/003-apache-tika.md)

### Processing Levels

Files are processed based on pattern matching:

- **IGNORE**: Skip entirely (not stored)
- **LOCATE**: Store metadata only (path, size, timestamps)
- **INDEX**: Extract full text + metadata
- **ANALYZE**: INDEX + sentence-level chunking

Configured via `PatternMatchingService` and file patterns.

---

## Key Patterns

### Module Communication

✅ **Use Events**:
```kotlin
// Publisher
@Service
class FSFileServiceImpl(
    private val publisher: ApplicationEventPublisher
) {
    fun indexFile(id: Long) {
        // Business logic
        publisher.publishEvent(FSFileIndexedEvent(id, uri))
    }
}

// Listener (different module)
@Component
class ChunkingTrigger {
    @EventListener
    fun onFileIndexed(event: FSFileIndexedEvent) {
        triggerChunking(event.fileId)
    }
}
```

✅ **Use Services**:
```kotlin
class MyComponent(
    private val fileService: FSFileService  // ✅ Service interface
)
```

❌ **Don't Access Repos Directly**:
```kotlin
class MyComponent(
    private val fileRepository: FSFileRepository  // ❌ Breaks modularity
)
```

### Adding Entities

1. Create entity extending `FSObject` or standalone
2. Add repository (JpaRepository)
3. Create DTO + MapStruct mapper
4. Create service interface + implementation
5. Optionally add REST resource or controller

[Complete Guide →](docs/guides/adding-entities.md)

### Creating Batch Jobs

1. Create Job configuration with JobBuilder
2. Define Step(s) with chunk size, reader, processor, writer
3. Register job name in application.yml if auto-start

[Complete Guide →](docs/guides/batch-jobs.md)

---

## Common Tasks

| Task | Quick Reference | Full Guide |
|------|----------------|------------|
| Add entity | Entity → Repo → DTO → Mapper → Service | [Guide](docs/guides/adding-entities.md) |
| Add batch job | Config → Steps → Reader/Processor/Writer | [Guide](docs/guides/batch-jobs.md) |
| Custom validation | Annotation → Validator → Apply to DTO | [Guide](docs/guides/validation.md) |
| Run specific test | `./gradlew test --tests TestName` | [Commands](docs/reference/commands.md) |
| Verify modules | `./gradlew test --tests ModularityTest` | [Module Design](docs/architecture/module-design.md) |
| Troubleshoot | Check logs, database, container status | [Troubleshooting](docs/reference/troubleshooting.md) |

---

## Configuration

### Database (PostgreSQL via Docker Compose)

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5433/spring_search_tempo  # Port 5433!
    username: postgres
    password: postgres
  jpa:
    hibernate:
      ddl-auto: update  # Use 'validate' in production
```

**Connect**: `psql -h localhost -p 5433 -U postgres -d spring_search_tempo`

### Batch Jobs

```yaml
spring:
  batch:
    job:
      enabled: true               # Run on startup
      name: fsCrawlJob           # Specific job to run
```

**Disable**: Set `enabled: false`

### Security

Default credentials: `user` / `password` (basic auth)

**Configure**: See `SecurityConfig.kt` and `application.yml`

---

## Anti-Patterns

❌ **Don't**:
- Access repositories across module boundaries (use services)
- Expose entities in REST APIs (use DTOs)
- Use `ddl-auto: create-drop` in production
- Ignore transaction boundaries for lazy loading
- Commit files with secrets (.env, credentials.json)

✅ **Do**:
- Use event-driven communication between modules
- Work with DTOs at service boundaries
- Add `@Transactional` for lazy loading or use JOIN FETCH
- Use validated migrations (Flyway/Liquibase) in production
- Review staged changes before committing

---

## Testing

```bash
./gradlew test                          # All tests
./gradlew test --tests ModularityTest   # Module verification
./gradlew cleanTest test                # Force re-run
./gradlew test jacocoTestReport         # With coverage
```

**Testcontainers**: PostgreSQL containers reused between runs for speed. Stop manually if schema changes: `docker stop $(docker ps -q --filter ancestor=postgres:18.0)`

[Testing Guide →](docs/guides/testing.md)

---

## Troubleshooting Quick Fixes

| Issue | Quick Fix |
|-------|-----------|
| Port 8089 in use | `lsof -i :8089` → `kill -9 <PID>` |
| Can't connect DB | `docker compose up -d` |
| Job already complete | Clear batch tables or add timestamp parameter |
| Kapt errors | `./gradlew cleanKapt && rm -rf build/generated/source/kapt` |
| Test containers won't stop | `docker stop $(docker ps -q --filter ancestor=postgres:18.0)` |
| LazyInitializationException | Add `@Transactional` or use JOIN FETCH |

[Complete Troubleshooting →](docs/reference/troubleshooting.md)

---

## Technology Decisions

- **Kotlin**: Null safety, concise syntax, great Spring support ([ADR-001](docs/architecture/decisions/001-use-kotlin.md))
- **Spring Modulith**: Enforced boundaries, migration path to microservices ([ADR-002](docs/architecture/decisions/002-spring-modulith.md))
- **Apache Tika**: 400+ formats, unified API, production-ready ([ADR-003](docs/architecture/decisions/003-apache-tika.md))
- **PostgreSQL**: Full-text search, JSONB, pgvector ready
- **Testcontainers**: Real DB in tests, matches production

---

## Documentation

- **Guides**: [docs/guides/](docs/guides/) - Step-by-step tutorials
- **Architecture**: [docs/architecture/](docs/architecture/) - Design decisions and module structure
- **Reference**: [docs/reference/](docs/reference/) - Commands, config, troubleshooting
- **README**: [README.md](README.md) - Human-facing project overview

---

**Last Updated**: 2025-11-07
**Version**: 0.0.1-SNAPSHOT
**Project Lead**: Sean (learning Spring Modulith)
