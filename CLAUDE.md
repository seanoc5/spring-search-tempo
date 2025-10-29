# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Spring Search Tempo is a Spring Boot 3.5 application written in Kotlin that combines content management, text processing, and batch processing capabilities. It uses Spring Modulith for modular architecture and includes a file system abstraction with text chunking and analysis features.

## Technology Stack

- **Language**: Kotlin 1.9.25 with Java 21 target
- **Framework**: Spring Boot 3.5.7
- **Architecture**: Spring Modulith 1.4.3 for modular monolith design
- **Database**: PostgreSQL (via Docker Compose, port 5433)
- **Web UI**: Thymeleaf + Bootstrap 5.3.8 + HTMX 2.0.7
- **Batch Processing**: Spring Batch for data import jobs
- **Security**: Spring Security with basic authentication
- **Mapping**: MapStruct 1.6.3 with Kotlin kapt
- **Testing**: JUnit 5, Testcontainers, Spring Modulith Test

## Build and Run Commands

### Development
```bash
# Start the application with docker-compose (starts PostgreSQL)
./gradlew bootRun
# Runs with 'local' profile by default, accessible at http://localhost:8089

# For custom local config, create application-local.yml to override settings
```

### Testing
```bash
# Run all tests (uses Testcontainers with reuse enabled)
./gradlew test

# Run a specific test class
./gradlew test --tests ModularityTest

# Run a specific test method
./gradlew test --tests ModularityTest.verifyModuleStructure
```

### Build
```bash
# Clean and build the application
./gradlew clean build

# Build Docker image
./gradlew bootBuildImage --imageName=com.oconeco/spring-search-tempo

# Run the built jar with production profile
java -Dspring.profiles.active=production -jar ./build/libs/spring-search-tempo-0.0.1-SNAPSHOT.jar
```

### Other Commands
```bash
# View module documentation (after running ModularityTest)
# Generated at: build/spring-modulith-docs

# Start PostgreSQL manually if needed
docker compose up

# Stop Docker containers
docker compose down
```

## Architecture

### Module Structure (Spring Modulith)

The application follows a modular architecture verified by `ModularityTest`. Main modules:

- **base**: Core domain module containing the primary business logic
  - `domain/`: JPA entities (FSFile, FSFolder, ContentChunks, Annotation, SpringUser, SpringRole)
  - `repos/`: Spring Data JPA repositories
  - `service/`: Service implementations and MapStruct mappers
  - `controller/`: Thymeleaf/HTMX controllers for web UI
  - `rest/`: REST API resources with HATEOAS support
  - `config/`: Security, domain, and servlet configuration
  - `events/`: Domain events for entity lifecycle management
  - `model/`: DTOs and validation annotations
  - `util/`: Shared utilities and custom validators

- **batch**: Spring Batch job configurations
  - `batch/person/`: Example batch job for importing person data

- **web**: Public web layer
  - `controller/`: Public controllers (HomeController)
  - `rest/`: Public REST endpoints (HomeResource)

### Domain Model

**File System Abstraction**:
- `FSObject`: Base class for file system entities
- `FSFolder`: Represents folders in the virtual file system
- `FSFile`: Represents files with `bodyText` and relationships to ContentChunks

**Text Processing**:
- `ContentChunks`: Stores text segments with NLP metadata including:
  - Text chunking (startPosition, endPosition, chunkNumber)
  - NLP features (namedEntities, nouns, verbs, tokenAnnotations)
  - Parse trees (parseNpvp, parseUd, parseTree, conllu)
  - Vector embeddings and FTS vectors for search
  - Hierarchical relationships (parentChunk)

**User Management**:
- `SpringUser`: User entities with role-based access
- `SpringRole`: Role definitions
- Authentication via Spring Security with basic auth

**Annotations**:
- `Annotation`: Custom labeling/tagging system

### Configuration

**Database**: PostgreSQL running in Docker (localhost:5433)
- JPA with Hibernate DDL auto-update
- Session management via JDBC
- Spring Batch schema auto-initialization

**Security**:
- Basic authentication enabled
- CSRF disabled for `/home`, `/api/**`, `/actuator/**`
- `/springUsers` GET endpoint requires LOGIN authority
- Password encoding via BCrypt (delegating encoder)

**Batch Jobs**:
- `importUserJob` runs on startup
- Processes person data (transforms to uppercase, saves to database)

**Server**: Runs on port 8089 (not default 8080)

### Key Patterns

1. **Service Layer**: Service interfaces + ServiceImpl implementations with MapStruct mappers
2. **DTO Pattern**: Entities mapped to DTOs via MapStruct for API/controller layers
3. **HATEOAS**: REST resources use Spring HATEOAS with custom assemblers
4. **Validation**: Custom validators for uniqueness constraints (e.g., FSFileUriUnique, AnnotationLabelUnique)
5. **Event Handling**: BeforeDelete events for entity lifecycle hooks
6. **Error Handling**: Custom exceptions (NotFoundException, ReferencedException) with error-handling-spring-boot-starter

## Testing Notes

- **Testcontainers**: Integration tests use Testcontainers with reuse flag (container persists after tests)
- **ModularityTest**: Verifies module structure and generates documentation in `build/spring-modulith-docs`
- Stop containers manually if needed: `docker stop $(docker ps -q --filter ancestor=postgres:18.0)`

## Development Profile

For local development, use the `local` profile:
- In IntelliJ: Add `-Dspring.profiles.active=local` to VM options in Run Configuration
- Create `src/main/resources/application-local.yml` to override settings
