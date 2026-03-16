# SourceHost And Crawl Session Plan

## Current State

The codebase currently uses `sourceHost` as a string on multiple entities:

- `CrawlConfig`
- `FSFolder`
- `FSFile`
- `ContentChunk`
- `EmailMessage` and related DTOs
- `DiscoverySession`
- `RemoteCrawlTask`
- `CrawlDiscoveryRun`
- `CrawlDiscoveryFolderObservation`

User ownership is modeled separately via `UserSourceHost`, which is a join from `SpringUser` to a raw `sourceHost` string.

Remote crawling already has two partial session concepts:

- `job_run` as the runtime session in `RemoteCrawlSessionService`
- `remote_crawl_task` as the selected-folder queue in `RemoteCrawlTaskService`

Discovery-mode crawling also has observation tables:

- `crawl_discovery_run`
- `crawl_discovery_folder_obs`
- `crawl_discovery_file_sample`

## Problems

1. Host identity is duplicated as an untyped string.
2. Ownership is attached to a string, not a first-class host entity.
3. Crawl session state is split across `job_run`, `remote_crawl_task`, and discovery observation tables.
4. There is no explicit persistent record for "why this folder was selected for this crawl".
5. `CrawlConfig` currently mixes policy definition and applied runtime state concerns.

## Target Model

### `SourceHost`

Create a first-class `SourceHost` entity.

Suggested fields:

- `id`
- `hostKey`
- `displayName`
- `normalizedHost`
- `hostType`
- `osType`
- `agentVersion`
- `lastSeenAt`
- `enabled`
- `archived`

Rules:

- `normalizedHost` should be unique.
- Existing string `sourceHost` values should be backfilled into `SourceHost`.

### `SourceHostOwnership`

Replace `UserSourceHost` with a join to `SourceHost`.

Suggested fields:

- `id`
- `springUser`
- `sourceHost`
- `role`
- `dateCreated`

This keeps user access control while removing string-based ownership joins.

### `DiscoverySession`

Change `DiscoverySession.host` from a raw string to a `ManyToOne` reference to `SourceHost`.

Keep discovery-specific fields:

- roots uploaded during onboarding
- folder counts
- suggested profile
- apply status
- timestamps

This remains the onboarding history record.

### `CrawlConfig`

Change `CrawlConfig.sourceHost` from a raw string to `sourceHostId`.

Keep `CrawlConfig` as policy only:

- start paths
- file/folder patterns
- match priority
- smart crawl thresholds
- discovery defaults

Behavior rules:

- configs for a host are ordered deepest path first, broadest last
- first match wins
- no merging across overlapping configs at runtime unless explicitly designed later

Suggested addition:

- `pathScope` or normalized primary root path to make ordering explicit
- `sortOrder` for deterministic override behavior

### `HostCrawlSession`

Create an explicit host-level crawl session entity instead of relying on `job_run` as the only durable concept.

Suggested fields:

- `id`
- `sourceHost`
- `crawlConfig`
- `jobRunId`
- `sessionType` (`FULL`, `SMART`, `USER_REQUESTED`, `DISCOVERY_REVIEW`, `RETRY`)
- `selectionPolicy`
- `selectionReasonSummary`
- `startedAt`
- `completedAt`
- `status`

`job_run` can remain the batch/runtime execution record, but it should be linked from this entity rather than standing in for it.

### `HostCrawlSessionFolder`

Create a per-folder selection table for each session.

Suggested fields:

- `id`
- `hostCrawlSession`
- `fsFolder`
- `selectedPath`
- `analysisStatus`
- `selectionReason`
- `selectionReasonDetail`
- `selectedAt`
- `claimedAt`
- `completedAt`
- `resultStatus`
- `filesSeen`
- `filesChanged`
- `errorMessage`

This replaces the implicit audit currently split between:

- `remote_crawl_task`
- `job_run`
- folder timestamps

`remote_crawl_task` can either be retired or narrowed to an internal queue implementation behind this table.

## FSFolder Direction

`FSFolder` should carry resolved effective behavior for scheduling and remote crawl handoff.

That means storing inherited values from the winning config, not just the latest observation:

- effective `analysisStatus`
- effective crawl policy identity
- `crawlConfigId` of the winning config
- recrawl scheduling timestamps
- temperature/stability fields

This matches the requirement that the server should be able to select folders for recrawl without recalculating the full config stack every time.

## Migration Sequence

1. Add `SourceHost` table and backfill distinct existing `sourceHost` strings.
2. Add nullable `source_host_id` foreign keys beside current string columns.
3. Backfill entity references from existing strings.
4. Add `SourceHostOwnership` and migrate `UserSourceHost`.
5. Update services/repositories to read via `sourceHostId`.
6. Add `HostCrawlSession` and `HostCrawlSessionFolder`.
7. Make `RemoteCrawlSessionService` create `HostCrawlSession`.
8. Make `RemoteCrawlTaskService.enqueueFolders()` persist selected-folder rows with reason metadata.
9. Migrate or wrap `remote_crawl_task` behind `HostCrawlSessionFolder`.
10. Remove old string-only host joins after data verification.

## Existing Pieces To Reuse

These should be evolved, not thrown away:

- `RemoteCrawlSessionService`
- `RemoteCrawlTaskService`
- `crawl_discovery_run`
- `crawl_discovery_folder_obs`
- smart crawl fields on `FSFolder`

## Recommendation

Do not replace `UserSourceHost` directly with a bare `SourceHost`.

Do this instead:

- introduce `SourceHost`
- rename `UserSourceHost` to `SourceHostOwnership` conceptually
- make all crawl, discovery, and scheduling records point to `SourceHost`
- keep user access as a separate join

That gives one canonical host identity while preserving ownership and auditability.
