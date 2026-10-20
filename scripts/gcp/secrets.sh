source $AFON_ROOT/scripts/config/afon-source.sh gcp

usage(){
  echo "Usage: ${BASH_SOURCE[0]} <store|show|list|delete> [<cert.pem> <key.pem>] [<domain-name>]"
  echo "Stores in GCP Secret Manager the TLS cert and key for the domain name, by default $GCP_POLARIS_DOMAIN_NAME."
  echo " - store <cert.pem> <key.pem> [<domain-name>]: create or add a new version of the cert and key secrets"
  echo " - show [<domain-name>]: show whether the cert and key secrets exist"
  echo " - list: list the secrets in the project"
  echo " - delete [<domain-name>]: delete the cert and key secrets"
}

ACTION="${1:-}"

PROJECT_ID="${GCP_PROJECT_ID:?GCP_PROJECT_ID must be set in afon-gcp.properties}"

# The domain name of the cert: the last arg of store, or the only arg of show and delete, if given, else that of Polaris
case "$ACTION" in
  store) DOMAIN_NAME="${4:-$GCP_POLARIS_DOMAIN_NAME}" ;;
  *)     DOMAIN_NAME="${2:-$GCP_POLARIS_DOMAIN_NAME}" ;;
esac

# Same naming convention as Azure's Key Vault secrets, which the stack's restore step reads from
KEBAB_DOMAIN_NAME="${DOMAIN_NAME//./-}"
GCP_TLS_CRT_NAME="${KEBAB_DOMAIN_NAME}-tls-crt"
GCP_TLS_KEY_NAME="${KEBAB_DOMAIN_NAME}-tls-key"

require_domain_name() {
  if [ -z "$DOMAIN_NAME" ]; then
    echo "- No domain name: set GCP_POLARIS_DOMAIN_NAME in afon-gcp.properties, or give it"
    exit 1
  fi
}

secret_exists() {
  gcloud secrets describe "$1" --project=$PROJECT_ID >/dev/null 2>&1
}

# Creates the secret if needed, then adds the base64-encoded PEM file as its latest version
# Requires role "Secret Manager Admin" (or Secret Manager Secret Version Adder on an existing secret)
store_secret() {
  NAME="$1"
  PEM="$2"
  if secret_exists "$NAME"; then
    echo "- Secret $NAME already exists, adding a new version"
  else
    echo "- Creating secret $NAME"
    gcloud secrets create "$NAME" --replication-policy=automatic --project=$PROJECT_ID || exit 1
  fi
  base64 < "$PEM" | tr -d '\n' | gcloud secrets versions add "$NAME" --data-file=- --project=$PROJECT_ID || exit 1
}

case "$ACTION" in
  store)
    require_domain_name
    CERT_PEM="${2:-}"
    KEY_PEM="${3:-}"
    if [ ! -f "$CERT_PEM" ] || [ ! -f "$KEY_PEM" ]; then
      echo "- Cert and key PEM files must be provided and exist"
      usage
      exit 1
    fi

    # Enabling an API needs serviceusage.services.enable, typically held by a project admin only,
    # so only attempt it if the API isn't already enabled
    if [ -z "$(gcloud services list --enabled --project=$PROJECT_ID --filter="name:secretmanager.googleapis.com" --format="value(name)" 2>/dev/null)" ]; then
      echo "- Enabling Secret Manager API"
      gcloud services enable secretmanager.googleapis.com --project=$PROJECT_ID || {
        echo "- Failed to enable the Secret Manager API - ask a project admin to run:"
        echo "    gcloud services enable secretmanager.googleapis.com --project=$PROJECT_ID"
        exit 1
      }
    fi

    echo "- Caching $DOMAIN_NAME cert in Secret Manager of $PROJECT_ID"
    store_secret "$GCP_TLS_CRT_NAME" "$CERT_PEM"
    store_secret "$GCP_TLS_KEY_NAME" "$KEY_PEM"
    echo "Cert secrets $GCP_TLS_CRT_NAME and $GCP_TLS_KEY_NAME cached in $PROJECT_ID"
    ;;

  show)
    require_domain_name
    for NAME in "$GCP_TLS_CRT_NAME" "$GCP_TLS_KEY_NAME"; do
      if secret_exists "$NAME"; then
        echo "- Secret $NAME exists"
      else
        echo "- Secret $NAME does not exist"
      fi
    done
    ;;

  list)
    echo "- Secrets in $PROJECT_ID:"
    gcloud secrets list --project=$PROJECT_ID --format="table(name.basename(), createTime, replication.automatic.yesno(yes=automatic, no=''))"
    ;;

  delete)
    require_domain_name
    for NAME in "$GCP_TLS_CRT_NAME" "$GCP_TLS_KEY_NAME"; do
      if secret_exists "$NAME"; then
        echo "- Deleting secret $NAME"
        gcloud secrets delete "$NAME" --project=$PROJECT_ID --quiet || exit 1
      else
        echo "- Secret $NAME does not exist, skipping"
      fi
    done
    ;;

  *)
    usage
    exit 1
    ;;
esac
