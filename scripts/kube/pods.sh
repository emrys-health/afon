#!/usr/bin/env bash
# List, or delete, pods matching a given status (phase or reason) on the current kubectl
# context/cluster.
#
# Usage:
#   ./pods.sh [list|delete] <status> [namespace]
#
#   list|delete Action to take on the matching pods.  Defaults to list.
#   <status>    Pod phase or reason to match, e.g. Failed, Evicted, Error,
#               Completed, Pending, CrashLoopBackOff, Unknown, ImagePullBackOff.
#   [namespace] Namespace to restrict to. Omit (or pass "all") to search
#               across all namespaces.
#
# Env vars:
#   DRY_RUN=1   For the delete action, print what would be deleted without deleting anything.
#   CONTEXT=... kubectl context to use (defaults to current context).
#
# Examples:
#   ./pods.sh Evicted
#   ./pods.sh list Failed kube-system
#   ./pods.sh delete Evicted
#   DRY_RUN=1 ./pods.sh delete CrashLoopBackOff all

set -euo pipefail

ACTION="list"
if [[ "${1:-}" == "list" || "${1:-}" == "delete" ]]; then
  ACTION="$1"
  shift
fi
STATUS="${1:-}"
NAMESPACE="${2:-all}"

if [[ -z "$STATUS" ]]; then
  echo "Usage: ${BASH_SOURCE[0]} [list|delete] <status> [namespace]" >&2
  echo "  e.g. ${BASH_SOURCE[0]} Evicted" >&2
  echo "       ${BASH_SOURCE[0]} delete Failed kube-system" >&2
  exit 1
fi

if ! command -v kubectl >/dev/null 2>&1; then
  echo "kubectl not found on PATH" >&2
  exit 1
fi

CONTEXT="${CONTEXT:-$(kubectl config current-context)}"
echo "Using kubectl context: $CONTEXT"

if [[ "$NAMESPACE" == "all" ]]; then
  NS_ARGS=(--all-namespaces)
else
  NS_ARGS=(--namespace "$NAMESPACE")
fi

echo "Looking for pods with status '$STATUS' ${NAMESPACE:+in namespace $NAMESPACE}..."

# Use the same STATUS column that `kubectl get pods` prints (Running, Pending,
# Evicted, CrashLoopBackOff, Completed, Error, etc.) so matching lines up with
# what an operator sees on screen.
if [[ "$NAMESPACE" == "all" ]]; then
  # columns: NAMESPACE NAME READY STATUS RESTARTS AGE
  MATCHES=$(kubectl --context "$CONTEXT" get pods "${NS_ARGS[@]}" --no-headers | \
    awk -v status="$STATUS" '$4 == status { print $1, $2 }')
else
  # columns: NAME READY STATUS RESTARTS AGE
  MATCHES=$(kubectl --context "$CONTEXT" get pods "${NS_ARGS[@]}" --no-headers | \
    awk -v ns="$NAMESPACE" -v status="$STATUS" '$3 == status { print ns, $1 }')
fi

if [[ -z "$MATCHES" ]]; then
  echo "No pods found matching status '$STATUS'."
  exit 0
fi

COUNT=$(echo "$MATCHES" | wc -l | tr -d ' ')
echo "Found $COUNT pod(s) matching '$STATUS':"
echo "$MATCHES" | sed 's/^/  /'

if [[ "$ACTION" == "list" ]]; then
  exit 0
fi

if [[ "${DRY_RUN:-0}" == "1" ]]; then
  echo "DRY_RUN=1 set, not deleting."
  exit 0
fi

read -r -p "Delete these $COUNT pod(s)? [y/N] " CONFIRM
if [[ ! "$CONFIRM" =~ ^[Yy]$ ]]; then
  echo "Aborted."
  exit 0
fi

echo "$MATCHES" | while read -r ns name; do
  [[ -z "$name" ]] && continue
  echo "Deleting pod $name in namespace $ns..."
  kubectl --context "$CONTEXT" --namespace "$ns" delete pod "$name" --ignore-not-found
done

echo "Done."
