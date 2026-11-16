#!/usr/bin/env bash
# Runs a command in the afon-base image (see docs/docker.md), as the acceptance-tests-container workflow does on GitHub, so that what passes in it
# can be tried on a developer's machine before a commit, with the same tools at the same versions.
#
# What the container is given:
#   - the host's network, so that localhost is the Docker host's, which the acceptance specs assume (KeyCloak is bound at a fixed
#     localhost:8080, Trino reaches it there, the kind cluster is reached at localhost:<port>); on Docker Desktop that is its VM, where the
#     containers of the specs are too, so they still see each other;
#   - the docker socket, so that the containers the specs start are the host's, siblings of this one, and are not nested in it;
#   - the repository, as /work, which is where the command runs, and the maven repository of the user, ~/.m2, so that dependencies are not
#     downloaded each time;
#   - the afon home, as /tmp/afon (AFON_HOME there), which has the config the specs and the scripts find: ~/.afon, or the directory given;
#   - what the cloud login left, if --cloud is given: AWS in its variables and ~/.aws, Azure in ~/.azure, GCP in ~/.config/gcloud and
#     GOOGLE_APPLICATION_CREDENTIALS;
#   - the user: it runs as root, which the docker socket needs, and gives what it wrote back to the user when it ends (HOST_UID and HOST_GID are
#     in its environment, for a command that writes files to chown them).
# The image is the entrypoint-less bash: the entrypoint of the image makes a user and runs the command through `su`, which splits a script given to
# `bash -c`.
#
# Executed:
#   afon-docker.sh [--image <image>] [--cloud aws|azure|gcp] [--afon-home <dir>] [-e <NAME[=value]>]... [--] [<command>]
#     <command> is a script for bash -c, eg 'mvn -pl afon-acceptance test -Pproject'.  Without one, a shell in the container.
#     --image      the image, with its tag: emryshealth/afon-base:<version> of the project by default, which docker.yml publishes for the
#                  commit on main; a local build of it, scripts/docker/afon/build.sh base, has the same name and tag
#     -e           more environment for the command, as docker run -e takes it: a name, to give its value in this shell, or NAME=value
# Sourced:
#   afon_docker '<script>' [<docker run option>...]    runs the script, with IMAGE, CLOUD (optional), AFON_DIR (default ~/.afon) and
#                                                      WORKSPACE (default the repository, or GITHUB_WORKSPACE) in the environment
#
# The command is the whole of what runs in the container: the acceptance specs need the modules installed first, and the signing spec the docker
# client, which the image has no more than a daemon; see .github/workflows/acceptance-tests-container.yml for the steps.

# The repository is where this is: scripts/docker/afon/afon-docker.sh
AFON_DOCKER_REPOSITORY="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

# The version of the project, from the root pom, which is the tag of the images built from it, see docs/docker.md.
afon_docker_version() {
  if command -v mvn > /dev/null 2>&1; then
    mvn -q -B -f "$AFON_DOCKER_REPOSITORY/pom.xml" help:evaluate -Dexpression=project.version -DforceStdout -N 2> /dev/null && return 0
  fi
  # the first version of the root pom after its artifactId is the project's
  sed -n '/<artifactId>afon<\/artifactId>/,/<\/version>/s|.*<version>\(.*\)</version>.*|\1|p' "$AFON_DOCKER_REPOSITORY/pom.xml" | head -1
}

# afon_docker <script> [<docker run option>...]: runs the script in the container, see above.
afon_docker() {
  local script="$1"; shift
  local workspace="${WORKSPACE:-${GITHUB_WORKSPACE:-$AFON_DOCKER_REPOSITORY}}"
  local afon_dir="${AFON_DIR:-$HOME/.afon}"
  local image="${IMAGE:-emryshealth/afon-base:$(afon_docker_version)}"
  local cloud_args=()
  case "${CLOUD:-}" in
    aws)
      cloud_args=(-e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY -e AWS_SESSION_TOKEN -e AWS_REGION -e AWS_DEFAULT_REGION -e AWS_PROFILE)
      [ ! -d "$HOME/.aws" ] || cloud_args+=(-v "$HOME/.aws":/root/.aws:ro) ;;
    azure)
      [ ! -d "$HOME/.azure" ] || cloud_args=(-v "$HOME/.azure":/root/.azure) ;;
    gcp)
      [ ! -d "$HOME/.config/gcloud" ] || cloud_args+=(-v "$HOME/.config/gcloud":/root/.config/gcloud)
      if [ -n "${GOOGLE_APPLICATION_CREDENTIALS:-}" ] && [ -f "$GOOGLE_APPLICATION_CREDENTIALS" ]; then
        cloud_args+=(-e GOOGLE_APPLICATION_CREDENTIALS -v "$GOOGLE_APPLICATION_CREDENTIALS":"$GOOGLE_APPLICATION_CREDENTIALS":ro)
      fi ;;
  esac
  mkdir -p "$HOME/.m2/repository" "$afon_dir"
  # a terminal, when there is one, as the shell of the container and the colours of what it runs are wanted then
  local tty_args=()
  if [ -t 0 ] && [ -t 1 ]; then tty_args=(-it); fi
  docker run --rm "${tty_args[@]+"${tty_args[@]}"}" --network host --user root --entrypoint bash \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "$workspace":/work -w /work \
    -v "$HOME/.m2":/root/.m2 \
    -v "$afon_dir":/tmp/afon \
    "${cloud_args[@]+"${cloud_args[@]}"}" \
    -e MAVEN_OPTS -e AFON_LOGGING_CONSOLE -e DOCKER_CLIENT_VERSION \
    -e CLOUD -e CONFIG_ENV -e CONFIG_TAG -e AZURE_KEY_VAULT_NAME -e GCP_PROJECT_ID \
    -e HOST_UID="$(id -u)" -e HOST_GID="$(id -g)" \
    -e TESTCONTAINERS_HOST_OVERRIDE=localhost -e AFON_HOME=/tmp/afon -e AFON_CLI_DIR=/work \
    "$@" "$image" -c "$script"
}

afon_docker_usage() {
  sed -n '/^# Executed:/,/^# Sourced:/p' "${BASH_SOURCE[0]}" | sed '$d; s/^# \{0,1\}//' >&2
}

afon_docker_main() {
  local extra=()
  local command=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --image)      [ -n "${2:-}" ] || { afon_docker_usage; return 1; }; IMAGE="$2"; shift 2 ;;
      --cloud)      [ -n "${2:-}" ] || { afon_docker_usage; return 1; }; CLOUD="$2"; shift 2 ;;
      --afon-home)  [ -n "${2:-}" ] || { afon_docker_usage; return 1; }; AFON_DIR="$2"; shift 2 ;;
      -e)           [ -n "${2:-}" ] || { afon_docker_usage; return 1; }; extra+=(-e "$2"); shift 2 ;;
      -h|--help)    afon_docker_usage; return 0 ;;
      --)           shift; command="$*"; break ;;
      -*)           echo "Unknown option $1" >&2; afon_docker_usage; return 1 ;;
      *)            command="$*"; break ;;
    esac
  done
  case "${CLOUD:-}" in
    ""|aws|azure|gcp) ;;
    *) echo "The cloud should be aws, azure or gcp, not '$CLOUD'" >&2; return 1 ;;
  esac
  export IMAGE CLOUD AFON_DIR
  if [ -z "$command" ]; then
    # a shell
    afon_docker 'exec bash' "${extra[@]+"${extra[@]}"}"
  else
    afon_docker "$command" "${extra[@]+"${extra[@]}"}"
  fi
}

# Executed, not sourced
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  set -euo pipefail
  afon_docker_main "$@"
fi
