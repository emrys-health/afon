source $AFON_ROOT/scripts/config/afon-source.sh azure

usage(){
  echo "Usage: $0 <create|delete|list|show>"
}

ACTION="${1:-}"

### Key Vault

# Grants the given Key Vault role to the signed-in principal, at vault scope, if not already
# held, then waits for it to propagate to the data plane before returning - a just-created role
# assignment can take a short while to reach Key Vault, so an immediate data-plane call can 403
# even though the assignment exists.
grant_vault_role() {
  ROLE="$1"
  PRINCIPAL_ID="$(az ad signed-in-user show --query id -o tsv)"
  VAULT_SCOPE="$(az keyvault show --name $AZURE_KEY_VAULT_NAME --query id -o tsv)"
  if [ -n "$(az role assignment list --assignee-object-id $PRINCIPAL_ID --role "$ROLE" --scope "$VAULT_SCOPE" --query "[].id" -o tsv)" ]; then
    return
  fi

  echo "- Granting $ROLE on $AZURE_KEY_VAULT_NAME to $PRINCIPAL_ID"
  # A just-activated PIM role (or a just-updated ABAC condition) can take a short while to
  # propagate to Microsoft.Authorization itself, so the create call can fail with
  # AuthorizationFailed even though the grant is otherwise correct - retry rather than aborting.
  for i in $(seq 1 12); do
    az role assignment create --role "$ROLE" --assignee-object-id $PRINCIPAL_ID \
      --assignee-principal-type User --scope "$VAULT_SCOPE" -o none && break
    [ "$i" -eq 12 ] && { echo "- Failed to grant $ROLE after repeated retries"; exit 1; }
    echo "- Grant not accepted yet (propagation?), retrying in 10s..."
    sleep 10
  done

  echo "- Waiting for the grant to propagate..."
  for i in $(seq 1 12); do
    az keyvault secret list --vault-name $AZURE_KEY_VAULT_NAME -o none 2>/dev/null && return
    [ "$i" -eq 12 ] && { echo "- Grant did not propagate in time, re-run 'keyvault.sh $ACTION'"; exit 1; }
    sleep 10
  done
}

case "$ACTION" in
  create)
    if az keyvault show --name $AZURE_KEY_VAULT_NAME -o none 2>/dev/null; then
      echo "- Key Vault $AZURE_KEY_VAULT_NAME already exists, skipping"
    else
      echo "- Creating Key Vault $AZURE_KEY_VAULT_NAME in $AZURE_RESOURCE_GROUP"
      az keyvault create  --name $AZURE_KEY_VAULT_NAME  --resource-group $AZURE_RESOURCE_GROUP  --location $AZURE_REGION  --enable-rbac-authorization true || exit 1
    fi

    echo "Key Vault $AZURE_KEY_VAULT_NAME ready."
    ;;

  delete)
    if az keyvault show --name $AZURE_KEY_VAULT_NAME -o none 2>/dev/null; then
      echo "- Deleting Key Vault $AZURE_KEY_VAULT_NAME"
      az keyvault delete  --name $AZURE_KEY_VAULT_NAME  --resource-group $AZURE_RESOURCE_GROUP || exit 1
      echo "- Soft-deleted. To permanently purge: az keyvault purge --name $AZURE_KEY_VAULT_NAME --location $AZURE_REGION"
    else
      echo "- Key Vault $AZURE_KEY_VAULT_NAME does not exist, skipping"
    fi
    ;;

  list)
    if az keyvault show --name $AZURE_KEY_VAULT_NAME -o none 2>/dev/null; then
      # Self-contained, unlike issue-cert.sh's write roles: list needs read access regardless of
      # which issuer (if any) has been used, so it can't rely on issue-cert.sh having run first.
      grant_vault_role "Key Vault Secrets User"

      echo "- Secrets in $AZURE_KEY_VAULT_NAME:"
      az keyvault secret list --vault-name $AZURE_KEY_VAULT_NAME --query "[].{name:name, updated:attributes.updated, enabled:attributes.enabled}" -o table
    else
      echo "- Key Vault $AZURE_KEY_VAULT_NAME does not exist"
    fi
    ;;

  show)
    if az keyvault show --name $AZURE_KEY_VAULT_NAME -o none 2>/dev/null; then
      echo "- Key Vault $AZURE_KEY_VAULT_NAME exists"
    else
      echo "- Key Vault $AZURE_KEY_VAULT_NAME does not exist"
    fi
    ;;

  *)
    usage
    exit 1
    ;;
esac
