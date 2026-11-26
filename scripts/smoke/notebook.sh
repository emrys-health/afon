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
#   --env     the afon env: the config read, and used by every afon command, is
#             $AFON_HOME/config-<env>.  Exported as AFON_ENV.  Default: the AFON_ENV already set, else the current config.
#   --notebooks
#             the CSV file of the notebooks (default: scripts/smoke/notebooks.csv)
#   --list    lists the notebooks and exits
#   --local   in a project's TRE, runs the notebooks from this machine, not in the pod of its smoke member
#
# The notebooks are read from the notebooks file, whose first line is the header 'name|file|description', and
# each other line a notebook: its name, its file, in scripts/smoke/notebooks if not an absolute path, and its description.  Blank lines and lines
# starting with '#' are ignored.
#
# Each executed notebook, with its outputs, is written to $AFON_HOME/smoke/notebooks/<notebook file name>, leaving the
# original clean.
#
# In a project's TRE (the config has SMOKE_MEMBER) the notebooks are those of scripts/smoke/project-notebooks.csv, unless --notebooks gives another file, and, unless
# --local, nothing below runs: the afon command 'smoke-pod-notebook' signs the smoke member in to the TRE's JupyterHub and runs the notebooks in the member's notebook pod, as a
# member's are, with the executed notebooks written to $AFON_HOME/smoke/notebooks.  With --local, or elsewhere, they are run from this machine:
#
# Sets up and checks the python env with the afon command 'python-setup', checks Keycloak is serving requests with
# 'check-oidc <cloud>', then
# gets USER_ID's OIDC access token from Keycloak with 'check-oidc <cloud> --token user', or, in a project's TRE, that of the smoke member with '--token smoke-member',
# port-forwards the Spark Connect driver of the cluster of the stack (KUBECONFIG, else that of '<cloud>-update-kube-config tre'), then runs the notebook with
# 'jupyter-execute'.  The commands are run as 'smoke-notebook <cloud> <notebook>'.  Also reads
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
LOCAL=
NOTEBOOKS_GIVEN=
ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --notebooks|-n)
      if [ -z "${2:-}" ]; then
        echo "Option $1 requires a value" >&2
        exit 1
      fi
      NOTEBOOKS_FILE="$2"; NOTEBOOKS_GIVEN=true; shift 2 ;;
    --env|-e)
      if [ -z "${2:-}" ]; then
        echo "Option $1 requires a value" >&2
        exit 1
      fi
      export AFON_ENV="$2"; shift 2 ;;
    --list|-l) LIST=true; shift ;;
    --local) LOCAL=true; shift ;;
    --help|-h) usage; exit 0 ;;
    *) ARGS+=("$1"); shift ;;
  esac
done
CONFIG_DIR="$AFON_HOME/config${AFON_ENV:+-$AFON_ENV}"

# The raw value of the property $1, as written in the config, eg aws-oidc.${ENV_NAME}.click.
raw_prop() {
  grep -h "^$1=" "$CONFIG_DIR"/*.properties 2>/dev/null | tail -1 | cut -d= -f2- || true
}

# The value of the property $1, with the references to other properties, ${NAME}, replaced by their values, as the config does, eg
# aws-oidc.emrys2.click.  A reference to a property that is not set is left as it is.  Nested references are resolved, up to 5 deep.
prop() {
  local value name replacement rest depth=0
  value=$(raw_prop "$1")
  while [ "$depth" -lt 5 ] && [[ "$value" =~ \$\{([A-Za-z0-9_]+)\} ]]; do
    name="${BASH_REMATCH[1]}"
    replacement=$(raw_prop "$name")
    [ -n "$replacement" ] || break
    value="${value//\$\{$name\}/$replacement}"
    depth=$((depth + 1))
  done
  printf '%s\n' "$value"
}


# A project's TRE, which the config says by naming its smoke member, SMOKE_MEMBER: its notebooks are those of its own file, unless --notebooks gave
# another, and run with that member's token, as only the tokens of the project are admitted there
SMOKE_MEMBER=$(prop SMOKE_MEMBER)
if [ -n "$SMOKE_MEMBER" ] && [ -z "$NOTEBOOKS_GIVEN" ]; then
  NOTEBOOKS_FILE="$AFON_ROOT/scripts/smoke/project-notebooks.csv"
fi
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
# the port that the Spark Connect driver listens on, in its pod
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

if [ ! -d "$CONFIG_DIR" ]; then
  echo "No config $CONFIG_DIR for the afon env '${AFON_ENV:-}'" >&2
  exit 1
fi

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

# In a project's TRE the notebooks are run, by default, in the notebook pod of its smoke member, signed in to the JupyterHub as a member is: they are run where a
# member's are, with the token that the hub gives the pod, the cluster's own addresses and the pod's network policy.  The afon command does it all.  With --local they
# are run from this machine as below, with the smoke member's token from Keycloak and port-forwards to Spark Connect and Trino, which tests the reads but not the pod.
if [ -n "$SMOKE_MEMBER" ] && [ -z "$LOCAL" ]; then
  RESULTS_DIR="$AFON_HOME/smoke/notebooks"
  mkdir -p "$RESULTS_DIR"
  afon smoke-pod-notebook "$RESULTS_DIR" "${NOTEBOOKS[@]}"
  exit $?
fi

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

# the token of USER_ID, who is the admin, or, in a project's TRE, which admits only the tokens of its project, that of its smoke member,
# signed in as a member is, with the project and the second factor in it
TOKEN_KIND=user
[ -z "$SMOKE_MEMBER" ] || TOKEN_KIND=smoke-member
TOKEN_LINE="^Access token for (user|smoke member) '([^']*)' on cloud '[^']*': "
TOKEN_OUTPUT=$(afon check-oidc "$CLOUD" --token "$TOKEN_KIND" 2>&1 || true)
USER_ID=$(printf '%s\n' "$TOKEN_OUTPUT" | sed -n -E "s/${TOKEN_LINE}.*/\2/p" | tail -1)
OIDC_TOKEN=$(printf '%s\n' "$TOKEN_OUTPUT" | sed -n -E "s/${TOKEN_LINE}//p" | tail -1)
if [ -z "$OIDC_TOKEN" ]; then
  echo "check-oidc --token $TOKEN_KIND gave no access token:" >&2
  printf '%s\n' "$TOKEN_OUTPUT" | tail -20 >&2
  exit 1
fi
unset TOKEN_OUTPUT
echo "Got an access token for user '$USER_ID' from $OIDC_HOST"

export OIDC_TOKEN
export POLARIS_DOMAIN_NAME="$POLARIS_HOST"
export OIDC_DOMAIN_NAME="$OIDC_HOST"
# The catalogs of the cloud, named <cloud> and <cloud>_forbidden, as the notebook defaults to those of AWS, which the
# Polaris of another cloud has too, but without the table that the smoke test spark-polaris writes to the catalog of its own stack, which must have been run first.
export AFON_CATALOG="${AFON_CATALOG:-$(echo "$CLOUD" | tr '[:upper:]' '[:lower:]')}"
export AFON_FORBIDDEN_CATALOG="${AFON_FORBIDDEN_CATALOG:-${AFON_CATALOG%%_*}_forbidden}"

# The kubeconfig of the stack, if KUBECONFIG is not set already, eg by the regression suite: that of the full stack cluster of the
# cloud in the env, with the afon command '<cloud>-update-kube-config tre', which prints it.  The default ~/.kube/config is neither written nor read.
if [ -z "${KUBECONFIG:-}" ]; then
  KUBE_CONFIG_OUTPUT=$(afon "$CLOUD-update-kube-config" tre 2>&1) || {
    printf '%s\n' "$KUBE_CONFIG_OUTPUT" | tail -20 >&2
    echo "Could not get the kubeconfig of the $CLOUD stack: is it deployed?  Or set KUBECONFIG to its kubeconfig" >&2
    exit 1
  }
  KUBECONFIG=$(printf '%s\n' "$KUBE_CONFIG_OUTPUT" | sed -n 's/^kubeconfig of .*: export KUBECONFIG=//p' | tail -1)
  unset KUBE_CONFIG_OUTPUT
  if [ -z "$KUBECONFIG" ] || [ ! -s "$KUBECONFIG" ]; then
    echo "No kubeconfig for the $CLOUD stack: set KUBECONFIG to its kubeconfig" >&2
    exit 1
  fi
  export KUBECONFIG
  echo "Using the kubeconfig $KUBECONFIG"
fi
# Forwards the driver's port to a free local port, which kubectl picks and reports, so that runs at the same time, eg for
# different clouds, do not compete for one.
PORT_FORWARD_LOG=$(mktemp)
kubectl port-forward pod/spark-connector-0-driver ":$SPARK_CONNECT_PORT" >"$PORT_FORWARD_LOG" 2>&1 &
PORT_FORWARD_PID=$!
trap 'kill $PORT_FORWARD_PID 2>/dev/null || true; rm -f "$PORT_FORWARD_LOG"' EXIT
LOCAL_PORT=
for _ in $(seq 1 30); do
  LOCAL_PORT=$(sed -n 's/^Forwarding from 127\.0\.0\.1:\([0-9][0-9]*\) -> .*/\1/p' "$PORT_FORWARD_LOG" | head -1)
  if [ -n "$LOCAL_PORT" ] || ! kill -0 "$PORT_FORWARD_PID" 2>/dev/null; then
    break
  fi
  sleep 0.5
done
if [ -z "$LOCAL_PORT" ]; then
  echo "Could not port-forward pod/spark-connector-0-driver with KUBECONFIG $KUBECONFIG:" >&2
  cat "$PORT_FORWARD_LOG" >&2
  exit 1
fi
export SPARK_REMOTE="sc://localhost:$LOCAL_PORT"

# In a project's TRE, the notebooks that read through Trino reach its coordinator as a pod does, by its HTTPS port, which this forwards to a free local port, trusting
# its self-signed certificate, which names localhost: AFON_TRINO_HOST, AFON_TRINO_PORT and TRINO_CA_CERT tell the notebook
if [ -n "$SMOKE_MEMBER" ] && printf '%s\n' "${NOTEBOOKS[@]}" | grep -qi trino; then
  TRINO_POD=$(kubectl get pods -o name | grep 'pod/trino-coordinator' | head -1)
  if [ -z "$TRINO_POD" ]; then
    echo "No trino-coordinator pod in the cluster of KUBECONFIG $KUBECONFIG: is Trino deployed?" >&2
    exit 1
  fi
  TRINO_CA_FILE=$(mktemp)
  kubectl get secret trino-tls -o 'jsonpath={.data.ca\.crt}' | base64 -d > "$TRINO_CA_FILE"
  TRINO_FORWARD_LOG=$(mktemp)
  kubectl port-forward "$TRINO_POD" ":8443" >"$TRINO_FORWARD_LOG" 2>&1 &
  TRINO_FORWARD_PID=$!
  trap 'kill $PORT_FORWARD_PID $TRINO_FORWARD_PID 2>/dev/null || true; rm -f "$PORT_FORWARD_LOG" "$TRINO_FORWARD_LOG" "$TRINO_CA_FILE"' EXIT
  TRINO_LOCAL_PORT=
  for _ in $(seq 1 30); do
    TRINO_LOCAL_PORT=$(sed -n 's/^Forwarding from 127\.0\.0\.1:\([0-9][0-9]*\) -> .*/\1/p' "$TRINO_FORWARD_LOG" | head -1)
    if [ -n "$TRINO_LOCAL_PORT" ] || ! kill -0 "$TRINO_FORWARD_PID" 2>/dev/null; then
      break
    fi
    sleep 0.5
  done
  if [ -z "$TRINO_LOCAL_PORT" ]; then
    echo "Could not port-forward $TRINO_POD:" >&2
    cat "$TRINO_FORWARD_LOG" >&2
    exit 1
  fi
  export AFON_TRINO_HOST=localhost AFON_TRINO_PORT="$TRINO_LOCAL_PORT" TRINO_CA_CERT="$TRINO_CA_FILE"
fi

cd "$AFON_ROOT"
FAILED=()
for i in "${!NOTEBOOKS[@]}"; do
  notebook="${NOTEBOOKS[$i]}"
  # absolute: jupyter resolves it relative to the notebook
  output="$AFON_HOME/smoke/notebooks/$(basename "$notebook")"
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
