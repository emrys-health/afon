#!/usr/bin/env bash
#
# Runs the image signing flow of docs/stacks.md end to end on this machine, with a throwaway local registry and a throwaway key, so that it needs
# neither a cloud registry nor a KMS: it signs one image into the registry and checks that its signature verifies, and that the images that were
# not signed do not.
#
# usage: scripts/smoke/signing.sh [--env|-e <env>] [<cloud>] [<image>]
#   cloud     the cloud whose properties are used, aws, azure or gcp (default: aws).  Only its property names are used: the registry and the key are
#             set for the run, so the config of the cloud, and its keys, are neither read nor changed.
#   image     the name of the image of docker-images.yaml to sign (default: postgres-alpine)
#   --env     the afon env, as for './afon.sh --env <env>' (exported as AFON_ENV)
#
# The steps:
#   1. starts a registry (the image of docker-images.yaml) on a free local port, from 5000
#   2. generates a key pair, with no password, and gets its public key with 'docker-image --public-key'
#   3. signs the image with 'docker-image --sign', which copies it to the registry first
#   4. checks with 'docker-image --verify --signatures' that the image is signed, and then all the images, of which only that one should be
#   5. removes the registry and the key, also when a step fails
#
# Needs docker, cosign and curl, and network access to pull the image.  COSIGN_PASSWORD is set to empty for the run.  The commands are run with
# './afon.sh -a <command>', or, from a cli distribution, with its afon-cli.sh, eg as 'afon-cli.sh smoke-signing azure'.

set -euo pipefail

SCRIPT_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
AFON_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
  sed -n '3,/^$/p' "$SCRIPT_FILE" | sed '$d; s/^# \{0,1\}//'
}

ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --env|-e)
      if [ -z "${2:-}" ]; then
        echo "Option $1 requires a value" >&2
        exit 1
      fi
      export AFON_ENV="$2"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    --*) echo "Unknown option $1" >&2; exit 1 ;;
    *) ARGS+=("$1"); shift ;;
  esac
done
# the cloud is the first arg if it is one, else aws, as for notebook.sh
CLOUD=aws
case "$(echo "${ARGS[0]:-}" | tr '[:upper:]' '[:lower:]')" in
  aws|azure|gcp) CLOUD=$(echo "${ARGS[0]}" | tr '[:upper:]' '[:lower:]'); ARGS=(${ARGS[@]+"${ARGS[@]:1}"}) ;;
  *) echo "No cloud given, defaulting to $CLOUD" ;;
esac
IMAGE="${ARGS[0]:-postgres-alpine}"
CLOUD_UPPER="$(echo "$CLOUD" | tr '[:lower:]' '[:upper:]')"

for TOOL in docker cosign curl; do
  if ! command -v "$TOOL" >/dev/null 2>&1; then
    echo "$TOOL not found on PATH: run afon-cli.sh local-setup" >&2
    exit 1
  fi
done

# Runs the afon command $1 with the args $2...: with afon.sh, if the script runs from the source tree, else with the
# afon-cli.sh of the cli distribution it runs from, or that in AFON_CLI_DIR.  The settings in ENVS are given to its java process as system properties, with afon.sh's --exec-envs or the AFON_ENVS of afon-cli.sh, after any already in AFON_ENVS.
ENVS=""
afon() {
  local command="$1" envs="${AFON_ENVS:-}"
  shift
  envs="${envs:+$envs }$ENVS"
  if [ -x "$AFON_ROOT/afon.sh" ]; then
    "$AFON_ROOT/afon.sh" -a "$command" --exec-envs "$envs" -- "$@"
  else
    AFON_ENVS="$envs" SPRING_MAIN_BANNER_MODE="${SPRING_MAIN_BANNER_MODE:-off}" "${AFON_CLI_DIR:-$AFON_ROOT}/afon-cli.sh" "$command" "$@"
  fi
}

WORK_DIR="$(mktemp -d)"
REGISTRY_NAME="afon-smoke-registry-$$"
cleanup() {
  echo "== 5. Removing the registry and the key"
  docker rm -f "$REGISTRY_NAME" >/dev/null 2>&1 || true
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

# the key has no password, whatever the environment has
export COSIGN_PASSWORD=""

echo "== 1. Starting a local registry"
REGISTRY_IMAGE="$(afon docker-image registry | sed -n 's/^AFON_DOCKER_IMAGE=//p' | tail -1)"
if [ -z "$REGISTRY_IMAGE" ]; then
  echo "Cannot get the registry image from docker-images.yaml" >&2
  exit 1
fi
# a port chosen up front, as on Docker Desktop the daemon does not reach one that Docker allocates
PORT=5000
while (echo > "/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; do
  PORT=$((PORT + 1))
done
docker run -d --name "$REGISTRY_NAME" -p "127.0.0.1:$PORT:5000" "$REGISTRY_IMAGE" >/dev/null
for _ in $(seq 1 30); do
  curl -fs "http://localhost:$PORT/v2/" >/dev/null 2>&1 && break
  sleep 1
done
curl -fs "http://localhost:$PORT/v2/" >/dev/null || { echo "The registry does not answer on localhost:$PORT" >&2; exit 1; }
echo "- Registry on localhost:$PORT"

echo "== 2. Generating a key pair and getting its public key"
(cd "$WORK_DIR" && cosign generate-key-pair </dev/null >/dev/null)
ENVS="${CLOUD_UPPER}_IMAGE_REGISTRY=localhost:$PORT/emrys ${CLOUD_UPPER}_COSIGN_KEY=$WORK_DIR/cosign.key"
PUBLIC_KEY="$(afon docker-image --public-key --cloud "$CLOUD" | sed -n "s/^${CLOUD_UPPER}_COSIGN_PUBLIC_KEY=//p" | tail -1)"
if [ -z "$PUBLIC_KEY" ]; then
  echo "Cannot get the public key of $WORK_DIR/cosign.key" >&2
  exit 1
fi
ENVS="$ENVS ${CLOUD_UPPER}_COSIGN_PUBLIC_KEY=$PUBLIC_KEY"

echo "== 3. Signing $IMAGE into the registry"
afon docker-image --sign --cloud "$CLOUD" "$IMAGE"

echo "== 4. Verifying the signatures"
echo "- $IMAGE, which is signed: it must pass"
afon docker-image --verify --signatures --cloud "$CLOUD" "$IMAGE"
echo "- every image, of which only $IMAGE is signed: the others must fail, as they are not in the registry"
# its output is filtered, as the command is meant to fail, and afon.sh then prints the failure of the Maven run
if OUTPUT="$(afon docker-image --verify --signatures --cloud "$CLOUD" 2>&1)"; then
  echo "$OUTPUT"
  echo "Every image verified, though only $IMAGE was signed" >&2
  exit 1
fi
echo "$OUTPUT" | grep -E ': OK$|: FAILED|^Verified|^Sign them' || { echo "$OUTPUT"; echo "The command failed, but not by finding unsigned images" >&2; exit 1; }
echo "OK: $IMAGE is signed and verifies, and the images that were not signed do not"
