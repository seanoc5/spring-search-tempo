# Crawl Configuration Guide

This guide explains how to configure file system crawling in Spring Search Tempo using YAML-based configuration.

---

## Overview

Spring Search Tempo supports multiple concurrent crawl definitions, each with its own:
- Start path (root directory to crawl)
- Maximum depth limit
- File and folder pattern matching rules
- Processing level assignments (IGNORE, LOCATE, INDEX, ANALYZE)

Configuration is defined in `application.yml` under the `app.crawl` section.

---

## Configuration Structure

```yaml
app:
  crawl:
    defaults:      # Global settings inherited by all crawls
      max-depth: 10
      follow-links: false
      parallel: false
      folder-patterns:
        skip: [...]     # Patterns to exclude folders
      file-patterns:
        skip: [...]     # Patterns to exclude files

    crawls:        # List of crawl definitions
      - name: "CRAWL_NAME"
        label: "Human-readable label"
        enabled: true
        start-path: "/path/to/crawl"
        # Optional overrides
        max-depth: 15
        parallel: true
        folder-patterns:
          skip: [...]
          locate: [...]
          index: [...]
          analyze: [...]
        file-patterns:
          skip: [...]
          locate: [...]
          index: [...]
          analyze: [...]
```

---

## Processing Levels

Files and folders are assigned one of four **AnalysisStatus** values based on pattern matching:

| Level | Description | Storage | Use Case |
|-------|-------------|---------|----------|
| **IGNORE** | Skip completely | Not stored | `.git`, `node_modules`, temp files |
| **LOCATE** | Metadata only | Path, size, timestamps | Binary files, images (like `plocate`) |
| **INDEX** | Extract text | + Full text, metadata | Documents, code files |
| **ANALYZE** | Full NLP | + Sentence chunks, NER, parsing | Important documents |

---

## Pattern Matching Rules

### Pattern Types

Patterns are **regular expressions** matched against absolute file paths:

- **skip**: Highest priority - always exclude if matched (→ IGNORE)
- **analyze**: Next priority (→ ANALYZE)
- **index**: Next priority (→ INDEX)
- **locate**: Lowest priority (→ LOCATE)

### Matching Priority

1. **SKIP patterns always win** (excludes the item)
2. Explicit patterns checked in order: ANALYZE → INDEX → LOCATE
3. If no explicit match, inherit from parent folder
4. If no parent, default to LOCATE

### Inheritance Rules

#### Folders
- Match explicit patterns first
- If no match, inherit from parent folder
- Root folders default to LOCATE

#### Files
- Match file-specific patterns first
- If parent folder is IGNORE, file is also IGNORE
- Otherwise, inherit from parent folder **capped at INDEX**
  - This prevents accidental ANALYZE of all files in an ANALYZE folder
  - Files must explicitly match `analyze` patterns to get ANALYZE status

---

## Pattern Examples

### Common Folder Patterns

```yaml
folder-patterns:
  skip:
    # Version control
    - ".*/\\.git/.*"
    - ".*/\\.svn/.*"

    # Build artifacts
    - ".*/build/.*"
    - ".*/target/.*"
    - ".*/dist/.*"

    # Dependencies
    - ".*/node_modules/.*"
    - ".*/vendor/.*"
    - ".*/\\.gradle/.*"

    # System folders
    - "/tmp/.*"
    - "/proc/.*"
    - "/dev/.*"

  locate:
    - ".*/bin/.*"           # Binary directories
    - "/usr/.*"             # System files

  index:
    - ".*/Documents/.*"     # User documents
    - ".*/src/.*"           # Source code

  analyze:
    - ".*/important/.*"     # Critical folders
```

### Common File Patterns

```yaml
file-patterns:
  skip:
    # Temporary files
    - ".*\\.(tmp|bak|~|swp)$"
    - ".*\\.lock$"

    # Compiled artifacts
    - ".*\\.(o|class|pyc|dll|so)$"

    # Large binaries
    - ".*\\.(iso|dmg|app)$"

  locate:
    # Images (metadata only)
    - ".*\\.(jpg|jpeg|png|gif|bmp|svg|webp)$"

    # Videos (metadata only)
    - ".*\\.(mp4|avi|mov|mkv)$"

    # System binaries
    - ".*/bin/.*"

  index:
    # Documents
    - ".*\\.(pdf|docx?|xlsx?|pptx?|odt|rtf)$"

    # Code files
    - ".*\\.(kt|java|py|js|ts|go|rs|c|cpp|h)$"

    # Config files
    - ".*\\.(xml|json|ya?ml|toml|properties|conf)$"

    # Plain text
    - ".*\\.(txt|log|csv)$"

    # Build files (no extension)
    - ".*/Makefile$"
    - ".*/Dockerfile$"
    - ".*/(build\\.gradle.*|pom\\.xml)$"

  analyze:
    # Documentation (full NLP processing)
    - ".*\\.(md|rst|adoc)$"
    - ".*\\.(txt)$"

    # Important documents
    - ".*/README.*$"
    - ".*/CHANGELOG.*$"
```

---

## Pattern Syntax Reference

Patterns use **Java regular expressions**. Common constructs:

| Pattern | Meaning | Example |
|---------|---------|---------|
| `.` | Any character | `a.c` matches "abc", "a1c" |
| `.*` | Zero or more of any char | `log.*` matches "log", "log.txt" |
| `$` | End of string | `.*\\.txt$` matches files ending in .txt |
| `^` | Start of string | `^/home/.*` matches paths starting with /home/ |
| `\\.` | Literal dot | `.*\\.pdf$` matches .pdf files |
| `(a\|b)` | Either a or b | `.*\\.(txt\|md)$` matches .txt or .md |
| `[abc]` | Any of a, b, or c | `file[123]\\.txt` matches file1.txt, file2.txt |
| `a?` | Optional a | `ya?ml` matches yaml or yml |
| `+` | One or more | `log.+` matches log1, log123 (not just "log") |

### Escaping Special Characters

- **Backslash**: Use `\\\\` (four backslashes in YAML)
- **Dot**: Use `\\.` (matches literal period)
- **Parentheses, brackets**: Usually don't need escaping in character classes

---

## Configuration Examples

### Example 1: User Documents Crawl

Index common document formats, analyze text/markdown for NLP:

```yaml
- name: "USER_DOCUMENTS"
  label: "User Documents"
  enabled: true
  start-path: "${user.home}/Documents"
  max-depth: 15
  parallel: true

  folder-patterns:
    index:
      - ".*"  # Index all folders (unless skipped by defaults)

  file-patterns:
    index:
      - ".*\\.(txt|md|pdf|docx?|xlsx?|pptx?|odt|rtf|csv)$"
    analyze:
      - ".*\\.(md|txt)$"
```

**Result**:
- PDFs, Word docs → INDEX (text extracted, searchable)
- Text/Markdown → ANALYZE (text + sentence chunking for future NLP)
- Other files → inherit from parent (LOCATE by default)

### Example 2: Code Repository Crawl

Index source code and configs, analyze documentation:

```yaml
- name: "WORK"
  label: "Work Projects"
  enabled: true
  start-path: "/opt/work"
  max-depth: 10
  parallel: true

  folder-patterns:
    index:
      - ".*"

  file-patterns:
    index:
      # Source code
      - ".*\\.(kt|java|py|js|ts|go|rs|c|cpp|h|hpp|sh|bash)$"

      # Configuration
      - ".*\\.(xml|json|ya?ml|toml|properties|conf|config|ini)$"

      # Documentation
      - ".*\\.(md|txt|rst|adoc)$"

      # Build files
      - ".*/Dockerfile$"
      - ".*/(Makefile|Rakefile|Gemfile|build\\.gradle.*|pom\\.xml)$"

    analyze:
      - ".*\\.(md|txt)$"
```

### Example 3: Photo Library (Metadata Only)

Store file paths and metadata, but don't extract text:

```yaml
- name: "USER_PICTURES"
  label: "User Pictures"
  enabled: true
  start-path: "${user.home}/Pictures"
  max-depth: 8

  folder-patterns:
    skip:
      - ".*/\\.thumbnails/.*"
      - ".*/cache/.*"
    locate:
      - ".*"  # All other folders: metadata only

  file-patterns:
    locate:
      - ".*\\.(jpg|jpeg|png|gif|bmp|svg|webp|heic|tiff|raw)$"
```

**Result**: Creates a searchable index of photo locations (like `plocate`) without extracting image content.

### Example 4: System Configuration (Disabled)

Prepared but not automatically run:

```yaml
- name: "CONFIG"
  label: "System Configuration"
  enabled: false       # Won't run on startup
  start-path: "/etc"
  max-depth: 3

  folder-patterns:
    locate:
      - ".*"

  file-patterns:
    index:
      - ".*\\.(conf|cfg|config|ini)$"
      - ".*/.*\\.d/.*$"  # .d directories (systemd, etc.)
```

---

## Global Defaults

Defaults apply to **all crawls** and are merged with crawl-specific settings:

```yaml
defaults:
  max-depth: 10           # Default max depth
  follow-links: false     # Don't follow symlinks
  parallel: false         # Sequential processing

  folder-patterns:
    skip:                 # Always skip these (merged with crawl-specific skip)
      - ".*/\\.git/.*"
      - ".*/\\.gradle/.*"
      - ".*/node_modules/.*"
      # ... more

  file-patterns:
    skip:                 # Always skip these (merged with crawl-specific skip)
      - ".*\\.(lck|tmp|~|bak)$"
```

### Merge Behavior

- **SKIP patterns**: **Merged** (defaults + crawl-specific)
  - Ensures common exclusions apply everywhere
- **LOCATE, INDEX, ANALYZE patterns**: **Crawl-specific only**
  - Each crawl defines its own inclusion rules

### Override Defaults

Crawls can override default settings:

```yaml
- name: "DEEP_CRAWL"
  max-depth: 20          # Override default of 10
  follow-links: true     # Override default of false
  parallel: true         # Enable parallel processing
```

---

## Environment Variables

Use Spring Boot property placeholders for dynamic paths:

```yaml
start-path: "${user.home}/Documents"
start-path: "${HOME}/Downloads"
start-path: "${WORKSPACE_ROOT}/projects"
```

Spring Boot resolves these at runtime.

---

## Best Practices

### 1. Start Conservative

Begin with restrictive patterns, then expand:

```yaml
file-patterns:
  index:
    - ".*\\.txt$"    # Start with just .txt
  # Later add more as needed
```

### 2. Use LOCATE for Large Binary Sets

Don't index content for files you'll never search inside:

```yaml
file-patterns:
  locate:
    - ".*\\.(jpg|png|mp4|zip)$"
```

### 3. Reserve ANALYZE for Critical Content

NLP processing is expensive. Only analyze what you'll deeply search:

```yaml
file-patterns:
  analyze:
    - ".*/(README|CHANGELOG|NOTES)\\.md$"  # Only specific files
```

### 4. Test Patterns Incrementally

Run crawls on small directories first:

```yaml
- name: "TEST"
  start-path: "/tmp/test-data"
  max-depth: 3
  # Test patterns here
```

### 5. Monitor Performance

Check crawl speed and adjust:
- Reduce `max-depth` if too slow
- Use `parallel: true` for large directories
- Add more skip patterns for irrelevant content

### 6. Document Custom Patterns

Add comments to explain complex patterns:

```yaml
file-patterns:
  index:
    # Match build files without extensions
    - ".*/Makefile$"
    - ".*/Dockerfile$"

    # Match Gradle build files with any extension
    - ".*/(build\\.gradle.*)$"
```

---

## Testing Patterns

Use the `PatternMatchingService` directly in tests:

```kotlin
@Autowired
private lateinit var patternMatchingService: PatternMatchingService

@Autowired
private lateinit var crawlConfigService: CrawlConfigService

@Test
fun testMyPattern() {
    val crawl = crawlConfigService.getCrawlByName("MY_CRAWL")!!
    val patterns = crawlConfigService.getEffectivePatterns(crawl)

    val status = patternMatchingService.determineFileAnalysisStatus(
        path = "/test/path/file.txt",
        filePatterns = patterns.filePatterns,
        parentFolderStatus = AnalysisStatus.INDEX
    )

    assertEquals(AnalysisStatus.INDEX, status)
}
```

---

## Troubleshooting

### Pattern Not Matching

**Symptom**: Files aren't being indexed as expected

**Solutions**:
1. Check regex syntax (use online regex testers)
2. Ensure pattern matches **full absolute path**, not just filename
3. Verify skip patterns aren't excluding the file
4. Check parent folder status (files inherit from folders)

Example debugging:

```kotlin
// Log the effective patterns
val patterns = crawlConfigService.getEffectivePatterns(crawl)
println("File patterns: ${patterns.filePatterns}")

// Test a specific path
val status = patternMatchingService.determineFileAnalysisStatus(
    path = "/full/absolute/path/to/file.txt",
    filePatterns = patterns.filePatterns,
    parentFolderStatus = AnalysisStatus.INDEX
)
println("Status: $status")
```

### Too Many Files Indexed

**Symptom**: Crawl takes too long, database grows large

**Solutions**:
1. Add more skip patterns for irrelevant directories
2. Reduce `max-depth`
3. Change INDEX patterns to LOCATE (metadata only)
4. Disable overly broad crawls

### Environment Variable Not Resolved

**Symptom**: Literal `${user.home}` appears in path

**Solutions**:
1. Ensure using Spring Boot property syntax: `${}`
2. Check variable exists: `echo $HOME`
3. Use alternate variable: `${HOME}` instead of `${user.home}`

---

## API Usage

### Get All Crawls

```kotlin
@Autowired
private lateinit var crawlConfigService: CrawlConfigService

val allCrawls = crawlConfigService.getAllCrawls()
val enabledOnly = crawlConfigService.getEnabledCrawls()
```

### Get Specific Crawl

```kotlin
val workCrawl = crawlConfigService.getCrawlByName("WORK")
    ?: error("WORK crawl not found")
```

### Get Effective Patterns

```kotlin
val patterns = crawlConfigService.getEffectivePatterns(workCrawl)
// patterns.folderPatterns contains merged defaults + crawl-specific
// patterns.filePatterns contains merged defaults + crawl-specific
```

### Determine File Status

```kotlin
@Autowired
private lateinit var patternMatchingService: PatternMatchingService

val fileStatus = patternMatchingService.determineFileAnalysisStatus(
    path = "/opt/work/project/Main.kt",
    filePatterns = patterns.filePatterns,
    parentFolderStatus = AnalysisStatus.INDEX
)
// Returns: AnalysisStatus.INDEX
```

---

## Related Documentation

- [Module Design](../architecture/module-design.md) - How configuration fits in the architecture
- [Batch Jobs Guide](batch-jobs.md) - How crawl jobs use configuration
- [Adding Entities](adding-entities.md) - Extending the domain model
- [Commands Reference](../reference/commands.md) - Running crawls

---

**Last Updated**: 2025-11-07
**Applies To**: Spring Search Tempo v0.1.9+
