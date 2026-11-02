source $AFON_ROOT/scripts/config/afon-source.sh gcp

# The stack the env is created for, given as its id, or its role in the cloud (identity, catalog or tre), which is required, as what the
# env needs depends on it.  What the stack needs is looked up in stacks.yaml and given as the
# AFON_STACK_* env vars: AFON_STACK_ID, AFON_STACK_ROLE, AFON_STACK_DOMAINS and AFON_STACK_COMPONENTS, by the launcher when run by
# afon-cli.sh, see ScriptMain, else by asking the ops command stack-needs, as when run by afon.sh, see scripts/stack/needs-source.sh.
STACK_ID=
for ARG in "$@"; do
  case "$ARG" in
    gcp-*|identity|catalog|tre) STACK_ID="$ARG" ;;
    *) echo "-- Unknown argument '$ARG': expected a stack by its id or role"; exit 1 ;;
  esac
done
if [ -z "$STACK_ID" ]; then
  echo "-- Requires the stack the env is created for, by its id or role: gcp-create <stack-id|identity|catalog|tre>"
  exit 1
fi
source $AFON_ROOT/scripts/stack/needs-source.sh
stack_needs gcp "$STACK_ID" || exit 1
STACK_ID="$AFON_STACK_ID"

# Whether the stack deploys the kind of component, as named in stacks.yaml, eg Git.
has_component() {
  case " ${AFON_STACK_COMPONENTS:-} " in
    *" $1 "*) return 0 ;;
    *) return 1 ;;
  esac
}

PROJECT_ID="${GCP_PROJECT_ID:?GCP_PROJECT_ID must be set in afon-gcp.properties}"
EXTERNAL_SA="external-sa@$PROJECT_ID.iam.gserviceaccount.com"
BLIRC_SA="blirc-600159231093-i76b@gcp-sa-biglakerestcatalog.iam.gserviceaccount.com"

# Reads GCP_BUCKET_NAME_<id>/GCP_CATALOG_TYPE_<id>/GCP_ADMIN_GRANTS_<id>, echoing "" instead of
# erroring under `set -u` if a catalog (eg a custom type with no bucket of its own) never defined
# one. GCP_ADMIN_GRANTS_<id> defaults to "true" when unset, matching AfonCatalogs' own default.
bucket_of() {
  local VAR="GCP_BUCKET_NAME_$1"
  if [ -n "${!VAR+x}" ]; then
    echo "${!VAR}"
  fi
}
type_of() {
  local VAR="GCP_CATALOG_TYPE_$1"
  if [ -n "${!VAR+x}" ]; then
    echo "${!VAR}"
  fi
}
admin_grants_of() {
  local VAR="GCP_ADMIN_GRANTS_$1"
  if [ -n "${!VAR+x}" ]; then
    echo "${!VAR}"
  else
    echo "true"
  fi
}

IFS=',' read -ra CATALOG_IDS <<< "$GCP_CATALOG_IDS"

## Service Accounts

if gcloud iam service-accounts describe $GCP_SERVICE_ACCOUNT >/dev/null 2>&1; then
  echo "- Service account $GCP_SERVICE_ACCOUNT already exists, skipping"
else
  echo "- Creating service account my-vendable-sa"
  gcloud iam service-accounts create my-vendable-sa --display-name "Vendable Service Account" --project=$PROJECT_ID || exit 1
fi

if [ -n "${GCP_USER_ACCOUNT:-}" ]; then
  if gcloud iam service-accounts get-iam-policy $GCP_SERVICE_ACCOUNT --flatten="bindings[].members" --filter="bindings.role=roles/iam.serviceAccountTokenCreator AND bindings.members=user:$GCP_USER_ACCOUNT" --format="value(bindings.members)" 2>/dev/null | grep -q .; then
    echo "- Role iam.serviceAccountTokenCreator already granted to $GCP_USER_ACCOUNT on $GCP_SERVICE_ACCOUNT, skipping"
  else
    echo "- Granting iam.serviceAccountTokenCreator on $GCP_SERVICE_ACCOUNT to $GCP_USER_ACCOUNT"
    gcloud iam service-accounts add-iam-policy-binding $GCP_SERVICE_ACCOUNT --member="user:$GCP_USER_ACCOUNT" --role="roles/iam.serviceAccountTokenCreator" || exit 1
  fi
fi

if gcloud iam service-accounts describe $EXTERNAL_SA >/dev/null 2>&1; then
  echo "- Service account $EXTERNAL_SA already exists, skipping"
else
  echo "- Creating service account external-sa"
  gcloud iam service-accounts create external-sa --display-name "Mock External Service Account" --project=$PROJECT_ID || exit 1
fi

## Cross-SA impersonation grants
if gcloud iam service-accounts get-iam-policy $EXTERNAL_SA --flatten="bindings[].members" --filter="bindings.role=roles/iam.serviceAccountTokenCreator AND bindings.members=serviceAccount:$GCP_SERVICE_ACCOUNT" --format="value(bindings.members)" 2>/dev/null | grep -q .; then
  echo "- Role iam.serviceAccountTokenCreator already granted to $GCP_SERVICE_ACCOUNT on $EXTERNAL_SA, skipping"
else
  echo "- Granting iam.serviceAccountTokenCreator on $EXTERNAL_SA to $GCP_SERVICE_ACCOUNT"
  gcloud iam service-accounts add-iam-policy-binding $EXTERNAL_SA --member="serviceAccount:$GCP_SERVICE_ACCOUNT" --role="roles/iam.serviceAccountTokenCreator" || exit 1
fi

if gcloud iam service-accounts get-iam-policy $GCP_SERVICE_ACCOUNT --flatten="bindings[].members" --filter="bindings.role=roles/iam.serviceAccountTokenCreator AND bindings.members=serviceAccount:$BLIRC_SA" --format="value(bindings.members)" 2>/dev/null | grep -q .; then
  echo "- Role iam.serviceAccountTokenCreator already granted to $BLIRC_SA on $GCP_SERVICE_ACCOUNT, skipping"
else
  echo "- Granting iam.serviceAccountTokenCreator on $GCP_SERVICE_ACCOUNT to $BLIRC_SA"
  gcloud iam service-accounts add-iam-policy-binding \
      $GCP_SERVICE_ACCOUNT \
      --member="serviceAccount:$BLIRC_SA" \
      --role="roles/iam.serviceAccountTokenCreator" \
      --project $PROJECT_ID || exit 1
fi
gcloud iam service-accounts get-iam-policy $GCP_SERVICE_ACCOUNT

## Storage Buckets

# A bucket is created for every catalog, including "external"-typed ones (eg gcp_external), which are
# granted to external-sa below.
for ID in "${CATALOG_IDS[@]}"; do
  BUCKET="$(bucket_of "$ID")"
  if [ -z "$BUCKET" ]; then
    continue
  fi
  if gcloud storage buckets describe "gs://$BUCKET" >/dev/null 2>&1; then
    echo "- Bucket $BUCKET already exists, skipping"
  else
    echo "- Creating bucket $BUCKET"
    gcloud storage buckets create "gs://$BUCKET" --location=$GCP_REGION --project=$PROJECT_ID || exit 1
  fi
done

## Bucket grants

# "external"-typed catalogs are granted to external-sa (a different, non-Afon-managed principal);
# every other catalog with GCP_ADMIN_GRANTS_<id>=true (ie not gcp_forbidden) is granted to
# GCP_SERVICE_ACCOUNT (my-vendable-sa).
for ID in "${CATALOG_IDS[@]}"; do
  BUCKET="$(bucket_of "$ID")"
  if [ -z "$BUCKET" ]; then
    continue
  fi
  if [ "$(type_of "$ID")" = "external" ]; then
    PRINCIPAL="$EXTERNAL_SA"
  elif [ "$(admin_grants_of "$ID")" = "true" ]; then
    PRINCIPAL="$GCP_SERVICE_ACCOUNT"
  else
    continue
  fi
  if gcloud storage buckets get-iam-policy "gs://$BUCKET" --flatten="bindings[].members" --filter="bindings.role=roles/storage.objectAdmin AND bindings.members=serviceAccount:$PRINCIPAL" --format="value(bindings.members)" 2>/dev/null | grep -q .; then
    echo "- Role storage.objectAdmin already granted to $PRINCIPAL on $BUCKET, skipping"
  else
    echo "- Granting storage.objectAdmin on $BUCKET to $PRINCIPAL"
    gcloud storage buckets add-iam-policy-binding "gs://$BUCKET" --member="serviceAccount:$PRINCIPAL" --role="roles/storage.objectAdmin" || exit 1
  fi
done

# Needed when we move to Polaris 1.4
for API in iam.googleapis.com iamcredentials.googleapis.com serviceusage.googleapis.com storage.googleapis.com compute.googleapis.com; do
  if gcloud services list --enabled --project=$PROJECT_ID --filter="config.name=$API" --format="value(config.name)" 2>/dev/null | grep -q "^$API$"; then
    echo "- API $API already enabled, skipping"
  else
    echo "- Enabling API $API"
    gcloud services enable $API --project=$PROJECT_ID || exit 1
  fi
done

if gcloud projects get-iam-policy $PROJECT_ID --flatten="bindings[].members" --filter="bindings.role=roles/storage.objectViewer AND bindings.members=serviceAccount:$GCP_SERVICE_ACCOUNT" --format="value(bindings.members)" 2>/dev/null | grep -q .; then
  echo "- Role storage.objectViewer already granted to $GCP_SERVICE_ACCOUNT on project $PROJECT_ID, skipping"
else
  echo "- Granting storage.objectViewer on project $PROJECT_ID to $GCP_SERVICE_ACCOUNT"
  gcloud projects add-iam-policy-binding $PROJECT_ID --member="serviceAccount:$GCP_SERVICE_ACCOUNT" --role="roles/storage.objectViewer" || exit 1
fi

if gcloud projects get-iam-policy $PROJECT_ID --flatten="bindings[].members" --filter="bindings.role=roles/serviceusage.serviceUsageConsumer AND bindings.members=serviceAccount:$GCP_SERVICE_ACCOUNT" --format="value(bindings.members)" 2>/dev/null | grep -q .; then
  echo "- Role serviceusage.serviceUsageConsumer already granted to $GCP_SERVICE_ACCOUNT on project $PROJECT_ID, skipping"
else
  echo "- Granting serviceusage.serviceUsageConsumer on project $PROJECT_ID to $GCP_SERVICE_ACCOUNT"
  gcloud projects add-iam-policy-binding $PROJECT_ID \
      --member="serviceAccount:$GCP_SERVICE_ACCOUNT" \
      --role="roles/serviceusage.serviceUsageConsumer" || exit 1
fi

## Gitea and the logs of Polaris

# Creates the bucket, if absent, and grants the service account the role to write to it, which the HMAC key of the account inherits.
create_bucket_for_hmac_key() {
  local BUCKET="$1"
  if gcloud storage buckets describe "gs://$BUCKET" >/dev/null 2>&1; then
    echo "- Bucket $BUCKET already exists, skipping"
  else
    echo "- Creating bucket $BUCKET"
    gcloud storage buckets create "gs://$BUCKET" --location=$GCP_REGION --project=$PROJECT_ID || exit 1
  fi
  if gcloud storage buckets get-iam-policy "gs://$BUCKET" --flatten="bindings[].members" --filter="bindings.role=roles/storage.objectAdmin AND bindings.members=serviceAccount:$GCP_SERVICE_ACCOUNT" --format="value(bindings.members)" 2>/dev/null | grep -q .; then
    echo "- Role storage.objectAdmin already granted to $GCP_SERVICE_ACCOUNT on $BUCKET, skipping"
  else
    echo "- Granting storage.objectAdmin on $BUCKET to $GCP_SERVICE_ACCOUNT"
    gcloud storage buckets add-iam-policy-binding "gs://$BUCKET" --member="serviceAccount:$GCP_SERVICE_ACCOUNT" --role="roles/storage.objectAdmin" || exit 1
  fi
}

# Gitea, deployed by the stacks with Git, keeps its blobs in a bucket of its own, and the stacks with Logging ship the logs of Polaris to
# GCP_POLARIS_LOG_BUCKET, if it is set: both through GCS' S3 compatible API with the HMAC key of the service account.
if has_component Git; then
  if [ -z "${GCP_GITEA_BUCKET:-}" ]; then
    echo "-- GCP_GITEA_BUCKET is not set, which stack '$STACK_ID' needs for Gitea's bucket"
    exit 1
  fi
  create_bucket_for_hmac_key "$GCP_GITEA_BUCKET"
fi
if has_component Logging && [ -n "${GCP_POLARIS_LOG_BUCKET:-}" ]; then
  create_bucket_for_hmac_key "$GCP_POLARIS_LOG_BUCKET"
fi

if has_component Git; then
  # Named as the GcpCloudProvider names it, after what it is for and the cluster of the stack, so that a cluster of the same name finds
  # it again.  It is in the zone the provider looks it up in, and deleted by destroy.sh, not by deleting the stack.
  DISK_NAME="gitea-${STACK_ID#gcp-}-${CLUSTER_NAME:?CLUSTER_NAME must be set in afon.properties}"
  DISK_ZONE="${GCP_REGION}-a"
  DISK_SIZE="${GITEA_STORAGE_SIZE:-10Gi}"
  DISK_SIZE="${DISK_SIZE%Gi}"
  if gcloud compute disks describe "$DISK_NAME" --zone=$DISK_ZONE --project=$PROJECT_ID >/dev/null 2>&1; then
    echo "- Disk $DISK_NAME already exists, skipping"
  else
    echo "- Creating disk $DISK_NAME (${DISK_SIZE}GB) in $DISK_ZONE"
    gcloud compute disks create "$DISK_NAME" --size="${DISK_SIZE}GB" --type=pd-balanced --zone=$DISK_ZONE --project=$PROJECT_ID || exit 1
  fi
fi

# The access id of the HMAC key, read from the secret files, not from the environment: afon-source.sh does not load them, and sourcing
# secret-gcp.properties would also run its GOOGLE_APPLICATION_CREDENTIALS, a JSON key on one line, through the shell.
configured_hmac_access_id() {
  local FILE
  for FILE in "$AFON_HOME/config/secret.properties" "$AFON_HOME/config/secret-gcp.properties"; do
    [ -f "$FILE" ] && grep -E '^[[:space:]]*GCP_HMAC_ACCESS_ID[[:space:]]*=' "$FILE"
  done | tail -1 | cut -d= -f2- | tr -d '\r' | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//'
}

if has_component Git || { has_component Logging && [ -n "${GCP_POLARIS_LOG_BUCKET:-}" ]; }; then
  # The secret of an HMAC key is only shown when it is created, so a key is only created if none is configured, and then it is
  # for the user to set it, in secret-gcp.properties.
  HMAC_ACCESS_ID="$(configured_hmac_access_id)"
  if [ -n "$HMAC_ACCESS_ID" ]; then
    if gcloud storage hmac describe "$HMAC_ACCESS_ID" --project=$PROJECT_ID >/dev/null 2>&1; then
      echo "- HMAC key $HMAC_ACCESS_ID already exists, skipping"
    else
      echo "-- GCP_HMAC_ACCESS_ID is set to $HMAC_ACCESS_ID, but there is no such HMAC key in $PROJECT_ID: unset it and GCP_HMAC_SECRET to have another created"
      exit 1
    fi
  else
    echo "- Creating an HMAC key for $GCP_SERVICE_ACCOUNT"
    gcloud storage hmac create "$GCP_SERVICE_ACCOUNT" --project=$PROJECT_ID || exit 1
    echo "-- Set GCP_HMAC_ACCESS_ID and GCP_HMAC_SECRET in secret-gcp.properties, from the accessId and secret above: the secret is not shown again"
  fi
fi

# Secret Manager, which stores the TLS cert (see secrets.sh): no vault to create, but the API must be enabled
if [ "$GCP_SECRETS_ENABLED" = "true" ]; then
  SECRETS_API="secretmanager.googleapis.com"
  if gcloud services list --enabled --project=$PROJECT_ID --filter="config.name=$SECRETS_API" --format="value(config.name)" 2>/dev/null | grep -q "^$SECRETS_API$"; then
    echo "- API $SECRETS_API already enabled, skipping"
  else
    echo "- Enabling API $SECRETS_API"
    gcloud services enable $SECRETS_API --project=$PROJECT_ID || exit 1
  fi
fi

echo "GCP env created."
