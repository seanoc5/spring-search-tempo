#!/bin/bash
# =============================================================================
# release.sh - Build and release the Remote Crawler CLI
# =============================================================================
#
# Usage:
#   ./release.sh <version>           # Build, tag, and create GitHub release
#   ./release.sh <version> --build-only  # Just build, no git/GitHub
#   ./release.sh <version> --no-push     # Build and tag, but don't push
#
# Examples:
#   ./release.sh 0.5.4
#   ./release.sh 0.5.4 --build-only
#
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Parse arguments
VERSION=""
BUILD_ONLY=false
NO_PUSH=false

for arg in "$@"; do
    case $arg in
        --build-only)
            BUILD_ONLY=true
            ;;
        --no-push)
            NO_PUSH=true
            ;;
        -*)
            echo -e "${RED}Unknown option: $arg${NC}"
            exit 1
            ;;
        *)
            if [ -z "$VERSION" ]; then
                VERSION="$arg"
            fi
            ;;
    esac
done

if [ -z "$VERSION" ]; then
    echo -e "${RED}Usage: $0 <version> [--build-only] [--no-push]${NC}"
    echo ""
    echo "Examples:"
    echo "  $0 0.5.4              # Full release"
    echo "  $0 0.5.4 --build-only # Just build JAR"
    echo "  $0 0.5.4 --no-push    # Build + tag locally"
    exit 1
fi

# Validate version format (semver-ish)
if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9]+)?$ ]]; then
    echo -e "${RED}Invalid version format: $VERSION${NC}"
    echo "Expected format: X.Y.Z or X.Y.Z-suffix (e.g., 0.5.4, 1.0.0-beta)"
    exit 1
fi

JAR_NAME="remote-crawler-${VERSION}.jar"
TAG_NAME="remote-crawler-v${VERSION}"

echo "========================================"
echo "Remote Crawler CLI Release"
echo "========================================"
echo "Version:    $VERSION"
echo "JAR:        $JAR_NAME"
echo "Tag:        $TAG_NAME"
echo "Build only: $BUILD_ONLY"
echo "No push:    $NO_PUSH"
echo "========================================"
echo ""

# Step 1: Build the fat JAR
echo -e "${YELLOW}>>> Step 1: Building fat JAR...${NC}"
cd "$SCRIPT_DIR"
../gradlew shadowJar -PremoteCrawlerVersion="$VERSION" --quiet

if [ ! -f "build/libs/$JAR_NAME" ]; then
    echo -e "${RED}Build failed: build/libs/$JAR_NAME not found${NC}"
    exit 1
fi

JAR_SIZE=$(du -h "build/libs/$JAR_NAME" | cut -f1)
echo -e "${GREEN}    Built: build/libs/$JAR_NAME ($JAR_SIZE)${NC}"

# Step 2: Copy to project root
echo -e "${YELLOW}>>> Step 2: Copying to project root...${NC}"
cp "build/libs/$JAR_NAME" "$PROJECT_ROOT/"
cd "$PROJECT_ROOT"
sha256sum "$JAR_NAME" > "${JAR_NAME}.sha256"
echo -e "${GREEN}    Copied: $JAR_NAME${NC}"
echo -e "${GREEN}    Checksum: ${JAR_NAME}.sha256${NC}"

if [ "$BUILD_ONLY" = true ]; then
    echo ""
    echo -e "${GREEN}========================================"
    echo "Build complete (--build-only)"
    echo "========================================"
    echo "JAR: $PROJECT_ROOT/$JAR_NAME"
    echo "SHA: $PROJECT_ROOT/${JAR_NAME}.sha256"
    echo "========================================${NC}"
    exit 0
fi

# Step 3: Check for uncommitted changes
echo -e "${YELLOW}>>> Step 3: Checking git status...${NC}"
if [ -n "$(git status --porcelain)" ]; then
    echo -e "${YELLOW}    Warning: You have uncommitted changes${NC}"
    git status --short
    echo ""
    read -p "Continue anyway? (y/N) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "Aborted."
        exit 1
    fi
fi

# Step 4: Check if tag already exists
if git rev-parse "$TAG_NAME" >/dev/null 2>&1; then
    echo -e "${RED}Tag $TAG_NAME already exists!${NC}"
    echo "Delete it first with: git tag -d $TAG_NAME && git push origin :refs/tags/$TAG_NAME"
    exit 1
fi

# Step 5: Create tag
echo -e "${YELLOW}>>> Step 4: Creating git tag...${NC}"
git tag -a "$TAG_NAME" -m "Remote Crawler CLI v${VERSION}"
echo -e "${GREEN}    Created tag: $TAG_NAME${NC}"

if [ "$NO_PUSH" = true ]; then
    echo ""
    echo -e "${GREEN}========================================"
    echo "Build and tag complete (--no-push)"
    echo "========================================"
    echo "JAR: $PROJECT_ROOT/$JAR_NAME"
    echo "Tag: $TAG_NAME (local only)"
    echo ""
    echo "To push later:"
    echo "  git push origin $TAG_NAME"
    echo "========================================${NC}"
    exit 0
fi

# Step 6: Push tag
echo -e "${YELLOW}>>> Step 5: Pushing tag to origin...${NC}"
git push origin "$TAG_NAME"
echo -e "${GREEN}    Pushed: $TAG_NAME${NC}"

# Step 7: Create GitHub release (if gh is available)
if command -v gh &> /dev/null; then
    echo -e "${YELLOW}>>> Step 6: Creating GitHub release...${NC}"

    # Generate release notes
    PREV_TAG=$(git tag -l "remote-crawler-v*" --sort=-version:refname | head -2 | tail -1)
    if [ -n "$PREV_TAG" ] && [ "$PREV_TAG" != "$TAG_NAME" ]; then
        CHANGES=$(git log --oneline "$PREV_TAG".."$TAG_NAME" -- remote-crawler-cli/ 2>/dev/null | head -10)
    else
        CHANGES="Initial release"
    fi

    RELEASE_NOTES="## Remote Crawler CLI v${VERSION}

### Changes
${CHANGES:-No changes recorded}

### Installation
\`\`\`bash
# Download
curl -LO https://github.com/seanoc5/spring-search-tempo/releases/download/${TAG_NAME}/${JAR_NAME}

# Verify checksum
curl -LO https://github.com/seanoc5/spring-search-tempo/releases/download/${TAG_NAME}/${JAR_NAME}.sha256
sha256sum -c ${JAR_NAME}.sha256

# Run
java -jar ${JAR_NAME} --help
\`\`\`
"

    gh release create "$TAG_NAME" \
        "$JAR_NAME" \
        "${JAR_NAME}.sha256" \
        --title "Remote Crawler v${VERSION}" \
        --notes "$RELEASE_NOTES"

    echo -e "${GREEN}    Created GitHub release: $TAG_NAME${NC}"
else
    echo -e "${YELLOW}>>> Step 6: Skipping GitHub release (gh CLI not installed)${NC}"
    echo "    Install with: sudo apt install gh"
    echo "    Then run: gh release create $TAG_NAME $JAR_NAME ${JAR_NAME}.sha256"
fi

echo ""
echo -e "${GREEN}========================================"
echo "Release complete!"
echo "========================================"
echo "Version: $VERSION"
echo "Tag:     $TAG_NAME"
echo "JAR:     $PROJECT_ROOT/$JAR_NAME"
echo ""
echo "Download URL:"
echo "  https://github.com/seanoc5/spring-search-tempo/releases/download/${TAG_NAME}/${JAR_NAME}"
echo "========================================${NC}"
