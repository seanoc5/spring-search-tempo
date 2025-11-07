# ADR 001: Use Kotlin for Spring Boot Development

## Status

Accepted

## Context

We needed to choose a JVM language for implementing Spring Search Tempo. The primary options were:
- Java (traditional choice for Spring)
- Kotlin (officially supported by Spring since 2017)
- Groovy (less common for Spring Boot)

## Decision

Use Kotlin as the primary language for Spring Search Tempo development.

## Rationale

### Advantages

1. **Null Safety**: Kotlin's type system distinguishes between nullable and non-nullable types, preventing NPEs at compile time
   ```kotlin
   var name: String = "value"      // Cannot be null
   var name: String? = null        // Explicitly nullable
   ```

2. **Concise Syntax**: Reduces boilerplate significantly
   - Data classes eliminate getter/setter/toString/equals/hashCode
   - No semicolons required
   - Type inference reduces verbosity
   - Extension functions enable cleaner code

3. **Excellent Spring Support**:
   - Official Spring Boot support since 2.0
   - Spring initializer includes Kotlin option
   - Spring team provides Kotlin-specific features
   - Extensive documentation and examples

4. **Interoperability**: 100% compatible with Java
   - Can call Java code from Kotlin seamlessly
   - Can call Kotlin code from Java
   - Mix Java and Kotlin in same project
   - Use all existing Java libraries

5. **Modern Language Features**:
   - Coroutines for async programming (future use)
   - Sealed classes for type-safe hierarchies
   - Extension functions
   - Smart casts
   - Inline functions

### Comparison Examples

**Java**:
```java
public class FSFile extends FSObject {
    private String bodyText;
    private Long bodySize;

    public String getBodyText() { return bodyText; }
    public void setBodyText(String bodyText) { this.bodyText = bodyText; }
    public Long getBodySize() { return bodySize; }
    public void setBodySize(Long bodySize) { this.bodySize = bodySize; }

    @Override
    public boolean equals(Object o) { /* ... */ }
    @Override
    public int hashCode() { /* ... */ }
    @Override
    public String toString() { /* ... */ }
}
```

**Kotlin**:
```kotlin
@Entity
class FSFile : FSObject() {
    var bodyText: String? = null
    var bodySize: Long? = null
}
```

### Trade-offs

**Drawbacks**:
- Learning curve for Java developers
- Some Spring annotations require special syntax (`@field:`)
- Smaller talent pool compared to Java
- Additional build complexity (kapt for annotation processing)

**Benefits Outweigh Drawbacks**:
- Modern, expressive syntax increases productivity
- Null safety prevents entire class of bugs
- Strong industry adoption and momentum
- Spring's first-class Kotlin support

## Consequences

### Positive

- Reduced boilerplate code
- Fewer null-related bugs
- More maintainable codebase
- Easier to write and read
- Future-ready for coroutines if needed

### Negative

- Team must learn Kotlin (manageable, similar to Java)
- Some Spring patterns require Kotlin-specific syntax
- Annotation processing uses kapt (slightly slower builds)

### Neutral

- Build configuration requires Kotlin plugin
- Mixed Java/Kotlin possible if needed
- Documentation should include Kotlin examples

## Implementation Notes

### Key Configurations

**build.gradle.kts**:
```kotlin
plugins {
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    kotlin("plugin.jpa") version "1.9.25"
    kotlin("kapt") version "1.9.25"
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
}
```

### Common Patterns

**Entities**:
```kotlin
@Entity
class FSFile : FSObject() {
    var bodyText: String? = null
}
```

**DTOs**:
```kotlin
data class FSFileDTO(
    val id: Long?,
    val uri: String
)
```

**Services**:
```kotlin
@Service
class FSFileServiceImpl(
    private val repository: FSFileRepository
) : FSFileService {
    // Constructor injection by default
}
```

**Validation**:
```kotlin
data class FSFileDTO(
    @field:NotBlank  // Note: @field: prefix required
    val uri: String
)
```

## References

- [Spring Boot Kotlin Support](https://spring.io/guides/tutorials/spring-boot-kotlin)
- [Kotlin for Spring Developers](https://kotlinlang.org/docs/jvm-spring-boot-restful.html)
- [Why Kotlin for Server-Side](https://kotlinlang.org/docs/server-overview.html)

## Date

2025-11-06
