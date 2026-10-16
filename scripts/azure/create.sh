source $AFON_ROOT/scripts/config/afon-source.sh azure
source $AFON_ROOT/scripts/config/secret-source.sh azure

# Args, in any order: whether to run as admin, true|false, and the stack the env is created for, given as its id, or its role
# in the cloud (identity, catalog or tre), which decides the DNS zones and storage, and is required, as what the env needs
# depends on it.  By default not as admin.  What the stack needs is looked up in stacks.yaml and given as the AFON_STACK_* env
# vars: AFON_STACK_ID, AFON_STACK_ROLE, AFON_STACK_DOMAINS and AFON_STACK_COMPONENTS, by the launcher when run by afon-cli.sh, see
# ScriptMain, else by asking the ops command stack-needs, as when run by afon.sh, see scripts/stack/needs-source.sh.
AS_ADMIN=false
STACK_ID=
for ARG in "$@"; do
  case "$ARG" in
    true|false) AS_ADMIN="$ARG" ;;
    azure-*|identity|catalog|tre) STACK_ID="$ARG" ;;
    *) echo "-- Unknown argument '$ARG': expected true|false, or a stack by its id or role"; exit 1 ;;
  esac
done
if [ -z "$STACK_ID" ]; then
  echo "-- Requires the stack the env is created for, by its id or role: azure-create [true|false] <stack-id|identity|catalog|tre>"
  exit 1
fi
source $AFON_ROOT/scripts/stack/needs-source.sh
stack_needs azure "$STACK_ID" || exit 1
STACK_ID="$AFON_STACK_ID"

# Whether the stack deploys the kind of component, as named in stacks.yaml, eg Git.
has_component() {
  case " ${AFON_STACK_COMPONENTS:-} " in
    *" $1 "*) return 0 ;;
    *) return 1 ;;
  esac
}

# The properties of the domain names whose DNS zones the stack needs: those that its components hold, which the launcher works
# out from stacks.yaml, eg Polaris, Jupyter and the KeyCloak, which a stack has unless AZURE_KEYCLOAK_EXTERNAL is true, as it
# then uses the one of another stack.
DOMAIN_PROPERTIES="$AFON_STACK_DOMAINS"
APP_ID="${AZURE_APP_ID:-}"
APP_SP_ID="${AZURE_SP_ID:-}"

echo "- Creating env for stack '$STACK_ID' for subscription '$AZURE_SUBSCRIPTION'"

if [ "$AS_ADMIN" = "true" ]; then
  echo "- AS_ADMIN=true, running create-admin.sh"
  set --
  source $AFON_ROOT/scripts/azure/create-admin.sh || exit 1
elif [ -z "${APP_ID:-}" ] || [ -z "${APP_SP_ID:-}" ]; then
  echo "-- APP_ID or APP_SP_ID are not set."
  echo "-- Ask your admin to run create-admin.sh (or pass AS_ADMIN=true as the first arg if you have az ad permissions)"
  echo "-- Add the printed APP_ID and APP_SP_ID to properties before running create.sh."
  exit 1
fi

## Resource Providers

echo "- Registering resource provides"

AZURE_RESOURCE_PROVIDERS="Microsoft.Authorization Microsoft.Storage Microsoft.Network Microsoft.ManagedIdentity Microsoft.ContainerService Microsoft.Compute Microsoft.KeyVault"

for ns in $AZURE_RESOURCE_PROVIDERS; do
  STATE="$(az provider show --namespace "$ns" --query registrationState -o tsv 2>/dev/null)"
  if [ "$STATE" = "Registered" ]; then
    echo "-- $ns already registered, skipping"
  else
    echo "-- Registering $ns (was: ${STATE:-NotFound}), waiting for it to complete..."
    az provider register --namespace "$ns" --wait || exit 1
  fi
done

# Resource Group

echo "- Creating resource group"

if [ "$(az group exists --name $AZURE_RESOURCE_GROUP)" = "true" ]; then
  echo "- Resource group '$AZURE_RESOURCE_GROUP' already exists, skipping"
else
  echo "- Creating group '$AZURE_RESOURCE_GROUP' in region '$AZURE_REGION'"
  az group create --name $AZURE_RESOURCE_GROUP  --location $AZURE_REGION || exit 1
fi

# DNS zone

echo "- Creating DNS zone"

# One zone per domain name the stack serves from the cluster, which must be set
for DOMAIN_PROPERTY in $DOMAIN_PROPERTIES; do
  DOMAIN_NAME="${!DOMAIN_PROPERTY:-}"
  if [ -z "$DOMAIN_NAME" ]; then
    echo "-- $DOMAIN_PROPERTY is not set, which stack '$STACK_ID' needs for its DNS zone"
    exit 1
  fi
  if az network dns zone show --resource-group $AZURE_RESOURCE_GROUP --name $DOMAIN_NAME -o none 2>/dev/null; then
    echo "- DNS zone for domain name '$DOMAIN_NAME' ($DOMAIN_PROPERTY) already exists, skipping"
  else
    echo "- Creating DNS zone for domain name '$DOMAIN_NAME' ($DOMAIN_PROPERTY)"
    az network dns zone create --resource-group $AZURE_RESOURCE_GROUP --name $DOMAIN_NAME || exit 1
  fi
done

# Storage

echo "- Creating storage"

## Storage Account

if az storage account show --name $AZURE_STORAGE_ACCOUNT --resource-group $AZURE_RESOURCE_GROUP -o none 2>/dev/null; then
  echo "- Storage account '$AZURE_STORAGE_ACCOUNT' already exists, skipping"
else
  echo "- Creating storage account '$AZURE_STORAGE_ACCOUNT'"
  az storage account create --name $AZURE_STORAGE_ACCOUNT --resource-group $AZURE_RESOURCE_GROUP --location $AZURE_REGION --sku Standard_LRS --kind StorageV2 --enable-hierarchical-namespace false || exit 1
fi

## Storage Buckets

create_container_if_absent() {
  local CONTAINER="$1"
  if [ "$(az storage container exists --account-name $AZURE_STORAGE_ACCOUNT --name $CONTAINER --auth-mode login --query exists -o tsv)" = "true" ]; then
    echo "- Bucket '$CONTAINER' already exists, skipping"
  else
    echo "- Creating bucket '$CONTAINER'"
    az storage container create --account-name $AZURE_STORAGE_ACCOUNT --name $CONTAINER --auth-mode login || exit 1
  fi
}

# Reads AZURE_BUCKET_NAME_<id>/AZURE_ADMIN_GRANTS_<id>, echoing "" instead of erroring under
# `set -u` if a catalog (eg a custom type with no bucket of its own) never defined one.
bucket_of() {
  local VAR="AZURE_BUCKET_NAME_$1"
  if [ -n "${!VAR+x}" ]; then
    echo "${!VAR}"
  fi
}
admin_grants_of() {
  local VAR="AZURE_ADMIN_GRANTS_$1"
  if [ -n "${!VAR+x}" ]; then
    echo "${!VAR}"
  else
    echo "true"
  fi
}

IFS=',' read -ra CATALOG_IDS <<< "$AZURE_CATALOG_IDS"
for ID in "${CATALOG_IDS[@]}"; do
  BUCKET="$(bucket_of "$ID")"
  if [ -z "$BUCKET" ]; then
    continue
  fi
  create_container_if_absent "$BUCKET"
done

# Gitea, deployed by the stacks with Git, keeps its blobs in a container of its own, which it reaches with the account key of
# AZURE_CONNECTION_STRING, so it needs no role assignment.
if has_component Git; then
  if [ -z "${AZURE_GITEA_BUCKET:-}" ]; then
    echo "-- AZURE_GITEA_BUCKET is not set, which stack '$STACK_ID' needs for Gitea's blob container"
    exit 1
  fi
  create_container_if_absent "$AZURE_GITEA_BUCKET"
fi

# The container Polaris' logs are shipped to, if it is set: it is optional, so there is no logging of Polaris without it.
if [ -n "${AZURE_POLARIS_LOG_BUCKET:-}" ]; then
  create_container_if_absent "$AZURE_POLARIS_LOG_BUCKET"
fi

## Role Assignment

echo "- Creating role assignments"

source $AFON_ROOT/scripts/azure/rbac.sh
source $AFON_ROOT/scripts/azure/account-principal.sh
require_role_assignment_rights $PRINCIPAL_ID create create.sh

echo "- Using APP_SP_ID='$APP_SP_ID' as assignee for creating role assignments"
ASSIGNEE_ID="$APP_SP_ID"
STORAGE_BUCKET_ROLE="Storage Blob Data Contributor"
STORAGE_ACCOUNT_ROLE="Storage Blob Delegator"

# Every catalog's bucket gets this role, except those with AZURE_ADMIN_GRANTS_<id>=false (eg
# azure_forbidden) - deliberately left without it, so it stays inaccessible to prove that guarantee.
for ID in "${CATALOG_IDS[@]}"; do
  BUCKET="$(bucket_of "$ID")"
  if [ -z "$BUCKET" ] || [ "$(admin_grants_of "$ID")" != "true" ]; then
    continue
  fi
  BUCKET_SCOPE="/subscriptions/$AZURE_SUBSCRIPTION/resourceGroups/$AZURE_RESOURCE_GROUP/providers/Microsoft.Storage/storageAccounts/$AZURE_STORAGE_ACCOUNT/blobServices/default/containers/$BUCKET"
  if [ -n "$(az role assignment list --assignee-object-id $ASSIGNEE_ID --role "$STORAGE_BUCKET_ROLE" --scope "$BUCKET_SCOPE" --query "[].id" -o tsv)" ]; then
    echo "- Role '$STORAGE_BUCKET_ROLE' already assigned on '$BUCKET', skipping"
  else
    echo "- Assigning role '$STORAGE_BUCKET_ROLE' to bucket '$BUCKET'"
    az role assignment create --role "$STORAGE_BUCKET_ROLE" --assignee-object-id $ASSIGNEE_ID --assignee-principal-type ServicePrincipal --scope "$BUCKET_SCOPE" || exit 1
  fi
done

STORAGE_ACCOUNT_SCOPE="/subscriptions/$AZURE_SUBSCRIPTION/resourceGroups/$AZURE_RESOURCE_GROUP/providers/Microsoft.Storage/storageAccounts/$AZURE_STORAGE_ACCOUNT"
if [ -n "$(az role assignment list --assignee-object-id $ASSIGNEE_ID --role "$STORAGE_ACCOUNT_ROLE" --scope "$STORAGE_ACCOUNT_SCOPE" --query "[].id" -o tsv)" ]; then
  echo "- Role '$STORAGE_ACCOUNT_ROLE' already assigned on '$AZURE_STORAGE_ACCOUNT', skipping"
else
  echo "- Assigning role '$STORAGE_ACCOUNT_ROLE' to '$AZURE_STORAGE_ACCOUNT'"
  az role assignment create --role "$STORAGE_ACCOUNT_ROLE" --assignee-object-id $ASSIGNEE_ID --assignee-principal-type ServicePrincipal --scope "$STORAGE_ACCOUNT_SCOPE" || exit 1
fi

warn_if_operational_identity_can_manage_roles $ASSIGNEE_ID

# The account may have been recreated, which gives it new keys, so that the connection string of the config is stale: say so
# now, not when what uses it fails to start.  It is also not set on the first run, as it is only known once the account exists.
source $AFON_ROOT/scripts/azure/check-storage.sh || echo "-- Set AZURE_CONNECTION_STRING, then check it with: afon-cli.sh azure-check-storage"

echo "Env created."

