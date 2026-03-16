# Discovery Audit: `kdeyoga`

## Session

- discovery session id: `7583624`
- host: `kdeyoga`
- resulting crawl config id: `7864513`
- crawl config name: `DISCOVERY_KDEYOGA_7583624`
- crawl mode: `ENFORCE`
- start path: `/`

## What Happened

The discovery-to-crawl-config apply step did more than the single `crawl_config` row suggests.

It created:

- one `crawl_config` row
- `280,888` `FSFolder` placeholders attached to crawl config `7864513`

At the time of inspection, the `DiscoverySession` counters were still zero because the session had no explicit `assignedStatus` values. The apply step used effective status fallback from `suggestedStatus`.

## Current Counts

### `discovered_folder`

- total folders: `280,888`
- assigned statuses: `0`
- suggested statuses: `280,888`
- explicitly classified rows: `0`

Suggested-status distribution:

- `SKIP`: `206,659`
- `LOCATE`: `72,314`
- `INDEX`: `1,014`
- `ANALYZE`: `901`

### `fsfolder`

Rows seeded for crawl config `7864513`:

- `SKIP`: `206,659`
- `LOCATE`: `72,314`
- `INDEX`: `1,014`
- `ANALYZE`: `901`

All seeded rows were marked:

- `analysis_status_set_by = PATTERN`

No remote crawl tasks existed yet for this crawl config at inspection time.

## Pattern Payload Size

The generated crawl config was heavily skewed toward skip rules:

- `folderPatternsSkip`: `2795`
- `folderPatternsLocate`: `1`
- `folderPatternsIndex`: `44`
- `folderPatternsAnalyze`: `44`
- `folderPatternsSemantic`: null

This matches the current implementation, which compresses discovered paths into pattern arrays and writes them back to one broad config rooted at `/`.

## Why It Looked Incomplete

Three reasons:

1. The UI button text was misleading.
   - `Apply Suggested Template` actually triggered `apply-suggestions`, not template rebuild.

2. The session summary did not reflect effective statuses used during apply.
   - `classified_folders`, `skip_count`, `locate_count`, `index_count`, and `analyze_count` remained `0`.

3. The materialized result lives mostly in `FSFolder`, not in `CrawlConfig`.

## Concrete Examples

Early `INDEX` folders:

- `remote://kdeyoga/etc`
- `remote://kdeyoga/etc/alternatives`
- `remote://kdeyoga/etc/apt`

Early `SKIP` folders:

- `remote://kdeyoga/tmp`
- `remote://kdeyoga/tmp/hsperfdata_sean`
- `remote://kdeyoga/tmp/tmux-1000`

Depth `<= 2` sample distribution:

- `INDEX`: `165`
- `LOCATE`: `39`
- `SKIP`: `34`

## Code Fixes Applied

The repository now includes these fixes:

1. the stray compile-breaking `j` in `DiscoveryService` was removed
2. the classify page button text now says `Apply Suggested Statuses`
3. the crawl-config apply button now says `Apply Effective Statuses`
4. `applyToCrawlConfig()` now syncs session counters from the same effective statuses used for `FSFolder` seeding

## Remaining Gap

The already-applied session `7583624` still has stale counter values in the database until it is reapplied or manually corrected.

That is historical data drift, not a current code-path bug.
