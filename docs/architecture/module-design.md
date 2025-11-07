# Spring Modulith Module Design

This document describes the modular architecture of Spring Search Tempo using Spring Modulith.

## Overview

Spring Modulith helps enforce modular boundaries in a monolithic application. Each module has:
- **Clear responsibilities**
- **Defined API boundaries**
- **Event-driven communication**
- **Verifiable dependencies**

## Module Structure

```
src/main/kotlin/com/oconeco/spring_search_tempo/
├── base/                    # Core domain module
│   ├── domain/             # Entities (internal)
│   ├── repos/              # Repositories (internal)
│   ├── service/            # Services (public API)
│   ├── model/              # DTOs (public API)
│   ├── config/             # Configuration (public)
│   ├── rest/               # REST resources (public)
│   ├── controller/         # Web controllers
│   └── package-info.java   # Module definition
├── batch/                   # Batch processing module
│   ├── fscrawl/            # File system crawling jobs
│   └── package-info.java
├── web/                     # Web UI module
│   ├── controller/         # Public controllers
│   ├── rest/               # Public REST endpoints
│   └── package-info.java
└── SpringSearchTempoApplication.kt
```

## Module Definitions

### Base Module

**Location**: `com.oconeco.spring_search_tempo.base`

**Purpose**: Core business logic and domain model

**Exposed Interfaces** (defined in package-info.java):
```java
@org.springframework.modulith.ApplicationModule(
    displayName = "Base Domain"
)
@org.springframework.modulith.NamedInterface(name = "config")
@org.springframework.modulith.NamedInterface(name = "service")
@org.springframework.modulith.NamedInterface(name = "model")
@org.springframework.modulith.NamedInterface(name = "repos")
@org.springframework.modulith.NamedInterface(name = "rest")
package com.oconeco.spring_search_tempo.base;
```

**Public API**:
- `service.*` - Service interfaces (FSFileService, FSFolderService, etc.)
- `model.*` - DTOs for data transfer
- `config.*` - Configuration classes
- `rest.*` - REST resources
- `repos.*` - Repositories (accessible for specific use cases)

**Internal** (not accessible from other modules):
- `domain.*` - JPA entities
- `controller.*` - Web controllers (accessed via HTTP, not code)
- `util.*` - Internal utilities

### Batch Module

**Location**: `com.oconeco.spring_search_tempo.batch`

**Purpose**: Spring Batch job processing

**Exposed Interfaces**:
```java
@org.springframework.modulith.ApplicationModule(
    displayName = "Batch Processing",
    allowedDependencies = {"base::service", "base::model", "base::config"}
)
package com.oconeco.spring_search_tempo.batch;
```

**Dependencies**:
- Can access `base` module's service, model, and config packages
- **Cannot** directly access `base` domain or repos

**Public API**:
- Job configurations
- Step definitions
- Custom readers/processors/writers

### Web Module

**Location**: `com.oconeco.spring_search_tempo.web`

**Purpose**: Public-facing web layer

**Exposed Interfaces**:
```java
@org.springframework.modulith.ApplicationModule(
    displayName = "Web Layer",
    allowedDependencies = {"base::service", "base::model"}
)
package com.oconeco.spring_search_tempo.web;
```

**Dependencies**:
- Can access `base` module's service and model packages
- **Cannot** directly access `base` domain or repos

**Public API**:
- Public controllers (HomeController)
- Public REST endpoints (HomeResource)

## Communication Patterns

### ✅ Preferred: Application Events

Use events for cross-module communication and loose coupling:

```kotlin
// Publishing in base module
@Service
class FSFileServiceImpl(
    private val publisher: ApplicationEventPublisher
) : FSFileService {

    fun deleteFile(id: Long) {
        // Business logic
        repository.deleteById(id)

        // Publish event
        publisher.publishEvent(FSFileDeletedEvent(id, fileUri))
    }
}

// Listening in batch module
@Component
class BatchJobCleaner {

    @EventListener
    fun onFileDeleted(event: FSFileDeletedEvent) {
        // Clean up batch metadata related to this file
        cleanupBatchMetadata(event.fileId)
    }
}
```

**Benefits**:
- Loose coupling between modules
- Publisher doesn't know about listeners
- Easy to add new listeners without modifying publisher
- Asynchronous processing possible with `@Async`

### ✅ Acceptable: Public Service Interfaces

Access services through well-defined interfaces:

```kotlin
// Batch module accessing base module service
@Configuration
class FsCrawlJobBuilder(
    private val fileService: FSFileService,  // ✅ Service interface
    private val folderService: FSFolderService
) {
    // Use services to interact with domain
}
```

**Benefits**:
- Clear API boundaries
- Encapsulation of business logic
- Type-safe interaction
- Testable with mocks

### ❌ Forbidden: Direct Repository Access

**DON'T** access repositories from other modules:

```kotlin
// BAD: Web module directly accessing repository
@Controller
class WebController(
    private val fsFileRepository: FSFileRepository  // ❌ Violates modularity
) {
    // This breaks module boundaries!
}

// GOOD: Web module using service
@Controller
class WebController(
    private val fsFileService: FSFileService  // ✅ Clean boundary
) {
    // This respects module boundaries
}
```

### ❌ Forbidden: Direct Entity Access

**DON'T** pass entities across module boundaries:

```kotlin
// BAD: Exposing entity
interface FSFileService {
    fun findById(id: Long): FSFile  // ❌ Exposes entity
}

// GOOD: Using DTO
interface FSFileService {
    fun findById(id: Long): FSFileDTO  // ✅ Uses DTO
}
```

## Event Design Patterns

### Domain Events

Events representing things that happened in the domain:

```kotlin
// Event definition
data class FSFileIndexedEvent(
    val fileId: Long,
    val uri: String,
    val bodySize: Long,
    val timestamp: Instant = Instant.now()
)

// Publishing
@Service
class FSFileServiceImpl(
    private val publisher: ApplicationEventPublisher
) : FSFileService {

    @Transactional
    fun indexFile(id: Long) {
        // Index the file
        val file = repository.findById(id).orElseThrow()
        file.bodyText = extractText(file)
        repository.save(file)

        // Publish after successful transaction
        publisher.publishEvent(
            FSFileIndexedEvent(file.id!!, file.uri, file.bodySize ?: 0)
        )
    }
}

// Listening
@Component
class SearchIndexUpdater {

    @EventListener
    @Transactional
    fun onFileIndexed(event: FSFileIndexedEvent) {
        updateSearchIndex(event.fileId)
    }
}
```

### Async Event Processing

For long-running operations:

```kotlin
@Component
class NotificationService {

    @Async
    @EventListener
    fun onFileIndexed(event: FSFileIndexedEvent) {
        // Long-running notification
        sendEmailNotification(event)
    }
}

// Enable async in configuration
@Configuration
@EnableAsync
class AsyncConfig {
    @Bean
    fun taskExecutor(): Executor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 2
        executor.maxPoolSize = 5
        executor.queueCapacity = 100
        executor.setThreadNamePrefix("async-")
        executor.initialize()
        return executor
    }
}
```

### Transactional Event Listeners

Ensure events are only published after successful commit:

```kotlin
@Component
class AuditLogger {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onFileCreated(event: FSFileCreatedEvent) {
        // Only called after transaction commits successfully
        logAuditEvent("File created: ${event.fileId}")
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
    fun onTransactionFailed(event: FSFileCreatedEvent) {
        // Called if transaction rolls back
        logError("File creation failed")
    }
}
```

## Module Verification

### Running Modularity Tests

Verify module boundaries with:

```bash
./gradlew test --tests ModularityTest
```

This test:
- ✅ Detects cyclic dependencies
- ✅ Verifies allowed dependencies
- ✅ Checks package structure
- ✅ Generates documentation

### Generated Documentation

After running tests, view module documentation:

```bash
open build/spring-modulith-docs/index.html
```

Documentation includes:
- Module dependency graph
- Component relationships
- Event relationships
- API surface area

## Dependency Rules

### Allowed Dependencies

```
web    → base (service, model)
batch  → base (service, model, config)
base   → (no module dependencies)
```

### Forbidden Dependencies

```
web    ✗→ batch
batch  ✗→ web
web    ✗→ base (domain, repos)
batch  ✗→ base (domain, repos)
```

### Dependency Configuration

Define allowed dependencies in `package-info.java`:

```java
@org.springframework.modulith.ApplicationModule(
    displayName = "Batch Processing",
    allowedDependencies = {"base::service", "base::model", "base::config"}
)
package com.oconeco.spring_search_tempo.batch;
```

## Best Practices

### 1. Define Clear Module Boundaries

**DO**:
- Expose services through interfaces
- Use DTOs for data transfer
- Publish events for cross-cutting concerns
- Keep domain model internal

**DON'T**:
- Expose entities directly
- Allow direct repository access from other modules
- Create circular dependencies
- Bypass service layer

### 2. Use Events for Loose Coupling

**When to use events**:
- Multiple modules need to react to same action
- Action is cross-cutting (logging, notifications, etc.)
- Decoupling is important for future changes
- Async processing is beneficial

**When to use service calls**:
- Direct dependency is appropriate
- Synchronous response needed
- Single consumer
- Strong typing required

### 3. Keep Modules Cohesive

Each module should have:
- Single, clear responsibility
- High cohesion within module
- Loose coupling between modules
- Well-defined API surface

### 4. Document Module APIs

Use `package-info.java` to document:
- Module purpose
- Public API
- Dependencies
- Usage examples

### 5. Test Module Boundaries

Add tests to verify:
- Dependency rules are followed
- No cyclic dependencies exist
- Public API is stable
- Events are published correctly

## Troubleshooting

### Cyclic Dependency Detected

**Error**: "Module cycle detected between base and batch"

**Solution**: Use events to break the cycle:
```kotlin
// Instead of batch → base → batch
// Use: batch → base (service) + base → event → batch (listener)
```

### Cannot Access Package

**Error**: "Cannot access com.oconeco.spring_search_tempo.base.domain"

**Solution**: Access through service layer:
```kotlin
// Instead of: private val repository: FSFileRepository
// Use: private val service: FSFileService
```

### Event Not Received

**Problem**: Event published but listener not called

**Solutions**:
1. Ensure listener component is scanned: `@Component`
2. Check transaction boundaries
3. Use `@TransactionalEventListener` if needed
4. Verify event type matches exactly

## Migration Guide

### Extracting to Microservices

Spring Modulith prepares for future microservices:

1. **Module becomes service**: Each module can become independent service
2. **Events become messages**: Replace `@EventListener` with message queues
3. **DTOs remain stable**: DTO contracts become API contracts
4. **Services become clients**: Replace service injection with HTTP/gRPC clients

Example migration path:
```
Modular Monolith           Microservices
-----------------          --------------
base module        →       Core Service (REST API)
batch module       →       Batch Service (calls Core API)
@EventListener     →       Kafka/RabbitMQ consumer
FSFileService      →       FSFileClient (Feign/WebClient)
```

## See Also

- [Adding Entities](../guides/adding-entities.md)
- [Batch Jobs](../guides/batch-jobs.md)
- [Spring Modulith Documentation](https://docs.spring.io/spring-modulith/reference/)
- [Module Verification Test](../../src/test/kotlin/com/oconeco/spring_search_tempo/ModularityTest.kt)
