#!/usr/bin/env bash
#
# Runs smoke/notebooks/SparkConnectOidcTest.ipynb from this machine against a deployed stack, as a
# Jupyter pod would: via Spark Connect, with catalogs authenticated by the user's OIDC token.
#
# usage: smoke/run-oidc-notebook.sh [cloud]
#   cloud  the cloud whose Polaris to use (default: aws), read from <CLOUD>_POLARIS_DOMAIN_NAME
#
# The executed notebook, with its outputs, is written to smoke/target/<notebook name>, leaving the
# original clean.
#
# Reads OIDC_DOMAIN_NAME, USER_POLARIS_ID and USER_POLARIS_SECRET from $AFON_HOME/config/*.properties
# (AFON_HOME defaults to ~/.afon), gets USER_POLARIS_ID's access token from Keycloak, port-forwards the
# Spark Connect driver of the current kubectl context, then runs the notebook via
# './afon.sh -a jupyter-execute'.  Only those properties are read, rather than sourcing the secrets:
# sourcing them would also export cloud credentials that change the identity kubectl uses.
# The secret and the token are never printed.

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
OIDC_HOST=$(prop OIDC_DOMAIN_NAME)
USER_ID=$(prop USER_POLARIS_ID)
USER_SECRET=$(prop USER_POLARIS_SECRET)
require "${CLOUD_PREFIX}_POLARIS_DOMAIN_NAME" "$POLARIS_HOST"
require OIDC_DOMAIN_NAME "$OIDC_HOST"
require USER_POLARIS_ID "$USER_ID"
require USER_POLARIS_SECRET "$USER_SECRET"

# The same password grant as KeyCloakProvider.userToken, where each user is also a confidential client.
OIDC_TOKEN=$(curl -s -m 30 \
  --data-urlencode "grant_type=password" \
  --data-urlencode "client_id=$USER_ID" \
  --data-urlencode "client_secret=$USER_SECRET" \
  --data-urlencode "username=$USER_ID" \
  --data-urlencode "password=$USER_SECRET" \
  "https://$OIDC_HOST/realms/polaris/protocol/openid-connect/token" \
  | python3 -c "import json,sys; print(json.load(sys.stdin).get('access_token', ''))" || true)
unset USER_SECRET
if [ -z "$OIDC_TOKEN" ]; then
  echo "Keycloak at $OIDC_HOST gave no access token for $USER_ID" >&2
  exit 1
fi
echo "Got an access token for $USER_ID from $OIDC_HOST"

export OIDC_TOKEN
export SPARK_REMOTE="sc://localhost:$SPARK_CONNECT_PORT"
export POLARIS_DOMAIN_NAME="$POLARIS_HOST"
export OIDC_DOMAIN_NAME="$OIDC_HOST"

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
