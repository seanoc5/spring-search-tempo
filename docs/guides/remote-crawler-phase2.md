# Remote Crawler Phase 2 (Control Plane)

Phase 2 introduces a server-driven API contract for lightweight remote crawler clients.
It now includes both control-plane APIs and ingest/session lifecycle APIs.

## Endpoints

- `GET /api/remote-crawl/bootstrap?host=<hostname>`
  - Returns enabled crawl configs assigned to that host (`targetHost = host` or `targetHost IS NULL`).
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
  - `BASE_URL` (default `http://localhost:8085`)
  - `USERNAME` / `PASSWORD` (default `admin` / `admin`)
  - `HOST_NAME` (default machine hostname)

## Deferred to Next Slice

- Dedicated remote ingest staging tables (instead of direct upsert into `fsfolder`/`fsfile`).
- Optional host/path display normalization for UI routes that currently expect native local paths.
