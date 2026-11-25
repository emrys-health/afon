#!/usr/bin/env bash
# Copies a file or directory to, or from, the notebook pod of a user, on the current kubectl cluster, eg a notebook to run or its results.
#
# Usage:
#   ./user-cp.sh [--user <user>] to   <local-path>  [<pod-path>]
#   ./user-cp.sh [--user <user>] from <pod-path>    [<local-path>]
#
#   --user      the user whose pod it is, or part of its name, eg their username or the start of their email: the pod is named after the
#               user, with the characters that Kubernetes does not take escaped.  Not needed when only one notebook pod is running.
#   <pod-path>  relative to the user's home in the pod, /home/jovyan, or absolute.  When copying to, defaults to the home; when copying from,
#               <local-path> defaults to the current dir.
#
# Env vars:
#   NAMESPACE=...  the namespace of the notebook pods (defaults to default)
#   CONTEXT=...    kubectl context to use (defaults to current context)
#
# Examples:
#   ./user-cp.sh to project-trino-reads-data-providers.ipynb
#   ./user-cp.sh --user mauro from project-trino-reads-data-providers.ipynb results/
#
# A local path is relative to the dir that the command is run from.
#
# Run in a project, so that it is that project's cluster, with afon-cli.sh manage-project-stack run <project> kube-user-cp ...

set -euo pipefail

POD_LABEL="afon-role=jupyter-singleuser"
POD_HOME="/home/jovyan"
NAMESPACE="${NAMESPACE:-default}"

usage() {
  sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
  exit 1
}

USER_NAME=""
if [[ "${1:-}" == "--user" ]]; then
  [[ -n "${2:-}" ]] || usage
  USER_NAME="$2"
  shift 2
fi

DIRECTION="${1:-}"
SOURCE="${2:-}"
TARGET="${3:-}"
if [[ ( "$DIRECTION" != "to" && "$DIRECTION" != "from" ) || -z "$SOURCE" ]]; then
  usage
fi

if ! command -v kubectl >/dev/null 2>&1; then
  echo "kubectl not found on PATH" >&2
  exit 1
fi

KUBECTL=(kubectl)
if [[ -n "${CONTEXT:-}" ]]; then
  KUBECTL+=(--context "$CONTEXT")
fi

# The running notebook pods, by name.
PODS=$("${KUBECTL[@]}" get pods -n "$NAMESPACE" -l "$POD_LABEL" --field-selector=status.phase=Running --no-headers -o custom-columns=NAME:.metadata.name)
if [[ -n "$USER_NAME" ]]; then
  # Kubernetes escapes, in a pod's name, what is not a lower-case letter or a digit, eg . as -2e and @ as -40, so a user is matched as they are
  # given and as they would be escaped
  ESCAPED=$(printf '%s' "$USER_NAME" | tr '[:upper:]' '[:lower:]' | perl -pe 's/([^a-z0-9])/sprintf("-%02x", ord($1))/ge')
  PODS=$(printf '%s\n' "$PODS" | grep -i -F -e "$USER_NAME" -e "$ESCAPED" || true)
fi

COUNT=$(printf '%s\n' "$PODS" | grep -c . || true)
if [[ "$COUNT" -eq 0 ]]; then
  echo "No running notebook pod${USER_NAME:+ for '$USER_NAME'} in namespace $NAMESPACE (label $POD_LABEL)" >&2
  exit 1
fi
if [[ "$COUNT" -gt 1 ]]; then
  echo "More than one notebook pod${USER_NAME:+ for '$USER_NAME'}, give the user with --user:" >&2
  printf '%s\n' "$PODS" | sed 's/^/  /' >&2
  exit 1
fi
POD="$PODS"
echo "Using pod: $POD"

# A local path is relative to where the command was run from, not to the scripts' dir that it runs in
local_path() {
  case "$1" in
    /*) echo "$1" ;;
    .)  echo "${AFON_CALLER_DIR:-$PWD}" ;;
    *)  echo "${AFON_CALLER_DIR:-$PWD}/$1" ;;
  esac
}

# A path of the pod is relative to its home
pod_path() {
  case "$1" in
    /*) echo "$1" ;;
    *)  echo "$POD_HOME/$1" ;;
  esac
}

if [[ "$DIRECTION" == "to" ]]; then
  LOCAL=$(local_path "$SOURCE")
  if [[ ! -e "$LOCAL" ]]; then
    echo "No such file or directory: $SOURCE" >&2
    exit 1
  fi
  DESTINATION=$(pod_path "${TARGET:-$(basename "$SOURCE")}")
  echo "Copying $LOCAL to $POD:$DESTINATION"
  "${KUBECTL[@]}" cp -n "$NAMESPACE" "$LOCAL" "$POD:$DESTINATION"
else
  ORIGIN=$(pod_path "$SOURCE")
  DESTINATION=$(local_path "${TARGET:-.}")
  # kubectl cp takes the path of the file to make, not the dir to put it in
  if [[ -d "$DESTINATION" ]]; then
    DESTINATION="${DESTINATION%/}/$(basename "$ORIGIN")"
  fi
  echo "Copying $POD:$ORIGIN to $DESTINATION"
  "${KUBECTL[@]}" cp -n "$NAMESPACE" "$POD:$ORIGIN" "$DESTINATION"
fi
