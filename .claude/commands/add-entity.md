---
description: Scaffold a new entity with full stack implementation
---

Create a new entity called **{{name}}** following the project patterns in CLAUDE.md.

**Requirements:**
- Extend: {{base_class}} (e.g., FSObject, or standalone)
- Module: {{module}} (e.g., base, batch, web)
- Inheritance: {{inheritance}} (e.g., JOINED, TABLE_PER_CLASS, or N/A)
- Fields: {{fields}} (e.g., "subject:String, sender:String, body:TEXT, receivedDate:Instant")

**Create the following:**
1. Entity class with proper annotations
2. JPA Repository interface with custom queries if needed
3. DTO (data class) and MapStruct mapper
4. Service interface and implementation
5. REST Resource with HATEOAS support
6. Model assembler for HATEOAS links
7. Basic tests (repository, service, integration)

**Follow these patterns:**
- Use Kotlin syntax
- Column definitions for TEXT fields: `@Column(columnDefinition = "TEXT")`
- MapStruct: `@Mapper(componentModel = "spring")`
- Service layer should publish events for lifecycle changes
- REST endpoints follow `/api/{entity-plural}` pattern
- Tests use Testcontainers for integration tests

**After creation:**
- Run tests to verify everything works
- Check modularity (ModularityTest)
- Show me a summary of what was created
- Offer to commit with appropriate message
