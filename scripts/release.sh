#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

usage() {
  cat <<'EOF'
Usage:
  scripts/release.sh [--part patch|minor|major | --version X.Y.Z] [options]

Options:
  --part <patch|minor|major>   Semver bump part (mutually exclusive with --version)
  --version <X.Y.Z>            Explicit release version (mutually exclusive with --part)
  --remote <name>              Git remote for push/tag checks (default: origin)
  --skip-checks                Skip Gradle verification before tagging
  --allow-dirty                Allow running with a dirty working tree
  --push                       Push commit and tag to remote
  --create-gh-release          Create GitHub release via gh CLI after tagging
  --dry-run                    Print planned actions without changing files
  -h, --help                   Show this help

Examples:
  scripts/release.sh --part patch --push
  scripts/release.sh --version 1.2.0 --push --create-gh-release
EOF
}

PART=""
VERSION=""
REMOTE="origin"
SKIP_CHECKS=0
ALLOW_DIRTY=0
DO_PUSH=0
DO_GH_RELEASE=0
DRY_RUN=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --part)
      PART="${2:-}"
      shift 2
      ;;
    --version)
      VERSION="${2:-}"
      shift 2
      ;;
    --remote)
      REMOTE="${2:-}"
      shift 2
      ;;
    --skip-checks)
      SKIP_CHECKS=1
      shift
      ;;
    --allow-dirty)
      ALLOW_DIRTY=1
      shift
      ;;
    --push)
      DO_PUSH=1
      shift
      ;;
    --create-gh-release)
      DO_GH_RELEASE=1
      shift
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -n "$PART" && -n "$VERSION" ]]; then
  echo "Use either --part or --version, not both." >&2
  exit 1
fi

if [[ -z "$PART" && -z "$VERSION" ]]; then
  echo "You must provide either --part or --version." >&2
  exit 1
fi

if [[ -n "$PART" && ! "$PART" =~ ^(patch|minor|major)$ ]]; then
  echo "--part must be one of: patch, minor, major." >&2
  exit 1
fi

if [[ "$ALLOW_DIRTY" -eq 0 ]]; then
  if ! git diff --quiet --ignore-submodules -- || ! git diff --cached --quiet --ignore-submodules --; then
    echo "Working tree is dirty. Commit/stash changes or pass --allow-dirty." >&2
    exit 1
  fi
  if [[ -n "$(git ls-files --others --exclude-standard)" ]]; then
    echo "Working tree has untracked files. Clean up or pass --allow-dirty." >&2
    exit 1
  fi
fi

current_version="$(awk -F= '$1=="version"{print $2}' gradle.properties | tr -d '[:space:]')"
if [[ ! "$current_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Current version is invalid: $current_version" >&2
  exit 1
fi

if [[ -n "$PART" ]]; then
  IFS='.' read -r major minor patch <<<"$current_version"
  case "$PART" in
    patch) patch=$((patch + 1)) ;;
    minor)
      minor=$((minor + 1))
      patch=0
      ;;
    major)
      major=$((major + 1))
      minor=0
      patch=0
      ;;
  esac
  VERSION="${major}.${minor}.${patch}"
fi

if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Version must match X.Y.Z, got: $VERSION" >&2
  exit 1
fi

TAG="v${VERSION}"

if git rev-parse --verify -q "refs/tags/${TAG}" >/dev/null; then
  echo "Tag already exists locally: ${TAG}" >&2
  exit 1
fi

if git remote get-url "$REMOTE" >/dev/null 2>&1; then
  if git ls-remote --exit-code --tags "$REMOTE" "refs/tags/${TAG}" >/dev/null 2>&1; then
    echo "Tag already exists on remote ${REMOTE}: ${TAG}" >&2
    exit 1
  fi
fi

current_serial="$(awk -F': ' '/^SerialVersion:/{print $2}' BappManifest.bmf | tr -d '[:space:]')"
if [[ ! "$current_serial" =~ ^[0-9]+$ ]]; then
  echo "Invalid BappManifest SerialVersion: $current_serial" >&2
  exit 1
fi
next_serial=$((current_serial + 1))

echo "Release plan:"
echo "  Version:      ${current_version} -> ${VERSION}"
echo "  Tag:          ${TAG}"
echo "  SerialVersion:${current_serial} -> ${next_serial}"
echo "  Remote:       ${REMOTE}"
echo "  Run checks:   $([[ "$SKIP_CHECKS" -eq 1 ]] && echo no || echo yes)"
echo "  Push:         $([[ "$DO_PUSH" -eq 1 ]] && echo yes || echo no)"
echo "  GH release:   $([[ "$DO_GH_RELEASE" -eq 1 ]] && echo yes || echo no)"

if [[ "$DRY_RUN" -eq 1 ]]; then
  exit 0
fi

sed -i -E "s/^version=.*/version=${VERSION}/" gradle.properties
sed -i -E "s/^ScreenVersion: .*/ScreenVersion: ${VERSION}/" BappManifest.bmf
sed -i -E "s/^SerialVersion: .*/SerialVersion: ${next_serial}/" BappManifest.bmf
sed -i -E "s/^  version: \".*\"/  version: \"${VERSION}\"/" skills/SKILL.md

if [[ "$SKIP_CHECKS" -eq 0 ]]; then
  ./gradlew clean test integrationTest ktlintCheck shadowJar --no-daemon
fi

git add gradle.properties BappManifest.bmf skills/SKILL.md
git commit -m "release: ${TAG}"
git tag -a "$TAG" -m "Release ${TAG}"

if [[ "$DO_PUSH" -eq 1 ]]; then
  git push "$REMOTE" HEAD
  git push "$REMOTE" "$TAG"
fi

if [[ "$DO_GH_RELEASE" -eq 1 ]]; then
  if ! command -v gh >/dev/null 2>&1; then
    echo "gh CLI not found. Install GitHub CLI to create releases from script." >&2
    exit 1
  fi
  if [[ "$DO_PUSH" -eq 0 ]]; then
    git push "$REMOTE" "$TAG"
  fi
  gh release create "$TAG" --title "$TAG" --generate-notes
fi

echo "Done. Created commit + tag: ${TAG}"
