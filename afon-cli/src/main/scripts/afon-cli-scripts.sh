#!/usr/bin/env bash
# Runs the afon-cli scripts
set -euo pipefail

usage(){
  echo "Usage: $0 [--home <dir>] [--cdktf <dir>] [--cloud|-c <cloud>]"
  echo "          [--config-env <env>] [--cdktf-env <env>] [--env|-e <env>]"
  echo "          <script-path> [script-args...]"
  echo "          <script-path> is relative to the scripts dir, eg 'config/switch.sh'."
  echo "options, settable via the flags above or the equivalent AFON_* env var (a flag wins over its env var):"
  echo "  --home / AFON_HOME:             the afon home dir.  Defaults to '$HOME/.afon'."
  echo "  --cdktf / AFON_CDKTF:           the afon cdktf dir.  Defaults to 'AFON_HOME/cdktf'."
  echo "  --cloud / AFON_CLOUD:           the cloud to target.  One of aws|azure|gcp."
  echo "  --cdktf-env / AFON_CDKTF_ENV:   the cdktf env name.  If set, switches AFON_CDKTF to 'AFON_CDKTF-<env>' before running the script."
  echo "  --config-env / AFON_CONFIG_ENV: the config env name.  If set, switches AFON_HOME/config to 'config-<env>' before running the script."
  echo "  --env / AFON_ENV:               sets both --cdktf-env and --config-env.  Overrides them if also set."
  echo "  AFON_CLI_DIR (env only):        the cli distribution dir. Defaults to 'AFON_HOME/cli'."
  echo "options specific to a single script (eg config/export.sh's zip path) are not flags here -"
  echo "pass them as script-args, eg: $0 config/export.sh myconfig.zip"
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AFON_HOME="${AFON_HOME:-}"
AFON_CLI_DIR="${AFON_CLI_DIR:-}"
AFON_CLOUD="${AFON_CLOUD:-}"
AFON_CDKTF="${AFON_CDKTF:-}"
AFON_CDKTF_ENV="${AFON_CDKTF_ENV:-}"
AFON_CONFIG_ENV="${AFON_CONFIG_ENV:-}"
AFON_ENV="${AFON_ENV:-}"

ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --home)
      AFON_HOME="$2"; shift 2 ;;
    --cdktf)
      AFON_CDKTF="$2"; shift 2 ;;
    --cloud|-c)
      AFON_CLOUD="$2"; shift 2 ;;
    --config-env)
      AFON_CONFIG_ENV="$2"; shift 2 ;;
    --cdktf-env)
      AFON_CDKTF_ENV="$2"; shift 2 ;;
    --env|-e)
      AFON_ENV="$2"; shift 2 ;;
    --help|-h)
      usage; exit 0 ;;
    *)
      ARGS+=("$1"); shift ;;
  esac
done
# macOS ships bash 3.2, which throws "unbound variable" expanding "${ARGS[@]}" under set -u
# when ARGS is empty (fixed in bash 4.4+), so guard the expansion instead of using it directly.
if [ ${#ARGS[@]} -gt 0 ]; then
  set -- "${ARGS[@]}"
else
  set --
fi

AFON_HOME="${AFON_HOME:-$HOME/.afon}"
AFON_CLI_DIR="${AFON_CLI_DIR:-"$AFON_HOME/cli"}"
if [ -z "$AFON_CLI_DIR" ]; then
  echo "AFON_CLI_DIR env must be set."
  exit 0
else
  AFON_ROOT="$AFON_CLI_DIR"
fi
AFON_CDKTF="${AFON_CDKTF:-$AFON_HOME/cdktf}"

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
