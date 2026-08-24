#!/usr/bin/env bash
# Delete pods matching a given status (phase or reason) on the current kubectl context/cluster.
#
# Usage:
#   ./delete-pods-by-status.sh <status> [namespace]
#
#   <status>    Pod phase or reason to match, e.g. Failed, Evicted, Error,
#               Completed, Pending, CrashLoopBackOff, Unknown, ImagePullBackOff.
#   [namespace] Namespace to restrict to. Omit (or pass "all") to search
#               across all namespaces.
#
# Env vars:
#   DRY_RUN=1   Print what would be deleted without deleting anything.
#   CONTEXT=... kubectl context to use (defaults to current context).
#
# Examples:
#   ./delete-pods-by-status.sh Evicted
#   ./delete-pods-by-status.sh Failed kube-system
#   DRY_RUN=1 ./delete-pods-by-status.sh CrashLoopBackOff all

set -euo pipefail

STATUS="${1:-}"
NAMESPACE="${2:-all}"

if [[ -z "$STATUS" ]]; then
  echo "Usage: $0 <status> [namespace]" >&2
  echo "  e.g. $0 Evicted" >&2
  echo "       $0 Failed kube-system" >&2
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
