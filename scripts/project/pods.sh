#!/usr/bin/env bash
# Lists, or deletes, the pods of the users of a project: the notebook pods that JupyterHub starts for each member, on the current kubectl
# cluster.  Deleting a user's pod ends their server: the hub starts a new pod as soon as they open the TRE's URL again, with no new
# sign-in needed if they are still signed in, which is how to give a member a fresh one, eg when their desktop is black because its window
# manager died.  What the member has not saved in the pod's home is lost.
#
# Usage:
#   ./pods.sh [list]
#   ./pods.sh delete (--user <user> | --all) [--yes] [--dry-run]
#
#   list       lists the user pods: the user, the pod, its status and its age.  The default.
#   delete     deletes the pods of the user, or of every user, asking first.  It needs one of --user and --all, so that it never deletes
#              them all by default.
#   --user     the user whose pod it is, or part of its name, eg their username or the start of their email: the pod is named after the
#              user, with the characters that Kubernetes does not take escaped.
#   --all      every user's pod
#   --yes      does not ask first
#   --dry-run  says which pods it would delete, and deletes none
#
# Env vars:
#   NAMESPACE=...  the namespace of the notebook pods (defaults to default)
#   CONTEXT=...    kubectl context to use (defaults to current context)
#
# Examples:
#   ./pods.sh
#   ./pods.sh delete --user alice@tre.example
#   ./pods.sh delete --all --yes
#
# Run in a project, so that it is that project's cluster, with afon-cli.sh manage-project run <project> project-pods ...

set -euo pipefail

POD_LABEL="afon-role=jupyter-singleuser"
NAMESPACE="${NAMESPACE:-default}"

usage() {
  sed -n '2,29p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
  exit 1
}

ACTION="list"
if [[ "${1:-}" == "list" || "${1:-}" == "delete" ]]; then
  ACTION="$1"
  shift
fi
USER_NAME=""
ALL=false
YES=false
DRY_RUN=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --user) [[ -n "${2:-}" ]] || usage; USER_NAME="$2"; shift 2 ;;
    --all) ALL=true; shift ;;
    --yes|-y) YES=true; shift ;;
    --dry-run) DRY_RUN=true; shift ;;
    -h|--help) usage ;;
    *) echo "Unknown argument: $1" >&2; usage ;;
  esac
done
if [[ "$ACTION" == "delete" && -z "$USER_NAME" && "$ALL" != "true" ]]; then
  echo "delete needs --user <user>, or --all for every user's pod" >&2
  exit 1
fi
if [[ -n "$USER_NAME" && "$ALL" == "true" ]]; then
  echo "Give --user or --all, not both" >&2
  exit 1
fi

if ! command -v kubectl >/dev/null 2>&1; then
  echo "kubectl not found on PATH" >&2
  exit 1
fi
KUBECTL=(kubectl)
if [[ -n "${CONTEXT:-}" ]]; then
  KUBECTL+=(--context "$CONTEXT")
fi

# The user pods, as kubectl prints them: NAME READY STATUS RESTARTS AGE
LINES=$("${KUBECTL[@]}" get pods -n "$NAMESPACE" -l "$POD_LABEL" --no-headers)
if [[ -n "$USER_NAME" ]]; then
  # Kubernetes escapes, in a pod's name, what is not a lower-case letter or a digit, eg . as -2e and @ as -40, so a user is matched as they are
  # given and as they would be escaped
  ESCAPED=$(printf '%s' "$USER_NAME" | tr '[:upper:]' '[:lower:]' | perl -pe 's/([^a-z0-9])/sprintf("-%02x", ord($1))/ge')
  LINES=$(printf '%s\n' "$LINES" | grep -i -F -e "$USER_NAME" -e "$ESCAPED" || true)
fi

if [[ -z "$(printf '%s' "$LINES")" ]]; then
  echo "No user pods${USER_NAME:+ for '$USER_NAME'} in namespace $NAMESPACE (label $POD_LABEL)"
  exit 0
fi

# The user of a pod: its name without the prefix, with the escaped characters back, eg jupyter-alice-40emrys-2ehealth is alice@emrys.health
user_of() {
  printf '%s' "${1#jupyter-}" | perl -pe 's/-([0-9a-f]{2})/chr(hex($1))/ge'
}

printf '%-40s %-50s %-10s %s\n' "USER" "POD" "STATUS" "AGE"
PODS=()
while read -r name _ready status _restarts age; do
  [[ -z "$name" ]] && continue
  printf '%-40s %-50s %-10s %s\n' "$(user_of "$name")" "$name" "$status" "$age"
  PODS+=("$name")
done <<< "$LINES"

if [[ "$ACTION" == "list" ]]; then
  exit 0
fi

if [[ "$DRY_RUN" == "true" ]]; then
  echo "--dry-run: not deleting ${#PODS[@]} pod(s)"
  exit 0
fi
if [[ "$YES" != "true" ]]; then
  if [[ ! -t 0 ]]; then
    echo "Not deleting ${#PODS[@]} pod(s): there is no terminal to ask on, so give --yes" >&2
    exit 1
  fi
  read -r -p "Delete ${#PODS[@]} pod(s)? Their servers end, and what their users did not save in the pod is lost. [y/N] " CONFIRM
  if [[ ! "$CONFIRM" =~ ^[Yy]$ ]]; then
    echo "Aborted."
    exit 0
  fi
fi

for pod in "${PODS[@]}"; do
  echo "Deleting pod $pod"
  "${KUBECTL[@]}" delete pod -n "$NAMESPACE" "$pod" --ignore-not-found
done
echo "Done: each of these users gets a new pod as soon as they open the TRE's URL again, signed in still or not."
