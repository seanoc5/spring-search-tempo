# Remote Crawler GitHub Publishing

This repo includes a GitHub Actions workflow that publishes the remote crawler CLI JAR as a GitHub Release asset.

## Workflow

- File: `.github/workflows/publish-remote-crawler.yml`
- Trigger options:
  - Push a tag matching `remote-crawler-v*`
  - Manual run via **Actions -> Publish Remote Crawler -> Run workflow**

## Publish by Tag (recommended)

```bash
# from repo root
git tag remote-crawler-v0.1.1
git push origin remote-crawler-v0.1.1
```

Or use the helper script:

```bash
scripts/remote/release-remote-crawler.sh 0.1.1
```

What happens automatically:

1. Builds `remote-crawler-cli` fat JAR with release version from the tag (`0.1.1`)
2. Produces:
   - `remote-crawler-0.1.1.jar`
   - `remote-crawler-0.1.1.jar.sha256`
3. Creates/updates GitHub Release `remote-crawler-v0.1.1`
4. Uploads both files as release assets

## Publish Manually

Use `workflow_dispatch` with input version (example `0.1.2`).

The workflow creates release tag `remote-crawler-v<version>` and publishes matching assets.

## Download on Windows Client

From the release page, download `remote-crawler-<version>.jar` to the Win11 host.

Example run:

```powershell
java -jar .\remote-crawler-0.1.1.jar onboard -s http://minti9:8082 -u admin -p admin -H winbook3
```

## Version source

`remote-crawler-cli/build.gradle.kts` now supports:

- `-PremoteCrawlerVersion=<version>` for release builds
- fallback default when property is not provided
