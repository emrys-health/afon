#!/usr/bin/env bash
# Sanity-check the Polaris database on the current kubectl cluster: confirms the database is
# reachable, the Polaris schema exists, reports its version, and confirms the root principal was
# actually bootstrapped (not just that the polaris-db-init Job reported success).
#
# It never reads the DB credentials into this shell: it spins up a short-lived pod that mounts the
# same "polaris-secret" Secret the Polaris Deployment uses, runs the checks inside that pod, and
# only the query results (never the password) come back over kubectl exec/logs.
#
# Usage:
#   ./polaris-db.sh [namespace]
#
#   [namespace]  Namespace the Polaris Secret/Deployment live in. Defaults to "default".
#
# Env vars:
#   CONTEXT      kubectl context to use (defaults to current context).
#   SECRET_NAME  Name of the Secret holding the Polaris env file. Defaults to "polaris-secret".
#   SECRET_KEY   Key within the Secret holding the env script. Defaults to "polaris_env.sh".
#
# Examples:
#   ./polaris-db.sh
#   ./polaris-db.sh default
#   CONTEXT=my-aks-cluster ./polaris-db.sh

set -euo pipefail

AFON_ROOT="${AFON_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
source "$AFON_ROOT/scripts/stack/docker-image-source.sh"

NAMESPACE="${1:-default}"
SECRET_NAME="${SECRET_NAME:-polaris-secret}"
SECRET_KEY="${SECRET_KEY:-polaris_env.sh}"

if ! command -v kubectl >/dev/null 2>&1; then
  echo "kubectl not found on PATH" >&2
  exit 1
fi

CONTEXT="${CONTEXT:-$(kubectl config current-context)}"
echo "Using kubectl context: $CONTEXT, namespace: $NAMESPACE"

if ! kubectl --context "$CONTEXT" -n "$NAMESPACE" get secret "$SECRET_NAME" >/dev/null 2>&1; then
  echo "Secret '$SECRET_NAME' not found in namespace '$NAMESPACE' - has Polaris been deployed here?" >&2
  exit 1
fi

# the image of the check pod, from docker-images.yaml
CHECK_IMAGE="$(docker_image postgres-alpine)" || exit 1

POD_NAME="polaris-db-check-$$-$(date +%s)"

cleanup() {
  kubectl --context "$CONTEXT" -n "$NAMESPACE" delete pod "$POD_NAME" --ignore-not-found >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

echo "Starting check pod $POD_NAME..."
kubectl --context "$CONTEXT" -n "$NAMESPACE" apply -f - >/dev/null <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: $POD_NAME
  labels:
    app: polaris-db-check
spec:
  restartPolicy: Never
  containers:
    - name: polaris-db-check
      image: $CHECK_IMAGE
      command: ["sleep", "300"]
      volumeMounts:
        - name: secret-volume
          mountPath: /etc/app-secrets
          readOnly: true
  volumes:
    - name: secret-volume
      secret:
        secretName: $SECRET_NAME
EOF

kubectl --context "$CONTEXT" -n "$NAMESPACE" wait --for=condition=Ready "pod/$POD_NAME" --timeout=60s >/dev/null

# Pull only the individual QUARKUS_DATASOURCE_* values out of the env file rather than sourcing it
# wholesale - the file also carries other exports (Polaris/KeyCloak config) that aren't needed here
# and, being a free-form shell script, aren't guaranteed to always be valid POSIX `export` syntax.
RESULT="$(kubectl --context "$CONTEXT" -n "$NAMESPACE" exec "$POD_NAME" -- sh -c '
  set -e
  ENV_FILE="/etc/app-secrets/'"$SECRET_KEY"'"
  JDBC_URL=$(grep -m1 "^export QUARKUS_DATASOURCE_JDBC_URL=" "$ENV_FILE" | sed -E "s/^export QUARKUS_DATASOURCE_JDBC_URL=\"?([^\"]*)\"?\$/\1/")
  # the values are written with or without surrounding double quotes, eg the password is quoted and the username is not
  DB_USER=$(grep -m1 "^export QUARKUS_DATASOURCE_USERNAME=" "$ENV_FILE" | sed -E "s/^export QUARKUS_DATASOURCE_USERNAME=//; s/^\"(.*)\"\$/\1/")
  export PGPASSWORD=$(grep -m1 "^export QUARKUS_DATASOURCE_PASSWORD=" "$ENV_FILE" | sed -E "s/^export QUARKUS_DATASOURCE_PASSWORD=//; s/^\"(.*)\"\$/\1/")

  if [ -z "$JDBC_URL" ] || [ -z "$DB_USER" ] || [ -z "$PGPASSWORD" ]; then
    echo "Failed to find QUARKUS_DATASOURCE_* entries in $ENV_FILE"
    exit 1
  fi

  CONN=${JDBC_URL#jdbc:}
  echo "target=$CONN"

  if ! CONNECT_ERROR=$(psql "$CONN" -U "$DB_USER" -tAc "SELECT 1" 2>&1 >/dev/null); then
    # what psql says, ie why it could not connect, eg the host, the SSL or the credentials: it never has the password in it
    echo "$CONNECT_ERROR" | head -3 | sed "s/^/  psql: /"
    echo "Failed to connect to the database at $CONN"
    exit 1
  fi

  SCHEMA=$(psql "$CONN" -U "$DB_USER" -tAc "SELECT schema_name FROM information_schema.schemata WHERE schema_name = '"'"'polaris_schema'"'"'")
  if [ -z "$SCHEMA" ]; then
    echo "Failed to find polaris_schema - the database exists but Polaris has not been bootstrapped"
    exit 1
  fi

  echo "Polaris Database"
  echo "- schema=$SCHEMA"
  echo "- version=$(psql "$CONN" -U "$DB_USER" -tAc "SELECT version_value FROM ${SCHEMA}.version WHERE version_key = '"'"'version'"'"'")"
  echo "- entities=$(psql "$CONN" -U "$DB_USER" -tAc "SELECT count(*) FROM ${SCHEMA}.entities")"
  echo "- root_principal=$(psql "$CONN" -U "$DB_USER" -tAc "SELECT count(*) FROM ${SCHEMA}.entities WHERE name='"'"'root'"'"'")"
  echo "- principal_auth_rows=$(psql "$CONN" -U "$DB_USER" -tAc "SELECT count(*) FROM ${SCHEMA}.principal_authentication_data")"
' 2>&1)" || true # a failed check exits non-zero: set -e would stop the script here, before it can say what failed

echo
echo "$RESULT" | grep -v '^Failed' || true

ERROR_LINE="$(echo "$RESULT" | grep '^Failed' || true)"
if [ -n "$ERROR_LINE" ]; then
  echo
  echo "Failed: ${ERROR_LINE#error=}" >&2
  exit 1
fi

ROOT_PRESENT="$(echo "$RESULT" | sed -n 's/^root_principal=//p')"
AUTH_ROWS="$(echo "$RESULT" | sed -n 's/^principal_auth_rows=//p')"

echo
if [ "$ROOT_PRESENT" != "0" ] && [ "$AUTH_ROWS" != "0" ]; then
  echo "Polaris schema present, root principal bootstrapped with credentials."
else
  echo "Failed: Polaris schema present but root principal or its credentials are missing." >&2
  exit 1
fi
