#!/usr/bin/env bash
#
# Runs a notebook in the afon python env, from this machine against a deployed stack, as a Jupyter pod would: via
# Spark Connect, with catalogs authenticated by the user's OIDC token.
#
# usage: scripts/smoke/notebook.sh [--env|-e <env>] [--notebooks|-n <file>] [<cloud>] [<notebook>...]
#        scripts/smoke/notebook.sh [--notebooks|-n <file>] --list|-l
#   cloud     the cloud whose Polaris to use, aws, azure or gcp (default: aws), read from <CLOUD>_POLARIS_DOMAIN_NAME.
#             Given first, if at all.  The notebook may use the catalogs <cloud> and <cloud>_forbidden, set as AFON_CATALOG and AFON_FORBIDDEN_CATALOG, unless already set.
#   notebook  the names of the notebooks to run, separated by spaces or commas, as listed by --list (default: all of them,
#             in the order of the notebooks file).  All are run, even if some fail, and the script exits non-zero if any fails.
#   --env     the afon env, as for './afon.sh --env <env>': the config read, and used by every afon command, is
#             $AFON_HOME/config-<env>.  Exported as AFON_ENV.  Default: the AFON_ENV already set, else the current config.
#   --notebooks
#             the CSV file of the notebooks (default: scripts/smoke/notebooks.csv)
#   --list    lists the notebooks and exits
#
# The notebooks are read from the notebooks file, whose first line is the header 'name|file|description', and
# each other line a notebook: its name, its file, in scripts/smoke/notebooks if not an absolute path, and its description.  Blank lines and lines
# starting with '#' are ignored.
#
# Each executed notebook, with its outputs, is written to smoke/target/<notebook file name>, leaving the
# original clean.
#
# Sets up and checks the python env with the afon command 'python-setup', checks Keycloak is serving requests with
# 'check-oidc <cloud>', then
# gets USER_ID's OIDC access token from Keycloak with 'check-oidc <cloud> --token user',
# port-forwards the Spark Connect driver of the current kubectl context, then runs the notebook with
# 'jupyter-execute'.  The commands are run with './afon.sh -a <command>', or, from a cli distribution, which has no
# afon.sh, with its afon-cli.sh, eg as 'afon-cli.sh smoke-notebook <cloud> <notebook>'.  Also reads
# <CLOUD>_POLARIS_DOMAIN_NAME and <CLOUD>_OIDC_DOMAIN_NAME (else OIDC_DOMAIN_NAME) from
# $AFON_HOME/config/*.properties, or those of --env (AFON_HOME defaults to ~/.afon), for the notebook.  Only those properties
# are read, rather than sourcing the secrets: sourcing them would also export cloud credentials that change
# the identity kubectl uses.  The token is never printed.

set -euo pipefail

SCRIPT_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
AFON_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
AFON_HOME="${AFON_HOME:-$HOME/.afon}"
NOTEBOOKS_DIR="$AFON_ROOT/scripts/smoke/notebooks"
NOTEBOOKS_FILE="$AFON_ROOT/scripts/smoke/notebooks.csv"  # the default, unless given --notebooks

# Prints the notebooks of the notebooks file as "<name>|<file>|<description>" lines, without its header and any comments.
notebook_lines() {
  if [ ! -f "$NOTEBOOKS_FILE" ]; then
    echo "Missing notebooks file $NOTEBOOKS_FILE" >&2
    exit 1
  fi
  tr -d '\r' < "$NOTEBOOKS_FILE" | grep -v '^$' | grep -v '^#' | tail -n +2 || true
}

usage() {
  sed -n '3,/^$/p' "$SCRIPT_FILE" | sed '$d; s/^# \{0,1\}//'
}

list_notebooks() {
  notebook_lines | while IFS='|' read -r name file description; do
    printf '%-20s %s\n' "$name" "$description"
  done
}

LIST=
ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --notebooks|-n)
      if [ -z "${2:-}" ]; then
        echo "Option $1 requires a value" >&2
        exit 1
      fi
      NOTEBOOKS_FILE="$2"; shift 2 ;;
    --env|-e)
      if [ -z "${2:-}" ]; then
        echo "Option $1 requires a value" >&2
        exit 1
      fi
      export AFON_ENV="$2"; shift 2 ;;
    --list|-l) LIST=true; shift ;;
    --help|-h) usage; exit 0 ;;
    *) ARGS+=("$1"); shift ;;
  esac
done
if [ -n "$LIST" ]; then
  list_notebooks; exit 0
fi
# the cloud is the first arg if it is one, else aws, as for test.sh
CLOUD=aws
case "$(echo "${ARGS[0]:-}" | tr '[:upper:]' '[:lower:]')" in
  aws|azure|gcp) CLOUD=$(echo "${ARGS[0]}" | tr '[:upper:]' '[:lower:]'); ARGS=(${ARGS[@]+"${ARGS[@]:1}"}) ;;
  *) echo "No cloud given, defaulting to $CLOUD" ;;
esac
# the names of the notebooks to run, from the args after the cloud, else all those of the notebooks file
NAMES=()
for arg in ${ARGS[@]+"${ARGS[@]}"}; do
  IFS=',' read -ra parts <<< "$arg"
  NAMES+=("${parts[@]}")
done
if [ ${#NAMES[@]} -eq 0 ]; then
  while IFS='|' read -r name _; do
    NAMES+=("$name")
  done < <(notebook_lines)
fi
if [ ${#NAMES[@]} -eq 0 ]; then
  echo "No notebooks in $NOTEBOOKS_FILE" >&2
  exit 1
fi
# the files of the notebooks, absolute as the afon root is the dir they are run from, checked before anything is set up
NOTEBOOKS=()
for name in "${NAMES[@]}"; do
  file=$(notebook_lines | awk -F'|' -v name="$name" '$1 == name { print $2; exit }')
  if [ -z "$file" ]; then
    echo "Unknown notebook: $name.  The notebooks, in $NOTEBOOKS_FILE:" >&2
    list_notebooks >&2
    exit 1
  fi
  case "$file" in
    /*) notebook="$file" ;;
    *) notebook="$NOTEBOOKS_DIR/$file" ;;
  esac
  if [ ! -f "$notebook" ]; then
    echo "Notebook $name has no file $notebook" >&2
    exit 1
  fi
  NOTEBOOKS+=("$notebook")
done
SPARK_CONNECT_PORT=15002

# Runs the afon command $1 with the args $2...: with afon.sh, if the script runs from the source tree, else with the
# afon-cli.sh of the cli distribution it runs from, or that in AFON_CLI_DIR.
afon() {
  local command="$1"
  shift
  if [ -x "$AFON_ROOT/afon.sh" ]; then
    "$AFON_ROOT/afon.sh" -a "$command" -- "$@"
  else
    SPRING_MAIN_BANNER_MODE="${SPRING_MAIN_BANNER_MODE:-off}" "${AFON_CLI_DIR:-$AFON_ROOT}/afon-cli.sh" "$command" "$@"
  fi
}

# the config of the afon env, if there is one, else the current config
CONFIG_DIR="$AFON_HOME/config${AFON_ENV:+-$AFON_ENV}"
if [ ! -d "$CONFIG_DIR" ]; then
  echo "No config $CONFIG_DIR for the afon env '${AFON_ENV:-}'" >&2
  exit 1
fi

prop() {
  grep -h "^$1=" "$CONFIG_DIR"/*.properties 2>/dev/null | tail -1 | cut -d= -f2- || true
}

require() {
  if [ -z "$2" ]; then
    echo "$1 is not set in $CONFIG_DIR" >&2
    exit 1
  fi
}

CLOUD_PREFIX=$(echo "$CLOUD" | tr '[:lower:]' '[:upper:]')
POLARIS_HOST=$(prop "${CLOUD_PREFIX}_POLARIS_DOMAIN_NAME")
# the KeyCloak of the cloud: its own domain name if it has one, else the neutral one
OIDC_HOST=$(prop "${CLOUD_PREFIX}_OIDC_DOMAIN_NAME")
OIDC_HOST="${OIDC_HOST:-$(prop OIDC_DOMAIN_NAME)}"
require "${CLOUD_PREFIX}_POLARIS_DOMAIN_NAME" "$POLARIS_HOST"
require "${CLOUD_PREFIX}_OIDC_DOMAIN_NAME or OIDC_DOMAIN_NAME" "$OIDC_HOST"

# the python env that runs the notebooks, set up and checked first: its failures are clearer here than in jupyter-execute
if ! afon python-setup; then
  echo "The python env is not ready: see above" >&2
  exit 1
fi

if ! afon check-oidc "$CLOUD" >/dev/null; then
  echo "OIDC provider at $OIDC_HOST is not ready" >&2
  exit 1
fi
echo "OIDC provider at $OIDC_HOST is ready"

TOKEN_LINE="^Access token for user '\([^']*\)' on cloud '[^']*': "
TOKEN_OUTPUT=$(afon check-oidc "$CLOUD" --token user 2>&1 || true)
USER_ID=$(printf '%s\n' "$TOKEN_OUTPUT" | sed -n "s/${TOKEN_LINE}.*/\1/p" | tail -1)
OIDC_TOKEN=$(printf '%s\n' "$TOKEN_OUTPUT" | sed -n "s/${TOKEN_LINE}//p" | tail -1)
if [ -z "$OIDC_TOKEN" ]; then
  echo "check-oidc --token gave no access token for USER_ID:" >&2
  printf '%s\n' "$TOKEN_OUTPUT" | tail -20 >&2
  exit 1
fi
unset TOKEN_OUTPUT
echo "Got an access token for user '$USER_ID' from $OIDC_HOST"

export OIDC_TOKEN
export SPARK_REMOTE="sc://localhost:$SPARK_CONNECT_PORT"
export POLARIS_DOMAIN_NAME="$POLARIS_HOST"
export OIDC_DOMAIN_NAME="$OIDC_HOST"
# The catalogs of the cloud, named <cloud> and <cloud>_forbidden, as the notebook defaults to those of AWS, which the
# Polaris of another cloud has too, but without the table that the setup of its stack writes to its own catalog.
export AFON_CATALOG="${AFON_CATALOG:-$(echo "$CLOUD" | tr '[:upper:]' '[:lower:]')}"
export AFON_FORBIDDEN_CATALOG="${AFON_FORBIDDEN_CATALOG:-${AFON_CATALOG%%_*}_forbidden}"

kubectl port-forward pod/spark-connector-0-driver "$SPARK_CONNECT_PORT:$SPARK_CONNECT_PORT" >/dev/null &
PORT_FORWARD_PID=$!
trap 'kill $PORT_FORWARD_PID 2>/dev/null || true' EXIT
sleep 4
if ! kill -0 "$PORT_FORWARD_PID" 2>/dev/null; then
  echo "Could not port-forward pod/spark-connector-0-driver with kubectl context $(kubectl config current-context)" >&2
  exit 1
fi

cd "$AFON_ROOT"
FAILED=()
for i in "${!NOTEBOOKS[@]}"; do
  notebook="${NOTEBOOKS[$i]}"
  # absolute: jupyter resolves it relative to the notebook
  output="$AFON_ROOT/smoke/target/$(basename "$notebook")"
  mkdir -p "$(dirname "$output")"
  echo "=== Running notebook ${NAMES[$i]}"
  if afon jupyter-execute "$notebook" "$output"; then
    echo "=== Notebook ${NAMES[$i]} passed"
  else
    echo "=== Notebook ${NAMES[$i]} FAILED" >&2
    FAILED+=("${NAMES[$i]}")
  fi
done
if [ ${#FAILED[@]} -gt 0 ]; then
  echo "Notebooks failed (${#FAILED[@]} of ${#NOTEBOOKS[@]}): ${FAILED[*]}" >&2
  exit 1
fi
echo "All notebooks (${#NOTEBOOKS[@]}) passed"
