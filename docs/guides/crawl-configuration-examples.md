# Crawl Configuration Examples

Quick reference for common crawl configuration patterns.

---

## Quick Start: Minimal Configuration

```yaml
app:
  crawl:
    crawls:
      - name: "MY_DOCS"
        label: "My Documents"
        enabled: true
        start-path: "${user.home}/Documents"
        file-patterns:
          index:
            - ".*\\.(txt|pdf|docx?)$"
```

---

## Example 1: Personal Knowledge Base

Index all your personal documents with full NLP on markdown/text:

```yaml
- name: "PERSONAL_KB"
  label: "Personal Knowledge Base"
  enabled: true
  start-path: "${user.home}/Documents"
  max-depth: 20
  parallel: true

  folder-patterns:
    skip:
      - ".*/\\.Trash/.*"
      - ".*/Archive/.*"
    index:
      - ".*"

  file-patterns:
    skip:
      - ".*\\.(tmp|bak|~)$"

    index:
      # Documents
      - ".*\\.(pdf|docx?|xlsx?|pptx?|odt|rtf)$"
      - ".*\\.(txt|md|org|rst)$"

      # E-books
      - ".*\\.(epub|mobi)$"

      # Notes
      - ".*\\.html?$"

    analyze:
      # Rich text for NLP
      - ".*\\.(txt|md|org|rst)$"
```

**Use Case**: Personal wiki, notes, research papers, e-books. Full-text search with NLP on plain text formats.

---

## Example 2: Software Development Workspace

Index all code repositories with documentation analysis:

```yaml
- name: "DEV_WORKSPACE"
  label: "Development Projects"
  enabled: true
  start-path: "${WORKSPACE_ROOT}"
  max-depth: 12
  parallel: true

  folder-patterns:
    skip:
      # VCS
      - ".*/\\.git/.*"
      - ".*/\\.svn/.*"

      # Build artifacts
      - ".*/build/.*"
      - ".*/target/.*"
      - ".*/dist/.*"
      - ".*/out/.*"

      # Dependencies
      - ".*/node_modules/.*"
      - ".*/vendor/.*"
      - ".*/venv/.*"
      - ".*/__pycache__/.*"

      # IDE
      - ".*/\\.idea/.*"
      - ".*/\\.vscode/.*"

    index:
      - ".*"

  file-patterns:
    skip:
      # Compiled
      - ".*\\.(o|so|dll|dylib|class|pyc|pyo)$"

      # Archives
      - ".*\\.(zip|tar|gz|bz2|7z|rar)$"

      # Lock files
      - ".*\\.(lock|lck)$"

    index:
      # Source code
      - ".*\\.(java|kt|kts|scala)$"                    # JVM
      - ".*\\.(py|pyi)$"                               # Python
      - ".*\\.(js|ts|jsx|tsx|mjs|cjs)$"                # JavaScript/TypeScript
      - ".*\\.(go)$"                                   # Go
      - ".*\\.(rs)$"                                   # Rust
      - ".*\\.(c|h|cpp|hpp|cc|cxx)$"                   # C/C++
      - ".*\\.(rb|rake)$"                              # Ruby
      - ".*\\.(php)$"                                  # PHP
      - ".*\\.(sh|bash|zsh|fish)$"                     # Shell

      # Config
      - ".*\\.(xml|json|ya?ml|toml|ini|conf|config)$"
      - ".*\\.properties$"
      - ".*\\.env\\.example$"   # Don't index .env (secrets!)

      # Build files
      - ".*/Dockerfile.*$"
      - ".*/docker-compose.*\\.ya?ml$"
      - ".*/(Makefile|Rakefile|Gemfile|CMakeLists\\.txt)$"
      - ".*/(package\\.json|composer\\.json|requirements\\.txt)$"
      - ".*/(build\\.gradle.*|settings\\.gradle.*|pom\\.xml)$"
      - ".*/(Cargo\\.toml|go\\.mod|mix\\.exs)$"

      # Documentation
      - ".*\\.(md|rst|adoc|txt)$"

    analyze:
      # Deep analysis for docs
      - ".*/(README|CONTRIBUTING|CHANGELOG|LICENSE|AUTHORS|NOTICE).*$"
      - ".*\\.md$"
```

**Use Case**: Multi-language development workspace. Search across code, configs, and docs. NLP on README files.

---

## Example 3: Media Library (Metadata Only)

Track photos, videos, and music without indexing content:

```yaml
- name: "MEDIA_LIBRARY"
  label: "Photos, Videos, Music"
  enabled: true
  start-path: "${user.home}/Media"
  max-depth: 10
  parallel: false  # Avoid hammering disk with parallel I/O

  folder-patterns:
    skip:
      - ".*/\\.thumbnails/.*"
      - ".*/\\.cache/.*"
      - ".*/Trash/.*"
    locate:
      - ".*"  # All folders: metadata only

  file-patterns:
    locate:
      # Images
      - ".*\\.(jpg|jpeg|png|gif|bmp|tiff|tif|webp)$"
      - ".*\\.(heic|heif|raw|cr2|nef|arw|dng)$"
      - ".*\\.(svg|ico)$"

      # Videos
      - ".*\\.(mp4|avi|mov|mkv|flv|wmv|webm|m4v)$"
      - ".*\\.(mpeg|mpg|3gp|ogv)$"

      # Audio
      - ".*\\.(mp3|flac|wav|aac|ogg|m4a|wma)$"

      # Sidecar files (store metadata about media)
      - ".*\\.(xmp|thm|nfo)$"
```

**Use Case**: Build a searchable catalog of media file locations (like `plocate` for photos). Fast, no text extraction.

---

## Example 4: Research Papers Collection

Index PDFs and their metadata, analyze abstracts:

```yaml
- name: "RESEARCH_PAPERS"
  label: "Academic Papers"
  enabled: true
  start-path: "${user.home}/Research/Papers"
  max-depth: 8

  folder-patterns:
    index:
      - ".*"

  file-patterns:
    index:
      # Papers
      - ".*\\.pdf$"

      # Supporting materials
      - ".*\\.(docx?|pptx?|xlsx?)$"

      # Citation files
      - ".*\\.(bib|ris|enw|nbib)$"

      # Plain text
      - ".*\\.(txt|md|tex)$"

    analyze:
      # Deep analysis for notes and abstracts
      - ".*/(abstract|notes|summary)\\.(txt|md)$"
      - ".*/(README|INDEX)\\..*$"
```

**Use Case**: Academic research collection. Extract text from PDFs, analyze structured notes. Search by author, title, keywords.

---

## Example 5: Email Archive

Index exported email archives (MBOX, EML):

```yaml
- name: "EMAIL_ARCHIVE"
  label: "Email Archive"
  enabled: false  # Run manually to avoid constant re-indexing
  start-path: "${user.home}/Mail/Archive"
  max-depth: 5

  folder-patterns:
    skip:
      - ".*/\\.AppleDouble/.*"
      - ".*/\\.DS_Store$"
    index:
      - ".*"

  file-patterns:
    index:
      # Email formats
      - ".*\\.eml$"
      - ".*\\.msg$"
      - ".*\\.mbox$"

      # Attachments (if exported separately)
      - ".*\\.(pdf|docx?|txt)$"

    analyze:
      # Analyze email bodies for NER (extract person names, orgs, dates)
      - ".*\\.eml$"
```

**Use Case**: Make old email archives searchable. Extract sender, recipients, dates, body text. NER identifies people and organizations.

---

## Example 6: System Configuration Backup

Index system configs for disaster recovery:

```yaml
- name: "SYSTEM_CONFIGS"
  label: "System Configuration Files"
  enabled: false  # Manual only
  start-path: "/etc"
  max-depth: 4
  follow-links: false

  folder-patterns:
    skip:
      - ".*/ssl/.*"        # Don't index certs/keys
      - ".*/pki/.*"
      - ".*/ssh/.*"        # Don't index SSH keys
    locate:
      - ".*"

  file-patterns:
    skip:
      # Secrets
      - ".*/shadow$"
      - ".*/passwd-$"
      - ".*\\.key$"
      - ".*\\.pem$"

    index:
      # Config files
      - ".*\\.conf$"
      - ".*\\.cfg$"
      - ".*\\.config$"
      - ".*\\.ini$"

      # Systemd units
      - ".*\\.service$"
      - ".*\\.socket$"
      - ".*\\.timer$"

      # Config directories
      - ".*/.*\\.d/.*$"

      # Plain text configs
      - ".*/(hosts|fstab|crontab|profile|bashrc|resolv\\.conf)$"
```

**Use Case**: Create searchable index of system configuration. **Critical**: Skip secrets! Use for searching "which config file sets X?"

---

## Example 7: Multi-Source Unified Search

Combine multiple crawls for unified search:

```yaml
app:
  crawl:
    defaults:
      max-depth: 10
      follow-links: false
      parallel: false
      folder-patterns:
        skip:
          - ".*/\\.git/.*"
          - ".*/node_modules/.*"
      file-patterns:
        skip:
          - ".*\\.(tmp|bak|~)$"

    crawls:
      # Personal documents
      - name: "DOCS"
        label: "Documents"
        enabled: true
        start-path: "${user.home}/Documents"
        max-depth: 15
        file-patterns:
          index: [".*\\.(pdf|docx?|txt|md)$"]
          analyze: [".*\\.(txt|md)$"]

      # Code projects
      - name: "CODE"
        label: "Code"
        enabled: true
        start-path: "${user.home}/Projects"
        max-depth: 10
        file-patterns:
          index: [".*\\.(kt|java|py|js|ts|md)$"]
          analyze: [".*\\.md$"]

      # Downloads (quick scan)
      - name: "DOWNLOADS"
        label: "Downloads"
        enabled: true
        start-path: "${user.home}/Downloads"
        max-depth: 3
        file-patterns:
          index: [".*\\.(pdf|txt|md)$"]

      # Desktop (quick scan)
      - name: "DESKTOP"
        label: "Desktop"
        enabled: true
        start-path: "${user.home}/Desktop"
        max-depth: 2
        file-patterns:
          index: [".*\\.(txt|md|pdf)$"]
```

**Use Case**: Search across multiple locations in one query. All results ranked together.

---

## Example 8: Incremental Crawl (Future-Ready)

Prepared for incremental crawling (Phase 1 feature):

```yaml
- name: "INCREMENTAL_DOCS"
  label: "Incremental Document Crawl"
  enabled: true
  start-path: "${user.home}/Documents"
  max-depth: 15
  parallel: true

  # Future: These will control incremental behavior
  # incremental: true
  # check-modified: true
  # skip-unchanged: true

  folder-patterns:
    index:
      - ".*"

  file-patterns:
    index:
      - ".*\\.(txt|md|pdf|docx?)$"
```

**Note**: Incremental crawling uses `lastModified` timestamps to skip unchanged files. Configuration structure is ready.

---

## Pattern Testing Checklist

Before deploying a new crawl config:

1. **Test on small directory first**:
   ```yaml
   start-path: "/tmp/test-crawl"
   max-depth: 3
   ```

2. **Verify skip patterns work**:
   - Check `.git` folders are excluded
   - Check temp files are excluded

3. **Verify index patterns match expected files**:
   - Run crawl
   - Query database: `SELECT uri, analysis_status FROM fs_file LIMIT 20;`
   - Verify AnalysisStatus is correct

4. **Check performance**:
   - Monitor crawl duration
   - Adjust `max-depth` if too slow
   - Enable `parallel: true` for large dirs

5. **Validate no secrets indexed**:
   - Ensure `.env`, `credentials.json`, etc. are skipped
   - Add explicit skip patterns if needed

---

## Related Documentation

- [Crawl Configuration Guide](crawl-configuration.md) - Complete reference
- [Pattern Syntax](crawl-configuration.md#pattern-syntax-reference) - Regex guide
- [Batch Jobs](batch-jobs.md) - Running crawls
- [Testing Guide](testing.md) - Writing configuration tests

---

**Last Updated**: 2025-11-07
