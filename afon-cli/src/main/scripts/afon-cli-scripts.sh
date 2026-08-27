#!/usr/bin/env bash
# Runs the afon-cli scripts
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AFON_CLI_DIR="${AFON_CLI_DIR:-}"
if [ -z "$AFON_CLI_DIR" ]; then
  echo "AFON_CLI_DIR env must be set."
  exit 0
else
  AFON_ROOT="$AFON_CLI_DIR"
fi

AFON_HOME="${AFON_HOME:-$HOME/.afon}"
AFON_CDKTF="${AFON_CDKTF:-$AFON_HOME/cdktf}"
AFON_CLOUD="${AFON_CLOUD:-}"

CLOUD=
if [ -n "$AFON_CLOUD" ]; then
  CLOUD="$AFON_CLOUD"
  echo "Using cloud $CLOUD"
fi

AFON_SCRIPT=${1:-}
if [ -z "$AFON_SCRIPT" ]; then
  echo "$0 requires a script path relative to $AFON_ROOT/scripts dir."
  exit 0
fi

. $AFON_ROOT/scripts/$AFON_SCRIPT

