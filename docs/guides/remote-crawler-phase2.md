# Remote Crawler Phase 2 (Control Plane)

Phase 2 introduces a server-driven API contract for lightweight remote crawler clients.
It now includes both control-plane APIs and ingest/session lifecycle APIs.

## Endpoints

- `GET /api/remote-crawl/bootstrap?host=<hostname>`
  - Returns enabled crawl configs assigned to the requested host (`sourceHost` case-insensitive match), plus host-agnostic configs with blank `sourceHost`.
  - Includes effective folder/file pattern sets (defaults + config-specific patterns).
  - Includes effective runtime settings (`startPaths`, `maxDepth`, `followLinks`, `parallel`).

- `POST /api/remote-crawl/classify`
  - Classifies folder/file paths using the same pattern logic as server-side crawl jobs.
  - Returns `analysisStatus` plus processing instructions:
    - `persistMetadata`
    - `extractText`
    - `runNlp`
    - `runEmbedding`

- `POST /api/remote-crawl/session/start`
  - Creates a `JobRun`-backed remote session for a host + crawl config.

- `POST /api/remote-crawl/session/heartbeat`
  - Updates session heartbeat/progress and optional current step.

- `POST /api/remote-crawl/session/ingest`
  - Upserts folder/file metadata (and optional extracted text) into `fsfolder` / `fsfile`.
  - Associates ingested rows to the remote session `jobRunId`.

- `POST /api/remote-crawl/session/complete`
  - Completes the session with `RunStatus` and optional final totals.

- `POST /api/remote-crawl/session/tasks/enqueue-folders`
  - Classifies folder batches and enqueues non-SKIP folders as server-assigned tasks.
  - Also persists the folder metadata via ingest.

- `POST /api/remote-crawl/session/tasks/next`
  - Claims next pending folder tasks for the session.
  - Returns `claimToken` and per-task processing instructions.

- `POST /api/remote-crawl/session/tasks/ack`
  - Acknowledges claimed tasks as `COMPLETED`, `SKIPPED`, `FAILED`, or `RETRY`.

- `POST /api/remote-crawl/session/tasks/status`
  - Returns queue counts by status (`PENDING`, `CLAIMED`, `COMPLETED`, etc).

## Request Shape (`/classify`)

```json
{
  "host": "win11-devbox",
  "crawlConfigId": 12345,
  "folders": [
    { "path": "C:\\Users\\alice\\Documents", "parentStatus": "LOCATE" }
  ],
  "files": [
    { "path": "C:\\Users\\alice\\Documents\\notes.docx", "parentFolderStatus": "ANALYZE" }
  ]
}
```

## What This Enables

- Remote clients can stay thin: crawl filesystem + ask server for policy decisions.
- Analysis policy remains centralized on server and consistent with local batch behavior.
- Server can change patterns/config without redeploying clients.

## URI Strategy for Multi-Host Safety

- Remote ingest stores canonical URIs as:
  - `remote://<normalized-host>/<normalized-path>`
- This avoids global `uri` collisions across hosts in the current schema.
- It also lets local crawler records and remote-ingested records coexist.

## Queue Persistence

- Server task queue is persisted in `remote_crawl_task`.
- Migration: `026-create-remote-crawl-task-table.sql`.
- This allows queue continuity across app restarts (session/task state is DB-backed).

## Smoke Script

- Script: `scripts/remote/remote-crawler-smoke.sh`
- Requires: `curl`, `jq`
- Example:
  - `CRAWL_CONFIG_ID=123 scripts/remote/remote-crawler-smoke.sh /home/sean/Documents /home/sean/Downloads`
- Optional env:
  - `BASE_URL` (default `https://localhost`)
  - `USERNAME` / `PASSWORD` (default `admin` / `admin`)
  - `HOST_NAME` (default machine hostname)

## Deferred to Next Slice

- Dedicated remote ingest staging tables (instead of direct upsert into `fsfolder`/`fsfile`).
- Optional host/path display normalization for UI routes that currently expect native local paths.

## Publishing Remote Client

- GitHub release automation guide:
  - `docs/guides/remote-crawler-github-publishing.md`

### More GH release automation
- `docs/guides/remote-crawler-github-publishing.md`


## Download remote client
gh release download remote-crawler-v0.2.1


## Sample Usage
Copy to winbook3 and run:

### Test connection
java -jar remote-crawler-0.1.0.jar -s https://minti9 test

### Dry run - short mode (explicit matches only)
java -jar remote-crawler-0.1.0.jar -s https://minti9 dry-run -c <CONFIG_ID>

### Dry run - detailed mode (all folders)
java -jar remote-crawler-0.1.0.jar -s https://minti9 dry-run -c <CONFIG_ID> --detailed

### Filter to INDEX folders only
java -jar remote-crawler-0.1.0.jar -s https://minti9 dry-run -c <CONFIG_ID> --status INDEX

### Export to JSON
java -jar remote-crawler-0.1.0.jar -s https://minti9 dry-run -c <CONFIG_ID> --detailed -o plan.json

You'll need the crawl config ID - if you've already applied the discovery session 3757671 to a config, use that ID. Otherwise, you can first apply the classifications in the UI at                                       
https://localhost/discovery/3757671/classify to create a config.

Note: plain `http://` is still supported for migration/testing, but the CLI now emits a loud warning when insecure transport is used.

## Windows Scheduled Task Setup

See the dedicated guide: **[Remote Crawler - Windows Setup Guide](remote-crawler-windows-setup.md)**

Covers:
- Installation to `C:\Tempo\remote-crawler\`
- PowerShell scripts for running and scheduling (`scripts/windows/`)
- Configurable intervals (default: every 4 hours)
- Credential management via environment variables
- Troubleshooting common issues

TLS guide:
- **[Remote Crawler TLS Setup (LAN + self-signed)](remote-crawler-tls-lan-setup.md)**
