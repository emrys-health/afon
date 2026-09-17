#!/usr/bin/env bash
# Runs the afon-cli scripts
set -euo pipefail

usage(){
  echo "Usage: $0 <script-path> [script-args...]"
  echo "          <script-path> is relative to the scripts dir, eg 'config/switch.sh'."
  echo "options, settable only via env variables:"
  echo "  AFON_CLI_DIR:     the cli distribution dir. Defaults to '$AFON_HOME/cli'"
  echo "  AFON_HOME:        the afon home dir.  Defaults to '$HOME/.afon'."
  echo "  AFON_CLOUD:       the cloud to target.  One of aws|azure|gcp."
  echo "  AFON_CDKTF:       the afon cdktf dir.  Defaults to 'AFON_HOME/cdktf'."
  echo "  AFON_CDKTF_ENV:   the cdktf env name.  If set, switches AFON_CDKTF to 'AFON_CDKTF-<env>' before running the script."
  echo "  AFON_CONFIG_ENV:  the config env name.  If set, switches AFON_HOME/config to 'config-<env>' before running the script."
  echo "  AFON_CONFIG_ZIP:  the config zip path, as used by config/import.sh, config/export.sh."
  echo "  AFON_ENV:         sets both AFON_CDKTF_ENV and AFON_CONFIG_ENV.  Overrides them if also set."
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AFON_HOME="${AFON_HOME:-$HOME/.afon}"
AFON_CLI_DIR="${AFON_CLI_DIR:-"$AFON_HOME/cli"}"
if [ -z "$AFON_CLI_DIR" ]; then
  echo "AFON_CLI_DIR env must be set."
  exit 0
else
  AFON_ROOT="$AFON_CLI_DIR"
fi

AFON_CLOUD="${AFON_CLOUD:-}"
AFON_CDKTF="${AFON_CDKTF:-$AFON_HOME/cdktf}"
AFON_CDKTF_ENV="${AFON_CDKTF_ENV:-}"
AFON_CONFIG_ENV="${AFON_CONFIG_ENV:-}"
AFON_CONFIG_ZIP="${AFON_CONFIG_ZIP:-}"
AFON_ENV="${AFON_ENV:-}"

if [ -n "$AFON_ENV" ]; then
  echo "Using env=$AFON_ENV"
  echo "Both cdktf and config env are set to $AFON_ENV"
  AFON_CDKTF_ENV="$AFON_ENV"
  AFON_CONFIG_ENV="$AFON_ENV"
fi

CLOUD="$AFON_CLOUD"
if [ -n "$CLOUD" ]; then
  echo "Using cloud $CLOUD"
fi
CDKTF_DIR="$AFON_CDKTF"
CDKTF_ENV="$AFON_CDKTF_ENV"
CONFIG_DIR="$AFON_HOME/config"
CONFIG_ENV="$AFON_CONFIG_ENV"
CONFIG_ZIP="$AFON_CONFIG_ZIP"

cd $AFON_ROOT/scripts

# CONFIG_ENV/CDKTF_ENV are global: apply the switch up front so it takes effect for whichever
# script or command follows, not just when the target happens to be */switch.sh.
CONFIG_SWITCHED=
if [ -n "$CONFIG_ENV" ]; then
  source config/switch.sh
  CONFIG_SWITCHED=true
fi
CDKTF_SWITCHED=
if [ -n "$CDKTF_ENV" ]; then
  source cdktf/switch.sh
  CDKTF_SWITCHED=true
fi

AFON_SCRIPT=${1:-}
if [ -z "$AFON_SCRIPT" ]; then
  echo "$0 requires a script path relative to $AFON_ROOT/scripts dir."
  usage; exit 0
fi
shift

ACTION="${AFON_SCRIPT%.sh}"
ACTION="${ACTION//\//-}"

# Skip re-running config-switch/cdktf-switch: the global handling above already ran them.
ALREADY_RUN=
[ "$ACTION" == "config-switch" ] && [ -n "$CONFIG_SWITCHED" ] && ALREADY_RUN=true
[ "$ACTION" == "cdktf-switch" ] && [ -n "$CDKTF_SWITCHED" ] && ALREADY_RUN=true
[ -n "$ALREADY_RUN" ] || source $AFON_SCRIPT "$@"

