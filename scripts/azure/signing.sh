#!/usr/bin/env bash
source $AFON_ROOT/scripts/config/afon-source.sh azure

usage(){
  echo "Usage: ${BASH_SOURCE[0]} <create|delete|show>"
  echo "Creates, outside the stack, what signing the Docker images needs on Azure, see docs/stacks.md#image-signing:"
  echo "- the ACR of AZURE_IMAGE_REGISTRY, which docker-image --sign copies the images to, ie <name>.azurecr.io[/<path>]"
  echo "- the EC P-256 key of AZURE_COSIGN_KEY, ie azurekms://<vault>.vault.azure.net/<key>, in that Key Vault, which must exist"
  echo "  (azure-keyvault create).  Only the deployer can sign with it, and its public key is readable by whoever deploys."
  echo "- AcrPush on the ACR for the deployer, so that it pushes the images; the kubelet of the cluster is given AcrPull by the stack."
  echo "It is idempotent.  Afterwards run: afon-cli.sh signing-public-key azure"
}

ACTION="${1:-}"
case "$ACTION" in
  create|delete|show) ;;
  *) usage; exit 1 ;;
esac

### The registry and the key, from the properties

REGISTRY_HOST="${AZURE_IMAGE_REGISTRY:-}"
REGISTRY_HOST="${REGISTRY_HOST%%/*}"
case "$REGISTRY_HOST" in
  *.azurecr.io) ACR_NAME="${REGISTRY_HOST%.azurecr.io}" ;;
  *) echo "-- AZURE_IMAGE_REGISTRY must be an ACR, ie <name>.azurecr.io[/<path>], not '${AZURE_IMAGE_REGISTRY:-}'"; exit 1 ;;
esac

# azurekms://<vault>.vault.azure.net/<key>, which is how cosign names the key
KEY_REF="${AZURE_COSIGN_KEY:-}"
case "$KEY_REF" in
  azurekms://*.vault.azure.net/?*) ;;
  *) echo "-- AZURE_COSIGN_KEY must be azurekms://<vault>.vault.azure.net/<key>, not '$KEY_REF'"; exit 1 ;;
esac
KEY_PATH="${KEY_REF#azurekms://}"
VAULT_NAME="${KEY_PATH%%.vault.azure.net/*}"
KEY_NAME="${KEY_PATH#*/}"
KEY_NAME="${KEY_NAME%%/*}"

source $AFON_ROOT/scripts/azure/rbac.sh
source $AFON_ROOT/scripts/azure/account-principal.sh

if az ad signed-in-user show --query id -o tsv > /dev/null 2>&1; then CALLER_TYPE=User; else CALLER_TYPE=ServicePrincipal; fi

# Grants the role to the deployer at the scope, if it does not hold it, and retries as a new assignment can be refused for a while.
grant() {
  local ROLE="$1" SCOPE="$2" LABEL="$3"
  if [ -n "$(az role assignment list --assignee-object-id $PRINCIPAL_ID --role "$ROLE" --scope "$SCOPE" --query "[].id" -o tsv)" ]; then
    echo "- Role '$ROLE' already assigned to '$PRINCIPAL_ID' on $LABEL, skipping"
    return
  fi
  echo "- Assigning role '$ROLE' to '$PRINCIPAL_ID' on $LABEL"
  for i in $(seq 1 12); do
    az role assignment create --role "$ROLE" --assignee-object-id $PRINCIPAL_ID --assignee-principal-type $CALLER_TYPE --scope "$SCOPE" -o none && return
    [ "$i" -eq 12 ] && { echo "-- Failed to assign '$ROLE' on $LABEL after repeated retries"; exit 1; }
    echo "- Assignment not accepted yet (propagation?), retrying in 10s..."
    sleep 10
  done
}

revoke() {
  local ROLE="$1" SCOPE="$2" LABEL="$3"
  if [ -n "$(az role assignment list --assignee-object-id $PRINCIPAL_ID --role "$ROLE" --scope "$SCOPE" --query "[].id" -o tsv)" ]; then
    echo "- Deleting role assignment '$ROLE' on $LABEL"
    az role assignment delete --assignee-object-id $PRINCIPAL_ID --role "$ROLE" --scope "$SCOPE" || exit 1
  fi
}

ACR_SCOPE="/subscriptions/$AZURE_SUBSCRIPTION/resourceGroups/$AZURE_RESOURCE_GROUP/providers/Microsoft.ContainerRegistry/registries/$ACR_NAME"

key_exists() {
  az keyvault key show --vault-name $VAULT_NAME --name $KEY_NAME -o none 2>/dev/null
}

case "$ACTION" in
  create)
    require_role_assignment_rights $PRINCIPAL_ID create signing.sh

    ## Registry

    if [ "$(az provider show --namespace Microsoft.ContainerRegistry --query registrationState -o tsv 2>/dev/null)" != "Registered" ]; then
      echo "- Registering Microsoft.ContainerRegistry"
      az provider register --namespace Microsoft.ContainerRegistry --wait || exit 1
    fi
    if az acr show --name $ACR_NAME --resource-group $AZURE_RESOURCE_GROUP -o none 2>/dev/null; then
      echo "- Registry '$ACR_NAME' already exists, skipping creation"
    else
      echo "- Creating registry '$ACR_NAME' in $AZURE_RESOURCE_GROUP"
      # Basic suffices, no admin user: it is pushed to by the deployer's role, and pulled from by the kubelet's
      az acr create --name $ACR_NAME --resource-group $AZURE_RESOURCE_GROUP --location $AZURE_REGION --sku Basic --admin-enabled false -o none || exit 1
    fi
    grant "AcrPush" "$ACR_SCOPE" "registry '$ACR_NAME'"

    ## Key

    if ! az keyvault show --name $VAULT_NAME -o none 2>/dev/null; then
      echo "-- Key Vault '$VAULT_NAME' does not exist: create it with afon-cli.sh azure-keyvault create (AZURE_KEY_VAULT_NAME), or fix AZURE_COSIGN_KEY"
      exit 1
    fi
    VAULT_SCOPE="$(az keyvault show --name $VAULT_NAME --query id -o tsv)"
    # The deployer manages the key, to create it, and, as it is kept, to rotate or delete it: the role of the vault, so it is held by a rerun too
    grant "Key Vault Crypto Officer" "$VAULT_SCOPE" "vault '$VAULT_NAME'"
    if key_exists; then
      echo "- Key '$KEY_NAME' already exists in '$VAULT_NAME', skipping creation"
    else
      echo "- Creating the EC P-256 key '$KEY_NAME' in '$VAULT_NAME', which can only sign and verify"
      for i in $(seq 1 12); do
        az keyvault key create --vault-name $VAULT_NAME --name $KEY_NAME --kty EC --curve P-256 --ops sign verify -o none && break
        [ "$i" -eq 12 ] && { echo "-- Failed to create the key: has the role Key Vault Crypto Officer propagated?  Run this again shortly"; exit 1; }
        echo "- Key not created yet (propagation?), retrying in 10s..."
        sleep 10
      done
    fi
    # The signing role, at the scope of the key alone, ie not of the vault's other keys: Crypto Officer cannot sign, so the deployer needs both.
    KEY_SCOPE="$VAULT_SCOPE/keys/$KEY_NAME"
    grant "Key Vault Crypto User" "$KEY_SCOPE" "key '$KEY_NAME'"

    echo "- Registry '$REGISTRY_HOST' and key '$KEY_REF' ready."
    echo "- A new role may take a few minutes to apply.  Next: afon-cli.sh signing-public-key azure, then docker-image --sign --cloud azure"
    ;;

  delete)
    require_role_assignment_rights $PRINCIPAL_ID delete signing.sh
    if key_exists; then
      echo "- Deleting key '$KEY_NAME' of '$VAULT_NAME', which is only soft-deleted: purge it with az keyvault key purge --vault-name $VAULT_NAME --name $KEY_NAME"
      az keyvault key delete --vault-name $VAULT_NAME --name $KEY_NAME -o none || exit 1
    else
      echo "- Key '$KEY_NAME' does not exist in '$VAULT_NAME', skipping"
    fi
    VAULT_ID="$(az keyvault show --name $VAULT_NAME --query id -o tsv 2>/dev/null)"
    if [ -n "$VAULT_ID" ]; then
      revoke "Key Vault Crypto User" "$VAULT_ID/keys/$KEY_NAME" "key '$KEY_NAME'"
    fi
    if az acr show --name $ACR_NAME --resource-group $AZURE_RESOURCE_GROUP -o none 2>/dev/null; then
      revoke "AcrPush" "$ACR_SCOPE" "registry '$ACR_NAME'"
      echo "- Deleting registry '$ACR_NAME', and the images in it"
      az acr delete --name $ACR_NAME --resource-group $AZURE_RESOURCE_GROUP --yes || exit 1
    else
      echo "- Registry '$ACR_NAME' does not exist, skipping"
    fi
    ;;

  show)
    if az acr show --name $ACR_NAME --resource-group $AZURE_RESOURCE_GROUP -o none 2>/dev/null; then
      echo "- Registry '$ACR_NAME' exists"
    else
      echo "- Registry '$ACR_NAME' does not exist"
    fi
    if key_exists; then
      echo "- Key '$KEY_NAME' exists in '$VAULT_NAME'"
      az keyvault key show --vault-name $VAULT_NAME --name $KEY_NAME --query "key.{kty:kty, curve:crv, operations:keyOps}" -o jsonc
    else
      echo "- Key '$KEY_NAME' does not exist in '$VAULT_NAME', or it cannot be read: it takes a role such as Key Vault Crypto User"
    fi
    ;;
esac
