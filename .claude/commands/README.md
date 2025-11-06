# Slash Commands Reference

Quick reference for custom slash commands in this project.

## Development Workflows

### `/add-entity` - Full Stack Entity Creation
**When**: Creating new domain entities with complete CRUD implementation

**Example**:
```
/add-entity name=FSEmail base_class=FSObject module=base inheritance=JOINED fields="subject:String, sender:String, recipients:String, body:TEXT, receivedDate:Instant"
```

**Creates**: Entity, Repository, DTO, Mapper, Service, REST Resource, Tests

---

### `/tdd` - Test-Driven Development
**When**: Implementing new functionality with tests first

**Example**:
```
/tdd functionality="FSFileRepository.findByUriStartingWith" edge_cases="null, empty, no matches" error_cases="invalid path" test_type="unit with mocks"
```

**Process**: Write tests → Run (fail) → Implement → Run (pass) → Refactor

---

### `/add-batch-job` - Spring Batch Job Creation
**When**: Creating new batch processing jobs

**Example**:
```
/add-batch-job name=crawlDocuments purpose="Index PDF files" input="file system scan" processing="extract text with Tika" output="save to FSFile" chunk_size=50 schedule="cron: 0 2 * * *"
```

**Creates**: JobConfig, ItemReader, ItemProcessor, ItemWriter, Tests

---

## Code Quality & Testing

### `/check-modularity` - Verify Module Boundaries
**When**: After adding dependencies or refactoring across modules

**Example**:
```
/check-modularity
```

**Checks**: Circular dependencies, unauthorized access, package visibility

---

### `/test-module` - Run Module Tests
**When**: Testing specific Spring Modulith module

**Example**:
```
/test-module module=base
```

**Runs**: All tests in specified module with summary report

---

### `/fix-bug` - Systematic Bug Investigation
**When**: Encountering errors, test failures, or unexpected behavior

**Example**:
```
/fix-bug description="LazyInitializationException on contentChunks" error="LazyInitException at FSFileServiceImpl:45" when="calling GET /api/files/1" changes="just added contentChunks relationship"
```

**Process**: Reproduce → Locate → Understand → Fix → Verify → Add test

---

### `/refactor` - Code Refactoring with Approval
**When**: Improving code structure without changing behavior

**Example**:
```
/refactor description="Extract file processing to separate FileProcessor interface" keep_api="identical" coverage="maintain current" approval="show plan first"
```

**Process**: Analyze → Plan → Approve → Execute → Verify

---

## Project Utilities

### `/crawl-status` - Crawl System Status
**When**: Checking what's implemented, configured, and indexed

**Example**:
```
/crawl-status
```

**Shows**: Config status, database stats, Phase 1 progress, recommendations

---

### `/db-query` - PostgreSQL Query Tool
**When**: Querying database or viewing entity counts

**Example**:
```
/db-query query="SELECT uri, last_modified FROM fs_file ORDER BY last_modified DESC LIMIT 5"
```

**Or** (for dashboard):
```
/db-query
```

**Shows**: Entity counts, recent files, database size

---

### `/add-config` - Configuration Management
**When**: Adding/modifying application.yml settings

**Example**:
```
/add-config description="crawl configuration for ~/Documents" target="application-dev.yml" type="crawl config" prefix="crawl"
```

**Creates**: YAML config + @ConfigurationProperties class + validation

---

### `/commit-pr` - Git Workflow
**When**: Ready to commit work and create pull request

**Example**:
```
/commit-pr scope="email feature (entity, repo, service, tests)" target="master"
```

**Process**: Review → Stage → Commit → Push → Create PR with test plan

---

## Tips for Effective Use

### ✅ DO:
- Provide as much context as possible in parameters
- Reference specific classes/files when relevant
- Specify expected behavior clearly
- Indicate preferences (e.g., "show plan first")

### ❌ DON'T:
- Use vague descriptions like "add feature"
- Omit important constraints or requirements
- Assume I know what you changed recently

### 🎯 Pro Tips:
1. **Chain commands**: Some commands work well together
   ```
   /add-entity ... followed by /test-module module=base
   ```

2. **Use context**: I can extract parameters from your message
   ```
   "I need email tracking with subject, sender, body fields"
   /add-entity
   ```

3. **Check before committing**:
   ```
   /check-modularity
   /test-module module=base
   /commit-pr scope="all changes"
   ```

---

## Command Cheat Sheet

| Task | Command |
|------|---------|
| New entity | `/add-entity` |
| TDD cycle | `/tdd` |
| Batch job | `/add-batch-job` |
| Fix error | `/fix-bug` |
| Refactor code | `/refactor` |
| Check modules | `/check-modularity` |
| Run tests | `/test-module` |
| DB query | `/db-query` |
| Crawl status | `/crawl-status` |
| Add config | `/add-config` |
| Commit & PR | `/commit-pr` |

---

**Generated**: 2025-11-06
**Project**: Spring Search Tempo
**For**: LLM-guided development with Claude Code
