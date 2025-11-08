# ADR 004: Single-Pass Crawl Strategy

## Status

Accepted (2025-11-07)

## Context

The file system crawler needs to walk directory trees, apply pattern matching rules, and index both folders and files into the database. The initial implementation used a two-phase approach, but performance analysis suggests a single-pass strategy would be more efficient.

We need to decide between:
1. **Two-Phase Crawl**: Walk all directories first, persist them, then query database and process files
2. **Single-Pass Crawl**: Process each directory and its immediate files together in one traversal
3. **Hybrid Approach**: Single pass with smart caching/batching for database operations

## Decision

Adopt a **single-pass crawl strategy with smart JPA caching** for processing directories and files together during tree traversal.

## Options Considered

### Option 1: Two-Phase Crawl (Current Implementation)

**Approach**:
```kotlin
// Step 1: Folders Step
FolderReader walks entire tree → FolderProcessor → FolderWriter → DB

// Step 2: Files Step
Query fs_folder table → List files from filesystem → FileProcessor → FileWriter → DB

// Step 3: Chunking Step
Query fs_file table (ANALYZE status) → ChunkProcessor → ChunkWriter → DB
```

**Advantages**:
- Clear separation of concerns (folders vs files)
- Simpler Spring Batch step definition
- Easier to restart individual steps on failure
- Database provides reliable state between steps

**Disadvantages**:
- Two separate passes through filesystem structures
- Folder data must be persisted to database before files can be processed
- Potential memory overhead from storing all folders in queue first
- Poor cache locality (directories "cold" by time files are processed)
- Higher database load (query all folders, then query all files)
- Slower for large file systems (double traversal overhead)

**Performance Characteristics**:
- Time: `O(dirs) + O(files)` with poor cache locality
- Memory: `O(dirs)` queue, plus database intermediate storage
- DB queries: `1 + N` (1 to fetch all folders, N folder listings)

---

### Option 2: Single-Pass Crawl (Proposed)

**Approach**:
```kotlin
// Single Step: Combined Folders + Files
For each directory in Files.walkFileTree():
    1. Apply folder patterns → determine folder status
    2. If not IGNORE:
        - Save folder to database (or batch)
        - List immediate files in directory
        - For each file:
            - Apply file patterns (with folder inheritance)
            - If not IGNORE: save file to database (or batch)
    3. Recurse to subdirectories (depth-first)

// Step 2: Chunking (unchanged)
Query fs_file table (ANALYZE status) → ChunkProcessor → ChunkWriter → DB
```

**Advantages**:
- Single filesystem traversal (better performance)
- Better cache locality (files processed while directory is "hot")
- Natural skip propagation (skip folder → skip all descendant files automatically)
- Lower memory footprint (no need to queue all folders)
- Reduced database roundtrips (batch insert folders+files together)
- More intuitive pattern matching behavior (immediate feedback)

**Disadvantages**:
- More complex step logic (combined folder+file processing)
- Harder to restart mid-step (less granular checkpointing)
- Need careful batch management to avoid memory bloat
- Transaction boundaries more complex

**Performance Characteristics**:
- Time: `O(dirs + files)` with good cache locality
- Memory: `O(depth)` stack depth, plus batch buffer
- DB queries: Batched inserts, minimal reads

---

### Option 3: Hybrid Approach (Recommended)

**Approach**:
Combine single-pass traversal with smart caching and batching:

```kotlin
// Use Files.walkFileTree() with custom FileVisitor
class CombinedCrawlVisitor : FileVisitor<Path> {
    private val folderBatch = mutableListOf<FSFolderDTO>()
    private val fileBatch = mutableListOf<FSFileDTO>()

    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
        val folderStatus = patternMatchingService.matchFolder(dir, parentStatus)

        if (folderStatus == IGNORE) {
            return FileVisitResult.SKIP_SUBTREE  // Skip folder and all descendants
        }

        folderBatch.add(createFolderDTO(dir, folderStatus))
        if (folderBatch.size >= BATCH_SIZE) flushFolders()

        // Process immediate files while directory is open
        Files.list(dir).filter { it.isRegularFile() }.forEach { file ->
            val fileStatus = patternMatchingService.matchFile(file, folderStatus)
            if (fileStatus != IGNORE) {
                fileBatch.add(createFileDTO(file, fileStatus))
                if (fileBatch.size >= BATCH_SIZE) flushFiles()
            }
        }

        return FileVisitResult.CONTINUE
    }

    private fun flushFolders() {
        folderService.saveAll(folderBatch)
        folderBatch.clear()
    }

    private fun flushFiles() {
        fileService.saveAll(fileBatch)
        fileBatch.clear()
    }
}
```

**Advantages**:
- All benefits of single-pass approach
- Batch database operations for efficiency
- Controlled memory usage via batch size limits
- Early termination on skip patterns (SKIP_SUBTREE)
- Still works within Spring Batch framework (single step)
- Can use JPA second-level cache for parent folder lookups

**Disadvantages**:
- More implementation complexity
- Need to manage batch flushing carefully
- Transaction boundaries require attention

**Performance Characteristics**:
- Time: `O(dirs + files)` with excellent cache locality
- Memory: `O(max(BATCH_SIZE, depth))`
- DB queries: `O((dirs + files) / BATCH_SIZE)` batch inserts

---

## Rationale for Decision

**Key Factors**:

1. **Performance**: Single-pass with batching is fundamentally more efficient
   - Reduces filesystem traversal from 2 passes to 1 pass
   - Better CPU cache utilization (temporal locality)
   - Fewer database roundtrips (batch operations)

2. **Natural Skip Semantics**: When a folder matches IGNORE pattern, all descendants are automatically skipped via `FileVisitResult.SKIP_SUBTREE` without additional database queries

3. **Memory Efficiency**: Bounded by batch size rather than total folder count
   - Two-phase: Must hold all folders in memory or database
   - Single-pass: Only holds current batch (e.g., 1000 items)

4. **Incremental Crawl Synergy**: Single-pass approach works better with timestamp-based change detection
   - Check folder timestamp → if unchanged, skip folder AND files together
   - Two-phase must still query database for all folders, even if unchanged

5. **Realistic Workloads**: Most crawls have many more files than folders
   - Two-phase: Processes files much later (poor cache behavior)
   - Single-pass: Files processed immediately after reading directory

**Benchmarks** (estimated):
- **Two-phase**: 10,000 files, 1,000 folders → ~60 seconds (2 passes, DB intermediate)
- **Single-pass**: 10,000 files, 1,000 folders → ~25 seconds (1 pass, batched writes)

## Consequences

### Positive

- Faster crawl times, especially for large file systems
- Lower memory usage (no folder queue)
- More predictable performance (fewer database queries)
- Better user experience (skip patterns work intuitively)
- Easier to add future optimizations (e.g., parallel tree walk)

### Negative

- More complex implementation (combined folder+file logic)
- Spring Batch step boundaries less clear (single large step)
- Restart granularity reduced (can't restart just "files step")
- Need careful transaction management for batch flushes

### Neutral

- Chunking step remains unchanged (still queries database for ANALYZE files)
- Configuration and pattern matching services unchanged
- Test coverage needs updating for new reader/processor structure

## Implementation Plan

1. Create `CombinedCrawlReader` that uses custom `FileVisitor`
2. Create `CombinedCrawlProcessor` that handles both folders and files
3. Create `CombinedCrawlWriter` with batch flushing logic
4. Update `FsCrawlJobBuilder` to use combined step
5. Add unit tests for batch flushing and skip semantics
6. Add integration tests comparing performance with two-phase
7. Update documentation (CLAUDE.md, guides)

## Migration Notes

**Backward Compatibility**: The change is transparent to configuration and pattern matching. Existing YAML configurations work unchanged.

**Database**: No schema changes required. Same tables (`fs_folder`, `fs_file`) used.

**Rollback Plan**: Keep two-phase implementation available via feature flag for one release cycle.

## References

- Files.walkFileTree() Java NIO documentation
- Spring Batch ItemReader/ItemWriter patterns
- JPA batching best practices
- [File System Crawl Job Implementation](../../guides/batch-jobs.md)

---

**Date**: 2025-11-07
**Author**: Sean
**Reviewers**: Claude Code (Sonnet 4.5)
**Status**: Accepted
