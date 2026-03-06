#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  scripts/remote/release-remote-crawler.sh <version> [options]

Example:
  scripts/remote/release-remote-crawler.sh 0.2.2

Options:
  --no-push      Create local tag only (do not push branch/tag)
  --no-wait      Do not wait for GitHub release/assets after push
  --skip-build   Skip local jar build/checksum generation
  -h, --help     Show this help
EOF
}

require_cmd() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "ERROR: Required command not found: $cmd" >&2
    exit 1
  fi
}

VERSION="${1:-}"
if [[ -z "$VERSION" || "$VERSION" == "-h" || "$VERSION" == "--help" ]]; then
  usage
  exit 0
fi
shift || true

PUSH=1
WAIT_FOR_RELEASE=1
SKIP_BUILD=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-push)
      PUSH=0
      ;;
    --no-wait)
      WAIT_FOR_RELEASE=0
      ;;
    --skip-build)
      SKIP_BUILD=1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "ERROR: Unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
  shift
done

if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z]+)?$ ]]; then
  echo "ERROR: Version must look like 0.2.2 (or 0.2.2-rc1)." >&2
  exit 1
fi

require_cmd git
require_cmd gh
require_cmd sha256sum

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

TAG="remote-crawler-v${VERSION}"
JAR_NAME="remote-crawler-${VERSION}.jar"
JAR_PATH="remote-crawler-cli/build/libs/${JAR_NAME}"
SHA_PATH="${JAR_PATH}.sha256"

if [[ -n "$(git status --porcelain)" ]]; then
  echo "ERROR: Working tree is not clean. Commit/stash first." >&2
  exit 1
fi

if git rev-parse -q --verify "refs/tags/${TAG}" >/dev/null; then
  echo "ERROR: Local tag already exists: ${TAG}" >&2
  exit 1
fi

if git ls-remote --tags origin "refs/tags/${TAG}" | grep -q "${TAG}$"; then
  echo "ERROR: Remote tag already exists on origin: ${TAG}" >&2
  exit 1
fi

echo "Release version: ${VERSION}"
echo "Release tag:     ${TAG}"
echo "Branch:          $(git rev-parse --abbrev-ref HEAD)"

if [[ "$SKIP_BUILD" -eq 0 ]]; then
  echo "Building remote crawler jar..."
  ./gradlew -q :remote-crawler-cli:buildCli -PremoteCrawlerVersion="${VERSION}"

  if [[ ! -f "$JAR_PATH" ]]; then
    echo "ERROR: Expected jar not found: ${JAR_PATH}" >&2
    exit 1
  fi

  sha256sum "$JAR_PATH" > "$SHA_PATH"
  echo "Built:    ${JAR_PATH}"
  echo "Checksum: ${SHA_PATH}"
fi

echo "Creating annotated tag: ${TAG}"
git tag -a "${TAG}" -m "Remote crawler ${VERSION}"

if [[ "$PUSH" -eq 1 ]]; then
  echo "Pushing current branch..."
  git push
  echo "Pushing tag..."
  git push origin "${TAG}"
fi

if [[ "$PUSH" -eq 1 && "$WAIT_FOR_RELEASE" -eq 1 ]]; then
  echo "Waiting for GitHub Release and assets (up to 10 minutes)..."
  for _ in {1..40}; do
    if gh release view "${TAG}" >/dev/null 2>&1; then
      if gh release download "${TAG}" -p "${JAR_NAME}" -D /tmp --clobber >/dev/null 2>&1; then
        echo "Release asset is available: ${JAR_NAME}"
        break
      fi
    fi
    sleep 15
  done
fi

cat <<EOF
Done.

Download command:
  gh release download ${TAG} -p "${JAR_NAME}"
EOF
