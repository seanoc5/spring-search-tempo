# Remote Crawler GitHub Publishing

This repo includes a GitHub Actions workflow that publishes the remote crawler CLI JAR as a GitHub Release asset.

## Workflow

- File: `.github/workflows/publish-remote-crawler.yml`
- Trigger options:
  - Push a tag matching `remote-crawler-v*`
  - Manual run via **Actions -> Publish Remote Crawler -> Run workflow**
- Release versions must match the root app version in `build.gradle.kts`.

## Publish by Tag (recommended)

```bash
# from repo root
export rcv=0.5.3
git tag remote-crawler-v$rcv
git push origin remote-crawler-v$rcv
```

Or use the helper script:

```bash
scripts/remote/release-remote-crawler.sh 0.5.3
```

The helper script and GitHub workflow reject a remote-crawler version that does not match the app version.

What happens automatically:

1. Builds `remote-crawler-cli` fat JAR with release version from the tag (`0.5.3`)
2. Produces:
   - `remote-crawler-0.5.3.jar`
   - `remote-crawler-0.5.3.jar.sha256`
3. Creates/updates GitHub Release `remote-crawler-v0.5.3`
4. Uploads both files as release assets

## Publish Manually

Use `workflow_dispatch` with input version (example `0.5.3`).

The workflow creates release tag `remote-crawler-v<version>` and publishes matching assets.

## Download on Windows Client

From the release page, download `remote-crawler-<version>.jar` to the Win11 host.

Example run:

```powershell
java -jar .\remote-crawler-0.5.3.jar onboard -s https://minti9 -u admin -p admin -H winbook3
```

## Version source

`remote-crawler-cli/build.gradle.kts` now supports:

- `-PremoteCrawlerVersion=<version>` for release builds
- fallback to the root app version when the property is not provided
