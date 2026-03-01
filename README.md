# Spring Search Tempo

A **Spotlight-style full-system search engine** built with Spring Boot 3.5. Index your entire digital life - files, emails, bookmarks, and more - with powerful full-text search and NLP analysis.

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.25-blue.svg)](https://kotlinlang.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.7-green.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-18-blue.svg)](https://www.postgresql.org)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> **Etymology**: `tempo` is a portmanteau of `temporal` and `tempus` (Latin for "time"), combined with `template` - reflecting this project's focus on time-aware search and serving as a template for search applications.

## Vision

**Spring Search Tempo** aims to be your personal search engine - like macOS Spotlight or Windows Search, but open, extensible, and under your control. The goal is to index **all real content** on your system:

- **Files**: Documents, code, configs, logs - everything readable
- **Email**: IMAP accounts (Gmail, Outlook, etc.)
- **Browser**: Bookmarks and history (Firefox, Chrome)
- **Future**: Calendar, chat, notes, cloud storage

### Indexing Philosophy

| Level | What Gets Indexed | Examples |
|-------|-------------------|----------|
| **SKIP** | Nothing (virtual/volatile) | `/proc`, `/sys`, `/tmp`, caches, build artifacts |
| **LOCATE** | Metadata only | Images, videos, audio, archives, executables |
| **INDEX** | Full text + metadata | Documents, code, configs, emails, logs |
| **ANALYZE** | INDEX + NLP processing | Key documents needing entity extraction, sentiment |

**Initial crawl** will be heavy (hours for a full system), but **incremental updates** are fast - only processing files that changed since the last crawl.

## Features

- 🗂️ **Full System Crawling**: Index entire filesystems with intelligent processing levels
- 📄 **400+ Formats**: Extract text from PDF, DOCX, XLSX, HTML, code, configs, and more (Apache Tika)
- 📊 **Rich Metadata**: Author, title, dates, page count, POSIX attributes
- 🔍 **Full-Text Search**: PostgreSQL GIN indexes with ranking and highlighting
- 🧠 **NLP Analysis**: Named entities, sentiment, POS tagging (Stanford CoreNLP)
- ✂️ **Smart Chunking**: Sentence-level segmentation for precise search
- 📧 **Email Integration**: IMAP crawling for Gmail, Outlook, etc.
- 🔖 **Browser Integration**: Firefox bookmarks and history
- ⚡ **Incremental Updates**: Only re-process changed files
- 🔄 **Overlapping Crawl Detection**: Smart skipping when crawl configs overlap
- 🏗️ **Modular Architecture**: Spring Modulith with enforced boundaries
- 🚀 **Batch Processing**: Resilient, resumable Spring Batch jobs

## Quick Start

### Prerequisites

- JDK 21+
- Docker & Docker Compose
- Gradle 8+ (or use included wrapper)

### Running the Application

```bash
# Clone the repository
git clone https://github.com/seanoc5/spring-search-tempo.git
cd spring-search-tempo

# Start PostgreSQL (happens automatically with bootRun)
docker compose up -d

# Run the application
./gradlew bootRun

# Access at http://localhost:8089
# Default credentials: user / password
```

### Running Tests

```bash
# Run all tests
./gradlew test

# Run with coverage
./gradlew test jacocoTestReport

# Verify module boundaries
./gradlew test --tests ModularityTest
```

## Architecture

### Technology Stack

- **Language**: Kotlin 1.9.25
- **Framework**: Spring Boot 3.5.7
- **Database**: PostgreSQL 18
- **Text Extraction**: Apache Tika 2.9.1
- **Batch Processing**: Spring Batch
- **Module Architecture**: Spring Modulith
- **ORM**: JPA with Hibernate
- **DTO Mapping**: MapStruct
- **Testing**: JUnit 5, Testcontainers
- **Web UI**: Thymeleaf + HTMX

### Module Structure

```
src/main/kotlin/com/oconeco/spring_search_tempo/
├── base/           # Core domain (entities, services, repos)
├── batch/          # Batch processing jobs
└── web/            # Web UI and public endpoints
```

**Key Architectural Principles**:
- Modules communicate via events and service interfaces
- No direct repository access across modules
- DTOs for all external boundaries
- Verified module boundaries with Spring Modulith

[Read more about architecture →](docs/architecture/module-design.md)

## How It Works

### Initial Crawl vs Incremental Updates

| Aspect | Initial Crawl | Incremental Updates |
|--------|--------------|---------------------|
| **Scope** | Full system | Changed files only |
| **Duration** | Hours (full system) | Seconds to minutes |
| **Frequency** | Once per system | After each change |
| **Resource Usage** | High (background) | Low |

The system tracks `lastModified` timestamps and file sizes. On subsequent crawls, unchanged files are skipped entirely.

### Processing Levels

Files are processed at different levels based on configurable regex patterns:

| Level | Description | Default Targets |
|-------|-------------|-----------------|
| **SKIP** | Metadata only, no children | `.git/`, `node_modules/`, `/proc/`, caches |
| **LOCATE** | Store path, size, timestamps | Images, videos, audio, archives, binaries |
| **INDEX** | Extract full text + metadata | Documents, code, configs, emails, logs |
| **ANALYZE** | INDEX + NLP chunking | READMEs, important docs, research papers |

### Overlapping Crawl Configs

You can define multiple crawl configurations that overlap:
- `/home/sean/Documents` (specific, ANALYZE level)
- `/home/sean/` (general, INDEX level)

The system automatically detects when a parent crawl encounters a recently-crawled child config root and skips that subtree.

[Configuration guide →](docs/guides/crawling.md)

## Development

### Project Structure

```
spring-search-tempo/
├── src/
│   ├── main/
│   │   ├── kotlin/        # Kotlin source code
│   │   └── resources/     # Config, templates, static files
│   └── test/
│       ├── kotlin/        # Test code
│       └── resources/     # Test fixtures
├── docs/
│   ├── guides/           # Step-by-step tutorials
│   ├── architecture/     # Design docs and ADRs
│   └── reference/        # Commands, config, troubleshooting
├── build.gradle.kts      # Build configuration
├── docker-compose.yml    # PostgreSQL setup
├── CLAUDE.md            # AI assistant context
└── README.md            # This file
```

### Development Profile

During development, use the `local` profile. In IntelliJ IDEA, add `-Dspring.profiles.active=local` in the VM options of the Run Configuration. Create your own `application-local.yml` file to override settings for development.

### Common Tasks

**Add a new entity**:
```bash
# See docs/guides/adding-entities.md
1. Create entity extending FSObject
2. Add repository
3. Create DTO + mapper
4. Create service interface + implementation
5. Add REST resource or controller
```

**Create a batch job**:
```bash
# See docs/guides/batch-jobs.md
1. Create job configuration
2. Define reader, processor, writer
3. Register in application.yml
```

**Run specific tests**:
```bash
./gradlew test --tests "FSFileServiceTest"
./gradlew test --tests "ModularityTest"
```

[Complete developer guide →](docs/guides/)

## Configuration

### Database

```yaml
# application.yml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/tempo
    username: postgres
    password: postgres
```


### Security

```yaml
spring:
  security:
    user:
      name: user
      password: password
```

**Production**: Use environment variables or external config for credentials.

### Batch Jobs

```yaml
spring:
  batch:
    job:
      enabled: true
      name: fsCrawlJob
```

[Full configuration reference →](docs/reference/configuration.md)

### Windows Packaging (jpackage)

Build a native Windows installer with bundled Java:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\windows\build-jpackage.ps1 `
  -InstallerType msi `
  -DbHost minti9 `
  -DbPort 5432 `
  -DbName tempo `
  -DbUser tempo `
  -DbPassword "password"
```

[Windows jpackage deployment guide →](docs/guides/windows-jpackage-deployment.md)

## Roadmap

### ✅ Phase 1: Core Foundation (Complete)

- [x] Domain model and repositories
- [x] Text extraction with Apache Tika (400+ formats)
- [x] Rich metadata extraction
- [x] File system crawling with pattern matching
- [x] Incremental crawl (timestamp-based change detection)
- [x] PostgreSQL full-text search with GIN indexes
- [x] Sentence-level chunking
- [x] SKIP folder optimization (children not enumerated)
- [x] Overlapping crawl detection

### ✅ Phase 2: NLP & Multi-Source (80% Complete)

- [x] Stanford CoreNLP integration
- [x] Named entity recognition (NER)
- [x] Part-of-speech tagging with lemmatization
- [x] Sentiment analysis
- [x] NLP auto-trigger after crawl
- [x] Email crawling infrastructure (IMAP)
- [x] Firefox bookmark/history integration
- [ ] NLP results in search UI
- [ ] Email crawl orchestration

### 🔮 Phase 3: Semantic Search (Future)

- [ ] Vector embeddings (pgvector)
- [ ] Semantic similarity search
- [ ] Hybrid keyword + semantic search
- [ ] Document summarization
- [ ] Query understanding

### 🔮 Phase 4: Real-Time & Scale (Future)

- [ ] File system event watching (inotify/FSEvents)
- [ ] Real-time index updates
- [ ] Background/idle-time processing
- [ ] Index compression for older content
- [ ] Distributed crawling

## Documentation

- **[Guides](docs/guides/)**: Step-by-step tutorials for common tasks
  - [Adding Entities](docs/guides/adding-entities.md)
  - [Batch Jobs](docs/guides/batch-jobs.md)
  - [Custom Validation](docs/guides/validation.md)
- **[Architecture](docs/architecture/)**: Design decisions and module structure
  - [Module Design](docs/architecture/module-design.md)
  - [ADR-001: Kotlin](docs/architecture/decisions/001-use-kotlin.md)
  - [ADR-002: Spring Modulith](docs/architecture/decisions/002-spring-modulith.md)
  - [ADR-003: Apache Tika](docs/architecture/decisions/003-apache-tika.md)
- **[Reference](docs/reference/)**: Commands, configuration, troubleshooting
  - [Commands Reference](docs/reference/commands.md)
  - [Troubleshooting Guide](docs/reference/troubleshooting.md)
- **[CLAUDE.md](CLAUDE.md)**: Context for AI-assisted development

## Testing

### Test Types

```bash
# Run all tests
./gradlew test

# Run with coverage
./gradlew test jacocoTestReport
open build/reports/jacoco/test/html/index.html

# Verify module boundaries
./gradlew test --tests ModularityTest
```

### Testcontainers

Integration tests use [Testcontainers](https://testcontainers.com/) for PostgreSQL. Due to the reuse flag, containers persist between runs for faster execution. Stop manually if needed:

```bash
docker stop $(docker ps -q --filter ancestor=postgres:18.0)
```

The `ModularityTest` verifies the module structure and generates documentation in `build/spring-modulith-docs/`.

## Build

Build the application using:

```bash
./gradlew clean build
```

Run with a specific profile:

```bash
java -Dspring.profiles.active=production \
     -jar ./build/libs/spring-search-tempo-0.0.1-SNAPSHOT.jar
```

Build Docker image:

```bash
./gradlew bootBuildImage --imageName=com.oconeco/spring-search-tempo
```

## Contributing

Contributions welcome! Please:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

**Development Guidelines**:
- Follow Kotlin coding conventions
- Add tests for new features
- Run `./gradlew test --tests ModularityTest` to verify module boundaries
- Update documentation as needed

## Troubleshooting

**Port already in use**:
```bash
lsof -i :8089
kill -9 <PID>
```

**Can't connect to PostgreSQL**:
```bash
docker compose up -d
docker compose logs postgres
```

**Batch job already complete**:
```bash
# Clear batch metadata
psql -h localhost -p 5433 -U postgres -d spring_search_tempo
DELETE FROM batch_job_execution;
```

[Complete troubleshooting guide →](docs/reference/troubleshooting.md)

## Further Reading

- [Gradle User Manual](https://docs.gradle.org/)
- [Spring Boot Reference](https://docs.spring.io/spring-boot/docs/current/reference/htmlsingle/)
- [Spring Data JPA Reference](https://docs.spring.io/spring-data/jpa/reference/jpa.html)
- [Spring Modulith Documentation](https://docs.spring.io/spring-modulith/reference/)
- [Kotlin for Spring Developers](https://spring.io/guides/tutorials/spring-boot-kotlin)
- [Apache Tika Documentation](https://tika.apache.org/documentation.html)
- [Thymeleaf Documentation](https://www.thymeleaf.org/documentation.html)
- [HTMX in a Nutshell](https://htmx.org/docs/)

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- [Spring Boot](https://spring.io/projects/spring-boot) - Application framework
- [Spring Modulith](https://spring.io/projects/spring-modulith) - Modular architecture
- [Apache Tika](https://tika.apache.org/) - Text extraction
- [Kotlin](https://kotlinlang.org/) - Programming language
- [PostgreSQL](https://www.postgresql.org/) - Database
- [Testcontainers](https://testcontainers.com/) - Testing infrastructure
- [Bootify.io](https://bootify.io/) - Initial project generation

## Contact

**Project Lead**: Sean

**Repository**: [https://github.com/seanoc5/spring-search-tempo](https://github.com/seanoc5/spring-search-tempo)

**Issues**: [GitHub Issues](https://github.com/seanoc5/spring-search-tempo/issues)

---

Built with ❤️ using Kotlin and Spring Boot
