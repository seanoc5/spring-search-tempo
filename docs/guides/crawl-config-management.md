# Crawl Configuration Management

This guide explains how to manage crawl configurations in the database and run crawls through the UI.

## Overview

Spring Search Tempo now supports managing crawl configurations through a database-backed UI, in addition to the YAML-based configuration in `application.yml`. This enables:

- **Dynamic configuration**: Create, edit, and manage crawl configs without restarting the app
- **Job run tracking**: View detailed statistics for every crawl execution
- **Per-entity tracking**: Track which job run created/updated each file/folder
- **UI management**: Manage crawls through a web interface

## Database Schema

### CrawlConfig Table

Stores crawl configuration that can be managed through the UI:

- **Basic info**: name, displayLabel, enabled status
- **Crawl settings**: startPaths, maxDepth, followLinks, parallel
- **Pattern storage**: JSON-encoded folder/file patterns for skip/locate/index/analyze

### JobRun Table

Tracks every job execution with detailed statistics:

- **Execution info**: jobName, startTime, finishTime, runStatus
- **File statistics**: filesDiscovered, filesNew, filesUpdated, filesSkipped, filesError
- **Folder statistics**: foldersDiscovered, foldersNew, foldersUpdated, foldersSkipped

### SaveableObject.jobRunId

All entities (FSFile, FSFolder, etc.) now have a `jobRunId` field that tracks which job run created or last updated them.

## Data Seeding

On application startup, if the `crawl_config` table is empty, the system automatically seeds it with configurations from `application.yml`.

### How It Works

1. **CrawlConfigDataSeeder** runs on startup (`@PostConstruct`)
2. Checks if `crawl_config` table is empty
3. If empty, reads all crawl definitions from `application.yml`
4. Converts each `CrawlDefinition` to a `CrawlConfig` entity
5. Persists to database

### Seed Location

**File**: `src/main/kotlin/.../base/service/CrawlConfigDataSeeder.kt`

**Key Features**:
- Only runs if table is empty (safe to restart)
- Converts pattern lists to JSON arrays for storage
- Preserves all settings from YAML config
- Logs seeding progress

### Pattern Storage Format

Patterns (folder/file skip/locate/index/analyze) are stored as JSON arrays:

```json
[".*\\.git/.*", ".*\\.gradle/.*", ".*\\.idea/.*"]
```

The `CrawlConfigConverter` service handles conversion between JSON strings and Kotlin lists.

## Using the UI

### Accessing Crawl Management

Navigate to: `http://localhost:8082/crawlConfigs`

From the home page, click "Crawl Configurations" in the "Crawl Management" section.

### Listing Configurations

The main list view (`/crawlConfigs`) shows:
- All crawl configurations
- Enable/disable status
- Start paths
- "Run Crawl" button for each config

### Viewing Configuration Details

Click "View" on any config to see:
- Complete configuration details (paths, depth, patterns)
- Job run history for that specific configuration
- Statistics for each run

### Running a Crawl

Click "Run Crawl" button to:
1. Create a new `JobRun` record
2. Build a Spring Batch job from the database config
3. Execute the crawl with job run tracking
4. Update statistics as the job runs

### Viewing Job Run History

Navigate to `/crawlConfigs/jobRuns` to see:
- All job runs across all configurations
- Execution time and duration
- Detailed statistics (files/folders discovered, new, updated, skipped)
- Status (RUNNING, COMPLETED, FAILED, CANCELLED)

## Job Execution Flow

When you click "Run Crawl" from the UI:

```
1. Controller receives request
   ↓
2. Convert database CrawlConfig → CrawlDefinition
   (CrawlConfigConverter.toDefinition)
   ↓
3. Build Spring Batch Job dynamically
   (FsCrawlJobBuilder.buildJob)
   ↓
4. Add job parameters (crawlConfigId, timestamp)
   ↓
5. Launch job asynchronously
   (JobLauncher.run)
   ↓
6. JobRunTrackingListener creates JobRun record
   ↓
7. Job executes crawl
   ↓
8. Writer sets jobRunId on all entities
   ↓
9. Listener updates statistics in real-time
   ↓
10. Job completes, final stats saved
```

## Key Services

### DatabaseCrawlConfigService

CRUD operations for crawl configurations:
- `findAll()` - List all configs
- `findAllEnabled()` - List enabled configs only
- `get(id)` - Get by ID
- `create()` / `update()` / `delete()` - Modify configs

### JobRunService

Manages job execution tracking:
- `startJobRun(crawlConfigId, jobName)` - Create new run
- `updateJobRunStats(...)` - Update statistics during execution
- `completeJobRun(...)` - Mark run as complete/failed
- `findByCrawlConfigId()` - Get runs for a config

### CrawlConfigConverter

Converts between formats:
- `toDefinition(CrawlConfigDTO)` → `CrawlDefinition`
  - Parses JSON pattern strings
  - Reconstructs PatternSet objects
  - Used when building jobs from database configs

- `toJsonArray(List<String>)` → JSON string
  - Used when saving configs to database

## Incremental Crawls

Every crawl execution is tracked separately with its own JobRun record. The system tracks:

- **New items**: First time discovered (file.id == null before save)
- **Updated items**: Already existed, but modified (file.id != null, updated timestamp)
- **Skipped items**: Matched SKIP patterns
- **Total discovered**: All items encountered

This enables:
- Tracking crawl progress over time
- Identifying which items were added/modified in each run
- Understanding crawl performance and coverage

## Best Practices

### Initial Setup

1. Start the application
2. Check logs for "Seeded X crawl configurations"
3. Navigate to `/crawlConfigs` to verify seeding
4. Disable unwanted configs (e.g., SYSTEM_ROOT)
5. Run your first crawl

### Managing Configurations

- **Start small**: Run smaller crawls (USER_DOCUMENTS) before large ones
- **Test patterns**: Create a test config with a small directory first
- **Monitor statistics**: Check job run history to tune configurations
- **Disable unused**: Disable configs you don't need to avoid clutter

### Troubleshooting

**Seed doesn't run**:
- Check if table already has data (seeder only runs if empty)
- Look for errors in application startup logs
- Verify `application.yml` has valid crawl definitions

**Job doesn't start**:
- Check configuration is enabled
- Verify start paths exist on filesystem
- Check application logs for errors

**Statistics not updating**:
- Verify `jobRunId` is being set on entities
- Check that `JobRunTrackingListener` is registered
- Look for errors in job execution logs

## API Endpoints

If you prefer REST API over UI:

```
GET    /api/crawl-configs              - List all configurations
GET    /api/crawl-configs/{id}         - Get configuration details
POST   /api/crawl-configs              - Create new configuration
PUT    /api/crawl-configs/{id}         - Update configuration
DELETE /api/crawl-configs/{id}         - Delete configuration

GET    /api/job-runs                   - List all job runs
GET    /api/job-runs/{id}              - Get job run details
GET    /api/job-runs?crawlConfigId={} - Filter by configuration
```

(Note: REST endpoints to be implemented based on need)

## Future Enhancements

Planned improvements:

- **REST API**: Full REST API for programmatic access
- **Scheduling**: Cron-based automatic crawl execution
- **Notifications**: Email/webhook on job completion
- **Advanced filtering**: Search job runs by date range, status, etc.
- **Export/Import**: Backup and restore configurations
- **Pattern editor**: UI-based pattern editing instead of JSON

---

**Related Documentation**:
- [Architecture Overview](../architecture/module-design.md)
- [Batch Jobs Guide](batch-jobs.md)
- [Testing Guide](testing.md)
