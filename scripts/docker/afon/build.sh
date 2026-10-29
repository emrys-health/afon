#!/usr/bin/env bash
#
# Builds the afon docker images: the base image of the cli tools, and the cli image, being the base image with the
# afon-cli built from this repo.  The version and tags are taken from git.
#
# usage: scripts/docker/afon/build.sh [--push] [--platform <platform>] [base|cli|all]
#   base|cli|all
#             the images to build (default: all).  cli needs the base image of the same version, so build it first.
#   --push    pushes every tag of the built images
#   --platform
#             linux/amd64 (default) or linux/arm64.  The tags are the same for both, so a push of one replaces the other.
#
set -euo pipefail

usage() {
  awk 'NR > 2 && !/^#/ { exit } NR > 2 { sub(/^# ?/, ""); print }' "$0"
}

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AFON_ROOT="$(cd "$DIR/../../.." && pwd)"

BASE_IMAGE="${BASE_IMAGE:-emryshealth/afon-base-image}"
CLI_IMAGE="${CLI_IMAGE:-emryshealth/afon-cli}"
PLATFORM=linux/amd64
PUSH=false
WHAT=all

while [ $# -gt 0 ]; do
  case "$1" in
    --push) PUSH=true ;;
    --platform) PLATFORM="$2"; shift ;;
    base|cli|all) WHAT="$1" ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 1 ;;
  esac
  shift
done

cd "$AFON_ROOT"

COMMIT="$(git rev-parse HEAD)"
SHORT_COMMIT="$(git rev-parse --short HEAD)"
DESCRIBE="$(git describe --tags --match 'afon-*' --dirty --always)"
VERSION="${DESCRIBE#afon-}"
CREATED="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
REPO="$(git remote get-url origin 2>/dev/null \
  | sed -E 's#^git@([^:]+):#https://\1/#; s#^(https?://)[^@/]+@#\1#; s#\.git$##')" || REPO=""

RELEASE=false
if git describe --tags --exact-match --match 'afon-*' HEAD >/dev/null 2>&1 && [[ "$VERSION" != *-dirty ]]; then
  RELEASE=true
fi

# The -t args of the tags of an image
tags() {
  local image="$1"
  echo "-t $image:$VERSION"
  [[ "$VERSION" == *-dirty ]] || echo "-t $image:$SHORT_COMMIT"
  ! $RELEASE || echo "-t $image:latest"
}

push() {
  local image="$1"
  ! $PUSH || for t in $(tags "$image" | awk '{print $2}'); do docker push "$t"; done
}

COMMON_ARGS=(
  --platform "$PLATFORM"
  --build-arg "AFON_VERSION=$VERSION"
  --build-arg "AFON_COMMIT=$COMMIT"
  --build-arg "AFON_REPO=$REPO"
  --build-arg "AFON_CREATED=$CREATED"
)

echo "Building afon images $WHAT, version $VERSION ($SHORT_COMMIT) for $PLATFORM$($RELEASE && echo ', release')"

if [ "$WHAT" = base ] || [ "$WHAT" = all ]; then
  # shellcheck disable=SC2046
  docker build "${COMMON_ARGS[@]}" $(tags "$BASE_IMAGE") "$DIR/base"
  push "$BASE_IMAGE"
fi

if [ "$WHAT" = cli ] || [ "$WHAT" = all ]; then
  # The context is the repo root, trimmed by cli/Dockerfile.dockerignore
  # shellcheck disable=SC2046
  docker build "${COMMON_ARGS[@]}" $(tags "$CLI_IMAGE") \
    --build-arg "BASE_IMAGE=$BASE_IMAGE:$VERSION" \
    -f "$DIR/cli/Dockerfile" "$AFON_ROOT"
  push "$CLI_IMAGE"
fi
