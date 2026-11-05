#!/usr/bin/env bash
#
# Builds the afon docker images: the base image of the cli tools (including maven, to build afon), and the cli image,
# being the base image with the pre-built afon-cli zip installed.  The version is the project version (from mvn), and
# the tags are taken from it and from git.
#
# usage: scripts/docker/afon/build.sh [--push] [--registry <registry>] [--platform <platform>] [--zip <zip>] [base|cli|all]
#   base|cli|all
#             the images to build (default: all).  cli needs the base image of the same version, so build it first.
#   --zip     the pre-built afon-cli zip for the cli image.  Defaults to afon-distribution/target/afon-<version>-cli.zip,
#             built with 'afon.sh -bg "clean package" -bp cli,distribution' (or in the afon-base image).
#   --push    pushes every tag of the built images
#   --registry
#             the registry to prefix the image names with, eg myacr.azurecr.io.  An Azure Container Registry is logged
#             in to with 'az acr login' before pushing, so needs 'az login' first.
#   --platform
#             linux/amd64 (default) or linux/arm64.  The tags are the same for both, so a push of one replaces the other.
#
set -euo pipefail

usage() {
  awk 'NR > 2 && !/^#/ { exit } NR > 2 { sub(/^# ?/, ""); print }' "$0"
}

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AFON_ROOT="$(cd "$DIR/../../.." && pwd)"

BASE_IMAGE="${BASE_IMAGE:-emryshealth/afon-base}"
CLI_IMAGE="${CLI_IMAGE:-emryshealth/afon-cli}"
ZIP=""
PLATFORM=linux/amd64
PUSH=false
REGISTRY=""
WHAT=all

while [ $# -gt 0 ]; do
  case "$1" in
    --push) PUSH=true ;;
    --platform) PLATFORM="$2"; shift ;;
    --registry) REGISTRY="$2"; shift ;;
    --zip) ZIP="$2"; shift ;;
    base|cli|all) WHAT="$1" ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 1 ;;
  esac
  shift
done

if [ -n "$REGISTRY" ]; then
  BASE_IMAGE="$REGISTRY/$BASE_IMAGE"
  CLI_IMAGE="$REGISTRY/$CLI_IMAGE"
fi

cd "$AFON_ROOT"

COMMIT="$(git rev-parse HEAD)"
SHORT_COMMIT="$(git rev-parse --short HEAD)"
# The project version: x-SNAPSHOT on main, and the release version on a release.  -N as only the root pom is needed.
VERSION="$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout -N)" \
  || { echo "Cannot read the project version with mvn" >&2; exit 1; }
PROJECT_VERSION="$VERSION"
DIRTY=false
git diff-index --quiet HEAD -- || DIRTY=true
! $DIRTY || VERSION="$VERSION-dirty"
CREATED="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
REPO="$(git remote get-url origin 2>/dev/null \
  | sed -E 's#^git@([^:]+):#https://\1/#; s#^(https?://)[^@/]+@#\1#; s#\.git$##')" || REPO=""

# A release is a clean build of a non-SNAPSHOT version
RELEASE=false
if [[ "$VERSION" != *-SNAPSHOT* && "$VERSION" != *-dirty ]]; then
  RELEASE=true
fi

# The -t args of the tags of an image
tags() {
  local image="$1"
  echo "-t $image:$VERSION"
  $DIRTY || echo "-t $image:$SHORT_COMMIT"
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

if $PUSH && [[ "$REGISTRY" == *.azurecr.io ]]; then
  az acr login --name "${REGISTRY%%.*}"
fi

if [ "$WHAT" = cli ] || [ "$WHAT" = all ]; then
  ZIP="${ZIP:-$AFON_ROOT/afon-distribution/target/afon-$PROJECT_VERSION-cli.zip}"
  [ -f "$ZIP" ] || { echo "The afon-cli zip $ZIP does not exist.  Build it first, or pass --zip <zip>." >&2; exit 1; }
fi

echo "Building afon images $WHAT, version $VERSION ($SHORT_COMMIT) for $PLATFORM$($RELEASE && echo ', release')"

if [ "$WHAT" = base ] || [ "$WHAT" = all ]; then
  # shellcheck disable=SC2046
  docker build "${COMMON_ARGS[@]}" $(tags "$BASE_IMAGE") "$DIR/base"
  push "$BASE_IMAGE"
fi

if [ "$WHAT" = cli ] || [ "$WHAT" = all ]; then
  ZIP="${ZIP:-$AFON_ROOT/afon-distribution/target/afon-$PROJECT_VERSION-cli.zip}"
  [ -f "$ZIP" ] || { echo "The afon-cli zip $ZIP does not exist.  Build it first, or pass --zip <zip>." >&2; exit 1; }
fi

echo "Building afon images $WHAT, version $VERSION ($SHORT_COMMIT) for $PLATFORM$($RELEASE && echo ', release')"

if [ "$WHAT" = base ] || [ "$WHAT" = all ]; then
  # shellcheck disable=SC2046
  docker build "${COMMON_ARGS[@]}" $(tags "$BASE_IMAGE") "$DIR/base"
  push "$BASE_IMAGE"
fi

if [ "$WHAT" = cli ] || [ "$WHAT" = all ]; then
  # The context is a dir with just the zip, as afon-cli.zip
  CONTEXT="$(mktemp -d)"
  trap 'rm -rf "$CONTEXT"' EXIT
  cp "$ZIP" "$CONTEXT/afon-cli.zip"
  # shellcheck disable=SC2046
  docker build "${COMMON_ARGS[@]}" $(tags "$CLI_IMAGE") \
    --build-arg "BASE_IMAGE=$BASE_IMAGE:$VERSION" \
    -f "$DIR/cli/Dockerfile" "$CONTEXT"
  push "$CLI_IMAGE"
fi
