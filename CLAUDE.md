# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.


## Project Overview

This app combines content management, text processing, and batch processing capabilities.

Spring Search Tempo is a Spring Boot 3.5 application written in Kotlin that aims to provide a starting template for a full-text search engine. The 'example' use case is a local file system crawl, but the app is designed to be extensible to other data sources and potentially even enterprise and e-commerce search foundation.

The design assumes one more 'crawls' (or 'scans') per day. We will provide 'opinionated' starter configurations with 'focused' crawls based on typical content locations. For example:
* USER (replace with current user, or explicit user name)
  * ~/Documents/**
  * ~/Pictures/**
* WORK
  * /opt/work/**
* CONFIG
  * /etc/**
* SYSTEM
  * /usr/**
  * /var/**
  * /lib/**
  * ...
* ...

Each of these 'top' bullets will be a 'crawl' with a different 'profile' (pattern matching, etc). 

We also assume significant performance improvement in 'subsequent' crawls if we load the 'index' of previous crawls. This will be as fast and light as possible, assuming we can use the time stamps on the cached/saved record to check for content changes since the last crawl. 


It uses Spring Modulith for modular architecture and includes a file system abstraction with text chunking and analysis features. The developer (Sean) is not familiar with Spring Modulith, but is open to learning. Highlight any modulith-relevant code.


### Phase 1
By default, the the application will crawl "personal" content like local/LAN filesystems in Phase 1. 
For simplicity sake, we will use postgres as the main (and only) persistence store. 
The app has stepped levels of processing, from ignore to (heavy) analysis:  
* **ignore** (assume these are non-relevant, not-searchable), e.g.
  * folders: (/tmp|/run|/root|/proc|/sys|/dev|/mnt|/media|/lost+found|.*\(.gradle||build|obj|out)|.*[/\](node_modules|vendor|packages))
  * files: (.*\.(lck|tmp|~|bak|bk1|bk2)|(deleteme.*|ignoreme.*))
* **locate** (assume these are searchable, but not processed), e.g.
  * OS files like (/usr/.*|/boot/.*), save metadata like linux `plocate`, but no content extraction
* **index** (basic tika-like content extraction to text blob, no advanced processing)
* **analyze** (advanced processing, e.g. NLP, chunking, vectorization, etc.) [placeholder in phase 1, todo: implement in phase 2]
* **semantic** (semantic search, e.g. embeddings, etc.) [placeholder in phase 1 and 2, todo: implement in phase 3]

We will configure postgres full text search (FTS) to enable fast text search capabilities. The crawl/processing configuration will come from application profile, but also overridable by environment variables or command line arguments.

## Phase 2
* **analyze** (advanced processing, e.g. NLP, chunking, vectorization, etc.) [implement in phase 2]
  * use stanford coreNLP, default annotations (see https://stanfordnlp.github.io/CoreNLP/annotators.html):
    * Tokenization
    * Sentence splitting (default to saving as 'contentChunks' with chunkType 'Sentence'
    * Part of speech tagging 
    * Dependency parsing
    * Named entity recognition
* Browser information (start with Firefox, extend to other browsers in phase 3)
  * history
  * bookmarks/tags 

## Phase 3
* **semantic** (semantic search, e.g. embeddings, etc.) [implement in phase 3]
  * implement a 'naive' paragraph splitter with the assumption that well-written pages should have distinct "thoughts" in paragraphs. 
    * we can either convert html to text, and look for blank text-lines as separators, or preferably, use a parser to look for html tags that define (or strongly suggest) paragraphs. 
  * by default vector embed: the entire page, 
  * along with 'useful' contentChunks 
    * (paragraphs if they exist)
    * sentences
    * phrase chunks (if they exist)
  * 

  * 

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
