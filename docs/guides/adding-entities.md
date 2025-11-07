# Adding New Entities

This guide walks through the complete process of adding a new entity to the Spring Search Tempo application.

## Overview

Adding a new entity involves creating the domain model, repository, service layer, DTO, mapper, and optionally REST endpoints or web controllers.

## Step-by-Step Process

### Step 1: Create the Entity

Create an entity class extending `FSObject` or as a standalone entity:

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

**Key Points:**
- Use `@Entity` annotation for JPA
- Extend `FSObject` if it's a file system object
- Use `columnDefinition = "TEXT"` for large text fields
- Use nullable types (`String?`) for optional fields

### Step 2: Create the Repository

```kotlin
interface FSEmailRepository : JpaRepository<FSEmail, Long> {
    fun findBySender(sender: String): List<FSEmail>
    fun findBySubject(subject: String): List<FSEmail>
}
```

**Key Points:**
- Extend `JpaRepository<EntityType, IdType>`
- Spring Data JPA auto-generates implementations
- Use method naming conventions for queries
- Add `@Query` for complex queries if needed

### Step 3: Create the DTO

```kotlin
data class FSEmailDTO(
    val id: Long?,
    val uri: String,
    val subject: String,
    val sender: String,
    val recipients: String,
    val body: String?,
    val receivedDate: Instant?
)
```

**Key Points:**
- Use Kotlin data classes for DTOs
- DTOs should match entity structure but focus on API contract
- Include validation annotations if needed (`@NotBlank`, `@Email`, etc.)

### Step 4: Create the Mapper

```kotlin
@Mapper(componentModel = "spring")
interface FSEmailMapper {
    fun toDTO(entity: FSEmail): FSEmailDTO
    fun toEntity(dto: FSEmailDTO): FSEmail

    @Mapping(target = "id", ignore = true)
    fun updateEntity(dto: FSEmailDTO, @MappingTarget entity: FSEmail): FSEmail
}
```

**Key Points:**
- Use MapStruct with `componentModel = "spring"`
- Create bidirectional mapping methods
- Use `@MappingTarget` for update operations
- Ignore `id` in update methods to prevent overwriting

### Step 5: Create Service Interface

```kotlin
interface FSEmailService {
    fun findById(id: Long): FSEmailDTO
    fun findAll(): List<FSEmailDTO>
    fun findBySender(sender: String): List<FSEmailDTO>
    fun save(dto: FSEmailDTO): FSEmailDTO
    fun update(id: Long, dto: FSEmailDTO): FSEmailDTO
    fun delete(id: Long)
}
```

**Key Points:**
- Work with DTOs in service interface, not entities
- Define all CRUD operations
- Add domain-specific query methods
- Keep interface in base module for cross-module access

### Step 6: Implement the Service

```kotlin
@Service
class FSEmailServiceImpl(
    private val repository: FSEmailRepository,
    private val mapper: FSEmailMapper,
    private val publisher: ApplicationEventPublisher
) : FSEmailService {

    override fun findById(id: Long): FSEmailDTO {
        val entity = repository.findById(id)
            .orElseThrow { NotFoundException("Email not found with id: $id") }
        return mapper.toDTO(entity)
    }

    override fun findAll(): List<FSEmailDTO> {
        return repository.findAll()
            .map { mapper.toDTO(it) }
    }

    override fun findBySender(sender: String): List<FSEmailDTO> {
        return repository.findBySender(sender)
            .map { mapper.toDTO(it) }
    }

    override fun save(dto: FSEmailDTO): FSEmailDTO {
        val entity = mapper.toEntity(dto)
        val saved = repository.save(entity)

        // Publish event for cross-module communication
        publisher.publishEvent(FSEmailCreatedEvent(saved.id!!, saved.sender))

        return mapper.toDTO(saved)
    }

    override fun update(id: Long, dto: FSEmailDTO): FSEmailDTO {
        val existing = repository.findById(id)
            .orElseThrow { NotFoundException("Email not found with id: $id") }

        val updated = mapper.updateEntity(dto, existing)
        val saved = repository.save(updated)

        return mapper.toDTO(saved)
    }

    override fun delete(id: Long) {
        if (!repository.existsById(id)) {
            throw NotFoundException("Email not found with id: $id")
        }
        repository.deleteById(id)
        publisher.publishEvent(FSEmailDeletedEvent(id))
    }
}
```

**Key Points:**
- Use constructor injection for dependencies
- Always work with DTOs at service boundaries
- Throw `NotFoundException` for missing entities
- Publish events for cross-cutting concerns
- Use `@Transactional` if needed (default for CrudRepository methods)

### Step 7: Create REST Resource (Optional)

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

    @GetMapping("/{id}")
    fun getEmail(@PathVariable id: Long): EntityModel<FSEmailDTO> {
        val email = service.findById(id)
        return assembler.toModel(email)
    }

    @PostMapping
    fun createEmail(@Valid @RequestBody dto: FSEmailDTO): ResponseEntity<EntityModel<FSEmailDTO>> {
        val created = service.save(dto)
        val model = assembler.toModel(created)
        return ResponseEntity
            .created(model.getRequiredLink(IanaLinkRelations.SELF).toUri())
            .body(model)
    }

    @PutMapping("/{id}")
    fun updateEmail(
        @PathVariable id: Long,
        @Valid @RequestBody dto: FSEmailDTO
    ): EntityModel<FSEmailDTO> {
        val updated = service.update(id, dto)
        return assembler.toModel(updated)
    }

    @DeleteMapping("/{id}")
    fun deleteEmail(@PathVariable id: Long): ResponseEntity<Void> {
        service.delete(id)
        return ResponseEntity.noContent().build()
    }
}
```

**HATEOAS Model Assembler:**

```kotlin
@Component
class FSEmailModelAssembler :
    RepresentationModelAssemblerSupport<FSEmailDTO, EntityModel<FSEmailDTO>>(
        FSEmailResource::class.java,
        EntityModel::class.java as Class<EntityModel<FSEmailDTO>>
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

### Step 8: Create Web Controller (Optional)

```kotlin
@Controller
@RequestMapping("/emails")
class FSEmailController(
    private val service: FSEmailService
) {

    @GetMapping
    fun list(model: Model): String {
        model.addAttribute("emails", service.findAll())
        return "emails/list"
    }

    @GetMapping("/{id}")
    fun view(@PathVariable id: Long, model: Model): String {
        model.addAttribute("email", service.findById(id))
        return "emails/view"
    }

    @GetMapping("/new")
    fun newForm(model: Model): String {
        model.addAttribute("email", FSEmailDTO(null, "", "", "", "", null, null))
        return "emails/form"
    }

    @PostMapping
    fun create(@Valid @ModelAttribute email: FSEmailDTO, result: BindingResult): String {
        if (result.hasErrors()) {
            return "emails/form"
        }
        service.save(email)
        return "redirect:/emails"
    }
}
```

## Testing Your New Entity

### Repository Test

```kotlin
@DataJpaTest
@Testcontainers
class FSEmailRepositoryTest {

    @Autowired
    lateinit var repository: FSEmailRepository

    @Test
    fun `should save and retrieve email`() {
        val email = FSEmail().apply {
            uri = "email://test@example.com/subject"
            subject = "Test Email"
            sender = "test@example.com"
        }

        val saved = repository.save(email)
        val found = repository.findById(saved.id!!)

        assertThat(found).isPresent
        assertThat(found.get().subject).isEqualTo("Test Email")
    }

    @Test
    fun `should find emails by sender`() {
        val email1 = FSEmail().apply {
            uri = "email://sender@example.com/1"
            sender = "sender@example.com"
        }
        val email2 = FSEmail().apply {
            uri = "email://sender@example.com/2"
            sender = "sender@example.com"
        }

        repository.saveAll(listOf(email1, email2))

        val found = repository.findBySender("sender@example.com")
        assertThat(found).hasSize(2)
    }
}
```

### Service Test

```kotlin
@ExtendWith(MockitoExtension::class)
class FSEmailServiceImplTest {

    @Mock
    lateinit var repository: FSEmailRepository

    @Mock
    lateinit var mapper: FSEmailMapper

    @Mock
    lateinit var publisher: ApplicationEventPublisher

    @InjectMocks
    lateinit var service: FSEmailServiceImpl

    @Test
    fun `should throw NotFoundException when email not found`() {
        whenever(repository.findById(1L)).thenReturn(Optional.empty())

        assertThrows<NotFoundException> {
            service.findById(1L)
        }
    }

    @Test
    fun `should publish event when email created`() {
        val dto = FSEmailDTO(null, "email://test", "Subject", "sender@test.com", "", null, null)
        val entity = FSEmail().apply { id = 1L }

        whenever(mapper.toEntity(dto)).thenReturn(entity)
        whenever(repository.save(any())).thenReturn(entity)
        whenever(mapper.toDTO(entity)).thenReturn(dto.copy(id = 1L))

        service.save(dto)

        verify(publisher).publishEvent(any<FSEmailCreatedEvent>())
    }
}
```

## Best Practices

1. **Always use DTOs** at service boundaries, never expose entities directly
2. **Publish events** for cross-module communication instead of direct dependencies
3. **Add validation** on DTOs using Bean Validation annotations
4. **Write tests** for repository, service, and controller layers
5. **Use transactions** appropriately (especially for multi-step operations)
6. **Follow naming conventions** for consistency across the codebase
7. **Document complex logic** with code comments or separate docs

## Common Pitfalls

- ❌ Exposing entities in REST APIs (use DTOs)
- ❌ Accessing repositories from other modules (use services)
- ❌ Forgetting to add `@Transactional` for complex operations
- ❌ Not handling null cases properly in Kotlin
- ❌ Creating circular dependencies between entities

## See Also

- [Custom Validation](validation.md)
- [Testing Guide](testing.md)
- [Module Design](../architecture/module-design.md)
- [Batch Jobs](batch-jobs.md)
