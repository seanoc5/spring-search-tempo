# Remote Crawler Guide

The Remote Crawler CLI enables distributed crawling from remote hosts (Windows, Linux) that send file metadata and extracted text to a central Spring Search Tempo server.

## Overview

**Architecture**: Thin client, smart server.
- Client crawls local filesystem
- Server provides classification rules, pattern matching, processing policies
- Client sends metadata + text to server for indexing

**Why remote crawling?**
- Index Windows workstations from a Linux server
- Distributed crawling across multiple hosts
- Centralized policy management

## Quick Start

### 1. Download the CLI

```bash
# Using GitHub CLI
gh release download remote-crawler-v0.5.3 -R seanoc5/spring-search-tempo

# Or curl
curl -LO https://github.com/seanoc5/spring-search-tempo/releases/download/remote-crawler-v0.5.3/remote-crawler-0.5.3.jar
```

### 2. Test connectivity

```bash
java -jar remote-crawler-0.5.3.jar -s https://your-server -u admin -p password test
```

### 3. Run a crawl

```bash
java -jar remote-crawler-0.5.3.jar -s https://your-server -u admin -p password crawl
```

## CLI Commands

| Command | Description |
|---------|-------------|
| `test` | Test server connectivity and authentication |
| `status` | Show assigned crawl configs for this host |
| `dry-run -c <ID>` | Preview what would be crawled (no changes) |
| `crawl` | Execute crawl for all assigned configs |
| `onboard -H <name>` | Initial discovery for a new host |

### Dry Run Examples

```bash
# Short mode - explicit pattern matches only
java -jar remote-crawler-0.5.3.jar -s https://minti9 dry-run -c <CONFIG_ID>

# Detailed mode - all folders
java -jar remote-crawler-0.5.3.jar -s https://minti9 dry-run -c <CONFIG_ID> --detailed

# Filter to specific status
java -jar remote-crawler-0.5.3.jar -s https://minti9 dry-run -c <CONFIG_ID> --status INDEX

# Export to JSON
java -jar remote-crawler-0.5.3.jar -s https://minti9 dry-run -c <CONFIG_ID> --detailed -o plan.json
```

## Server API Endpoints

The CLI communicates with these REST endpoints:

### Bootstrap & Classification

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/remote-crawl/bootstrap?host=<hostname>` | GET | Returns assigned crawl configs, patterns, settings |
| `/api/remote-crawl/classify` | POST | Classifies paths using server-side pattern logic |

### Session Lifecycle

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/remote-crawl/session/start` | POST | Creates a JobRun-backed remote session |
| `/api/remote-crawl/session/heartbeat` | POST | Updates session progress |
| `/api/remote-crawl/session/ingest` | POST | Upserts folder/file metadata |
| `/api/remote-crawl/session/complete` | POST | Marks session complete |

### Task Queue

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/remote-crawl/session/tasks/enqueue-folders` | POST | Queues folders for processing |
| `/api/remote-crawl/session/tasks/next` | POST | Claims next pending tasks |
| `/api/remote-crawl/session/tasks/ack` | POST | Acknowledges task completion |
| `/api/remote-crawl/session/tasks/status` | POST | Returns queue counts by status |

## URI Strategy

Remote ingest stores URIs as:
```
remote://<normalized-host>/<normalized-path>
```

This avoids URI collisions across hosts and allows local and remote records to coexist.

## Publishing Releases

### Via release.sh (recommended)

```bash
cd remote-crawler-cli
./release.sh 0.5.4           # Build, tag, and create GitHub release
./release.sh 0.5.4 --build-only  # Just build, no git/GitHub
./release.sh 0.5.4 --no-push     # Build and tag locally
```

### Via GitHub Actions

Push a tag to trigger the workflow:

```bash
git tag remote-crawler-v0.5.4
git push origin remote-crawler-v0.5.4
```

The workflow (`.github/workflows/publish-remote-crawler.yml`):
1. Builds the fat JAR
2. Creates SHA256 checksum
3. Creates/updates GitHub release
4. Uploads both files as assets

**Note**: Release version must match the app version in `gradle.properties`.

## Windows Scheduled Task Setup

See: **[Remote Crawler - Windows Setup Guide](remote-crawler-windows-setup.md)**

Covers:
- Installation to `C:\Tempo\remote-crawler\`
- PowerShell scripts for running and scheduling
- Configurable intervals (default: every 4 hours)
- Credential management

## TLS Setup

For self-signed certificates or private CAs:

See: **[Remote Crawler TLS Setup (LAN + self-signed)](remote-crawler-tls-lan-setup.md)**

## Testing (Server-Side Smoke Test)

```bash
# Requires curl, jq
CRAWL_CONFIG_ID=123 scripts/remote/remote-crawler-smoke.sh /home/sean/Documents

# With custom server
BASE_URL=https://myserver USERNAME=admin PASSWORD=secret \
  CRAWL_CONFIG_ID=123 scripts/remote/remote-crawler-smoke.sh /path/to/crawl
```

## Security Notes

- HTTPS is strongly recommended; HTTP triggers CLI warnings
- Credentials can be passed via:
  1. Command-line args (`-u`, `-p`)
  2. Environment variables (`TEMPO_CRAWLER_USERNAME`, `TEMPO_CRAWLER_PASSWORD`)
  3. Config file (`config.json`)

---

**Related Guides:**
- [Windows Setup](remote-crawler-windows-setup.md)
- [TLS/LAN Setup](remote-crawler-tls-lan-setup.md)
- [Batch Observability](batch-observability.md)
