#!/usr/bin/env bash
# Access pod logs on the current kubectl cluster.
#
# Usage:
#   ./logs.sh <pod-name> [<log-file>] [<logs-args>]
#   pod-name: the pod name or part of it
#   log-file: the log file to export logs to
#   logs-args: optional log args, defaults to "-f"
# Examples:
#   ./logs.sh polaris

set -euo pipefail

POD_NAME="${1:-}"
LOG_FILE="${2:-}"
LOGS_ARGS="${3:-"-f"}"

if [ -z "$POD_NAME" ]; then
  echo "Usage: $0 <pod-name>" >&2
  exit 1
fi

if ! command -v kubectl >/dev/null 2>&1; then
  echo "kubectl not found on PATH" >&2
  exit 1
fi

POD="$(kubectl get pods | grep $POD_NAME | awk '{print $1}')"
echo "Using pod: $POD"

if [ -n "$LOG_FILE" ]; then
  kubectl logs $POD > $LOG_FILE
else
  echo "Using logs args: $LOGS_ARGS"
  kubectl logs $LOGS_ARGS $POD
fi