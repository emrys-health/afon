#!/usr/bin/env bash
#
# Runs smoke/notebooks/SparkConnectOidcTest.ipynb from this machine against a deployed stack, as a
# Jupyter pod would: via Spark Connect, with catalogs authenticated by the user's OIDC token.
#
# usage: smoke/run-oidc-notebook.sh [cloud]
#   cloud  the cloud whose Polaris to use (default: aws), read from <CLOUD>_POLARIS_DOMAIN_NAME.  The notebook uses its
#          catalogs <cloud> and <cloud>_forbidden, unless AFON_CATALOG and AFON_FORBIDDEN_CATALOG are set.
#
# The executed notebook, with its outputs, is written to smoke/target/<notebook name>, leaving the
# original clean.
#
# Checks Keycloak is serving requests with './afon.sh -a check-oidc <cloud>', then
# gets USER_ID's OIDC access token from Keycloak with './afon.sh -a show-oidc-token <cloud> user',
# port-forwards the Spark Connect driver of the current kubectl context, then runs the notebook via
# './afon.sh -a jupyter-execute'.  Also reads <CLOUD>_POLARIS_DOMAIN_NAME and <CLOUD>_OIDC_DOMAIN_NAME (else OIDC_DOMAIN_NAME) from
# $AFON_HOME/config/*.properties (AFON_HOME defaults to ~/.afon), for the notebook.  Only those properties
# are read, rather than sourcing the secrets: sourcing them would also export cloud credentials that change
# the identity kubectl uses.  The token is never printed.

set -euo pipefail

AFON_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AFON_HOME="${AFON_HOME:-$HOME/.afon}"
CLOUD="${1:-aws}"
NOTEBOOK="smoke/notebooks/SparkConnectOidcTest.ipynb"
OUTPUT="$AFON_ROOT/smoke/target/$(basename "$NOTEBOOK")" # absolute: jupyter resolves it relative to the notebook
SPARK_CONNECT_PORT=15002

prop() {
  grep -h "^$1=" "$AFON_HOME"/config/*.properties 2>/dev/null | tail -1 | cut -d= -f2- || true
}

require() {
  if [ -z "$2" ]; then
    echo "$1 is not set in $AFON_HOME/config" >&2
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

if ! "$AFON_ROOT/afon.sh" -a check-oidc "$CLOUD" >/dev/null; then
  echo "OIDC provider at $OIDC_HOST is not ready" >&2
  exit 1
fi
echo "OIDC provider at $OIDC_HOST is ready"

TOKEN_LINE="^Access token for user '\([^']*\)' on cloud '[^']*': "
TOKEN_OUTPUT=$("$AFON_ROOT/afon.sh" -a show-oidc-token "$CLOUD" user 2>&1 || true)
USER_ID=$(printf '%s\n' "$TOKEN_OUTPUT" | sed -n "s/${TOKEN_LINE}.*/\1/p" | tail -1)
OIDC_TOKEN=$(printf '%s\n' "$TOKEN_OUTPUT" | sed -n "s/${TOKEN_LINE}//p" | tail -1)
if [ -z "$OIDC_TOKEN" ]; then
  echo "show-oidc-token gave no access token for USER_ID:" >&2
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
mkdir -p "$(dirname "$OUTPUT")"
./afon.sh -a jupyter-execute -sa "$NOTEBOOK $OUTPUT"
