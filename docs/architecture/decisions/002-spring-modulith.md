# ADR 002: Use Spring Modulith for Modular Architecture

## Status

Accepted

## Context

We needed an architecture pattern for Spring Search Tempo that:
- Keeps codebase organized as it grows
- Enables team collaboration with clear boundaries
- Supports future evolution (potentially to microservices)
- Enforces architectural constraints automatically
- Provides good developer experience

Options considered:
1. **Layered Architecture**: Traditional controller→service→repository layers
2. **Microservices**: Separate deployed services from the start
3. **Spring Modulith**: Modular monolith with verified boundaries
4. **Package-by-Feature**: Feature-based organization without enforcement

## Decision

Use Spring Modulith to structure the application as a modular monolith with enforced boundaries.

## Rationale

### Why Spring Modulith?

1. **Verified Module Boundaries**
   - Automatic verification of dependencies
   - Compile-time enforcement of architecture rules
   - Prevents accidental coupling
   - Generates dependency documentation

2. **Start Simple, Scale Incrementally**
   - Begin as single deployable unit (simple)
   - Define clear module boundaries (organized)
   - Can extract to microservices later (flexible)
   - Lower operational complexity than microservices

3. **Event-Driven Communication**
   - Built-in support for application events
   - Loose coupling between modules
   - Easy to make async
   - Natural transition to message queues later

4. **Developer Productivity**
   - Single deployment/debug cycle
   - Refactoring across modules is straightforward
   - Shared database (no distributed transaction complexity)
   - Fast feedback loop

5. **Future-Proof**
   - Modules map naturally to microservices
   - Event boundaries become message queue topics
   - Service interfaces become REST APIs
   - Migration path is clear

### Why Not Alternatives?

**Plain Layered Architecture**:
- ❌ No enforcement of boundaries
- ❌ Easy to create tangled dependencies
- ❌ Hard to extract functionality later
- ❌ No documentation of intended structure

**Microservices From Start**:
- ❌ High operational complexity
- ❌ Distributed transactions
- ❌ Network latency
- ❌ More expensive to develop/test
- ❌ Overkill for current requirements

**Package-by-Feature**:
- ❌ No automatic boundary enforcement
- ❌ Relies on discipline
- ❌ No documentation generation
- ❌ Dependencies can still tangle

## Architecture

### Module Structure

```
base module
├── Core domain (FSFile, FSFolder, User)
├── Text extraction (Apache Tika)
├── Security
└── Public API: services, DTOs

batch module
├── File crawling jobs
├── Chunking jobs
└── Depends on: base (services, DTOs)

web module
├── Public controllers
├── Public REST endpoints
└── Depends on: base (services, DTOs)
```

### Communication Rules

✅ **Allowed**:
- Module → Another Module's Public API (services, DTOs)
- Module → Event Publication
- Module → Event Listening

❌ **Forbidden**:
- Direct repository access across modules
- Entity sharing across modules
- Circular module dependencies
- Access to internal packages

### Example: Event-Driven Communication

```kotlin
// base module: Publishes event
@Service
class FSFileServiceImpl(
    private val publisher: ApplicationEventPublisher
) {
    fun indexFile(id: Long) {
        // Index file
        publisher.publishEvent(FSFileIndexedEvent(id))
    }
}

// batch module: Listens to event
@Component
class ChunkingJobTrigger {
    @EventListener
    fun onFileIndexed(event: FSFileIndexedEvent) {
        triggerChunkingJob(event.fileId)
    }
}
```

## Consequences

### Positive

1. **Clear Structure**: Modules have explicit boundaries and responsibilities
2. **Enforced Rules**: ModularityTest fails if rules are violated
3. **Documentation**: Auto-generated module diagrams and dependency graphs
4. **Team Scalability**: Teams can own specific modules
5. **Migration Path**: Easy path to microservices if needed
6. **Testability**: Modules can be tested independently

### Negative

1. **Learning Curve**: Team needs to understand module concepts
2. **Initial Overhead**: Setting up module boundaries takes time
3. **Discipline Required**: Must follow event-driven patterns
4. **Testing Complexity**: Need to test module boundaries

### Neutral

1. **Module Refactoring**: Moving code between modules requires care
2. **Dependency Management**: Must explicitly define allowed dependencies
3. **Event Versioning**: Events become contracts between modules

## Implementation

### Module Definition

**package-info.java**:
```java
@org.springframework.modulith.ApplicationModule(
    displayName = "Batch Processing",
    allowedDependencies = {"base::service", "base::model"}
)
package com.oconeco.spring_search_tempo.batch;
```

### Verification Test

**ModularityTest.kt**:
```kotlin
@SpringBootTest
class ModularityTest {
    @Test
    fun `verify modular structure`() {
        val modules = ApplicationModules.of(SpringSearchTempoApplication::class.java)
        modules.verify()  // Fails if violations found
    }
}
```

### Documentation Generation

```bash
./gradlew test --tests ModularityTest
open build/spring-modulith-docs/index.html
```

## Migration Considerations

### Path to Microservices (if needed)

1. **Module → Service**: Each module becomes a separate service
2. **Events → Messages**: Replace `@EventListener` with message queues (Kafka/RabbitMQ)
3. **Services → REST APIs**: Service interfaces become HTTP endpoints
4. **Shared Database → Database per Service**: Split database if needed

Example:
```
Current (Modulith)           Future (Microservices)
------------------           ----------------------
base module          →       core-service (REST API)
batch module         →       batch-service (calls core-service API)
@EventListener       →       Kafka consumer
FSFileService        →       FSFileClient (Feign/WebClient)
Single PostgreSQL    →       core-db + batch-db (if needed)
```

## Alternatives Considered

### Alternative 1: Traditional Layered Architecture

**Pros**:
- Familiar pattern
- Simple to understand
- No special framework needed

**Cons**:
- No enforcement of boundaries
- Easy to create spaghetti code
- Hard to extract later
- No clear migration path

**Decision**: ❌ Rejected - doesn't provide enough structure for growth

### Alternative 2: Microservices from Start

**Pros**:
- Maximum modularity
- Independent deployment
- Technology flexibility per service

**Cons**:
- High operational overhead
- Distributed transactions
- Network latency
- Over-engineering for current needs
- Expensive to develop/test

**Decision**: ❌ Rejected - premature optimization

### Alternative 3: Hexagonal Architecture

**Pros**:
- Clear separation of concerns
- Port/adapter pattern
- Testable

**Cons**:
- More complex than needed
- Doesn't prevent module coupling
- No verification tooling
- Steeper learning curve

**Decision**: ❌ Rejected - too complex for requirements

## Success Criteria

1. ✅ ModularityTest passes on every build
2. ✅ Module documentation auto-generates
3. ✅ No circular dependencies between modules
4. ✅ Events used for cross-module communication
5. ✅ Service interfaces define module boundaries

## References

- [Spring Modulith Documentation](https://docs.spring.io/spring-modulith/reference/)
- [Modular Monoliths](https://www.kamilgrzybek.com/blog/posts/modular-monolith-primer)
- [Simon Brown - Modular Monoliths](https://www.youtube.com/watch?v=5OjqD-ow8GE)

## Date

2025-11-06

## Reviewed

- Initial implementation: 2025-11-06
- Module boundaries verified: 2025-11-07
