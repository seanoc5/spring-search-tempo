# Custom Validation

This guide covers creating custom validation annotations and validators in Spring Search Tempo.

## Overview

Spring Boot uses Bean Validation (JSR-380) for validation. You can create custom validators for domain-specific validation logic.

## Basic Pattern

Creating a custom validator involves two steps:
1. Create a validation annotation
2. Implement the validator logic

## Complete Example: Unique Subject Validation

### Step 1: Create the Annotation

```kotlin
@Target(AnnotationTarget.FIELD, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [FSEmailSubjectUniqueValidator::class])
@Documented
annotation class FSEmailSubjectUnique(
    val message: String = "Email subject must be unique",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Any>> = []
)
```

**Key Elements:**
- `@Target`: Where annotation can be applied (field, class, etc.)
- `@Retention(RUNTIME)`: Available at runtime for validation
- `@Constraint`: Links to validator implementation
- `message`: Default error message
- `groups`: For grouped validation (optional)
- `payload`: For metadata (optional)

### Step 2: Implement the Validator

```kotlin
@Component
class FSEmailSubjectUniqueValidator(
    private val repository: FSEmailRepository
) : ConstraintValidator<FSEmailSubjectUnique, String> {

    override fun isValid(value: String?, context: ConstraintValidatorContext): Boolean {
        // Null values are valid (use @NotNull for null checking)
        if (value == null) {
            return true
        }

        // Check if subject already exists
        return repository.findBySubject(value).isEmpty()
    }
}
```

**Key Points:**
- Implement `ConstraintValidator<AnnotationType, FieldType>`
- Use constructor injection for dependencies (repositories, services)
- Return `true` for valid values, `false` for invalid
- Handle null appropriately (usually let `@NotNull` handle nulls)

### Step 3: Apply to DTO

```kotlin
data class FSEmailDTO(
    val id: Long?,

    @field:NotBlank(message = "Subject is required")
    @field:FSEmailSubjectUnique
    val subject: String,

    @field:NotBlank(message = "Sender is required")
    @field:Email(message = "Invalid email format")
    val sender: String
)
```

**Note**: Use `@field:` prefix in Kotlin to apply annotation to the field

## Advanced Validation Examples

### Cross-Field Validation

Validate multiple fields together:

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [DateRangeValidator::class])
annotation class ValidDateRange(
    val message: String = "End date must be after start date",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Any>> = []
)

@Component
class DateRangeValidator : ConstraintValidator<ValidDateRange, FSEmailDTO> {

    override fun isValid(value: FSEmailDTO?, context: ConstraintValidatorContext): Boolean {
        if (value == null) return true

        val start = value.receivedDate
        val end = value.processedDate

        if (start == null || end == null) return true

        return end.isAfter(start)
    }
}

// Usage
@ValidDateRange
data class FSEmailDTO(
    val receivedDate: Instant?,
    val processedDate: Instant?
)
```

### Conditional Validation

Validate based on another field's value:

```kotlin
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [ConditionalRequiredValidator::class])
annotation class ConditionalRequired(
    val field: String,
    val fieldValue: String,
    val message: String = "This field is required",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Any>> = []
)

@Component
class ConditionalRequiredValidator : ConstraintValidator<ConditionalRequired, Any?> {

    private lateinit var conditionalField: String
    private lateinit var conditionalFieldValue: String

    override fun initialize(annotation: ConditionalRequired) {
        conditionalField = annotation.field
        conditionalFieldValue = annotation.fieldValue
    }

    override fun isValid(value: Any?, context: ConstraintValidatorContext): Boolean {
        val target = context.unwrap(HibernateConstraintValidatorContext::class.java)
            .getDefaultConstraintViolation().rootBean

        // Get the field value using reflection
        val fieldValue = target::class.memberProperties
            .find { it.name == conditionalField }
            ?.call(target)

        // If conditional field matches, this field is required
        return if (fieldValue?.toString() == conditionalFieldValue) {
            value != null && value.toString().isNotBlank()
        } else {
            true // Not required in other cases
        }
    }
}

// Usage
data class FSEmailDTO(
    val type: String, // "important" or "normal"

    @field:ConditionalRequired(
        field = "type",
        fieldValue = "important",
        message = "Priority is required for important emails"
    )
    val priority: String?
)
```

### Pattern with Parameters

Validator that accepts configuration:

```kotlin
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [FileSizeValidator::class])
annotation class ValidFileSize(
    val maxSizeMB: Int = 10,
    val message: String = "File size exceeds maximum",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Any>> = []
)

@Component
class FileSizeValidator : ConstraintValidator<ValidFileSize, Long> {

    private var maxSizeBytes: Long = 0

    override fun initialize(annotation: ValidFileSize) {
        maxSizeBytes = annotation.maxSizeMB * 1024L * 1024L
    }

    override fun isValid(value: Long?, context: ConstraintValidatorContext): Boolean {
        if (value == null) return true
        return value <= maxSizeBytes
    }
}

// Usage
data class FSFileDTO(
    @field:ValidFileSize(maxSizeMB = 50, message = "File must be under 50MB")
    val bodySize: Long?
)
```

### Custom Error Messages

Customize error messages with context:

```kotlin
@Component
class FSFileUriUniqueValidator(
    private val repository: FSFileRepository
) : ConstraintValidator<FSFileUriUnique, String> {

    override fun isValid(value: String?, context: ConstraintValidatorContext): Boolean {
        if (value == null) return true

        val exists = repository.findByUri(value) != null

        if (exists) {
            // Disable default message
            context.disableDefaultConstraintViolation()

            // Add custom message with parameters
            context.buildConstraintViolationWithTemplate(
                "File with URI '$value' already exists in the database"
            ).addConstraintViolation()

            return false
        }

        return true
    }
}
```

## Integration with Controllers

### REST Controller Validation

```kotlin
@RestController
@RequestMapping("/api/emails")
class FSEmailResource(private val service: FSEmailService) {

    @PostMapping
    fun createEmail(
        @Valid @RequestBody dto: FSEmailDTO
    ): ResponseEntity<FSEmailDTO> {
        val created = service.save(dto)
        return ResponseEntity.created(/* ... */).body(created)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationErrors(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val errors = ex.bindingResult.fieldErrors.associate {
            it.field to (it.defaultMessage ?: "Invalid value")
        }

        return ResponseEntity
            .badRequest()
            .body(ErrorResponse("Validation failed", errors))
    }
}

data class ErrorResponse(
    val message: String,
    val fieldErrors: Map<String, String>
)
```

### Web Controller Validation

```kotlin
@Controller
@RequestMapping("/emails")
class FSEmailController(private val service: FSEmailService) {

    @PostMapping
    fun create(
        @Valid @ModelAttribute email: FSEmailDTO,
        result: BindingResult,
        model: Model
    ): String {
        if (result.hasErrors()) {
            model.addAttribute("errors", result.allErrors)
            return "emails/form"
        }

        service.save(email)
        return "redirect:/emails"
    }
}
```

## Validation Groups

Use groups to apply different validation rules in different contexts:

```kotlin
interface CreateValidation
interface UpdateValidation

data class FSEmailDTO(
    @field:Null(groups = [CreateValidation::class], message = "ID must be null for creation")
    @field:NotNull(groups = [UpdateValidation::class], message = "ID is required for update")
    val id: Long?,

    @field:NotBlank
    val subject: String
)

// Usage in controller
@PostMapping
fun create(@Validated(CreateValidation::class) @RequestBody dto: FSEmailDTO): ResponseEntity<FSEmailDTO> {
    // id must be null
}

@PutMapping("/{id}")
fun update(
    @PathVariable id: Long,
    @Validated(UpdateValidation::class) @RequestBody dto: FSEmailDTO
): ResponseEntity<FSEmailDTO> {
    // id must not be null
}
```

## Testing Validators

### Unit Test

```kotlin
@ExtendWith(MockitoExtension::class)
class FSEmailSubjectUniqueValidatorTest {

    @Mock
    lateinit var repository: FSEmailRepository

    lateinit var validator: FSEmailSubjectUniqueValidator

    @Mock
    lateinit var context: ConstraintValidatorContext

    @BeforeEach
    fun setUp() {
        validator = FSEmailSubjectUniqueValidator(repository)
    }

    @Test
    fun `should return true when subject is unique`() {
        whenever(repository.findBySubject("Unique Subject")).thenReturn(emptyList())

        val result = validator.isValid("Unique Subject", context)

        assertThat(result).isTrue()
    }

    @Test
    fun `should return false when subject already exists`() {
        val existing = FSEmail().apply { subject = "Duplicate" }
        whenever(repository.findBySubject("Duplicate")).thenReturn(listOf(existing))

        val result = validator.isValid("Duplicate", context)

        assertThat(result).isFalse()
    }

    @Test
    fun `should return true for null values`() {
        val result = validator.isValid(null, context)

        assertThat(result).isTrue()
        verifyNoInteractions(repository)
    }
}
```

### Integration Test

```kotlin
@SpringBootTest
@Testcontainers
class FSEmailValidationIntegrationTest {

    @Autowired
    lateinit var validator: Validator

    @Autowired
    lateinit var repository: FSEmailRepository

    @Test
    fun `should fail validation when subject is not unique`() {
        // Given: existing email
        repository.save(FSEmail().apply { subject = "Test Subject" })

        // When: validating DTO with same subject
        val dto = FSEmailDTO(null, "Test Subject", "test@example.com")
        val violations = validator.validate(dto)

        // Then: validation fails
        assertThat(violations).hasSize(1)
        assertThat(violations.first().message).contains("unique")
    }
}
```

## Built-in Validation Annotations

Common Bean Validation annotations you can use:

```kotlin
data class ExampleDTO(
    @field:NotNull
    val id: Long?,

    @field:NotBlank
    @field:Size(min = 3, max = 100)
    val name: String,

    @field:Email
    val email: String,

    @field:Pattern(regexp = "^[0-9]{10}$", message = "Must be 10 digits")
    val phone: String?,

    @field:Min(0)
    @field:Max(100)
    val percentage: Int,

    @field:Positive
    val count: Long,

    @field:PastOrPresent
    val createdDate: Instant,

    @field:Future
    val scheduledDate: Instant?,

    @field:DecimalMin("0.0")
    @field:DecimalMax("999.99")
    val price: BigDecimal,

    @field:URL
    val website: String?
)
```

## Best Practices

1. **Separate Concerns**: Validation logic in validators, business logic in services
2. **Null Handling**: Let `@NotNull` handle null checks, validators assume non-null
3. **Clear Messages**: Provide meaningful error messages for users
4. **Dependency Injection**: Use constructor injection in validators
5. **Reusability**: Create generic validators that can be configured
6. **Testing**: Test validators independently and in integration
7. **Performance**: Avoid expensive operations in validators (e.g., external API calls)
8. **Groups**: Use validation groups for different contexts (create vs update)

## Common Pitfalls

- ❌ Performing business logic in validators (use services instead)
- ❌ Not handling null values properly
- ❌ Forgetting `@field:` prefix in Kotlin
- ❌ Making database queries for every validation (consider caching)
- ❌ Not testing edge cases (null, empty, boundary values)
- ❌ Circular dependencies between validators

## See Also

- [Adding Entities](adding-entities.md)
- [Testing Guide](testing.md)
- [Bean Validation Specification](https://beanvalidation.org/2.0/spec/)
