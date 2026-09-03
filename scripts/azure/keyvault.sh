source $AFON_ROOT/scripts/config/afon-source.sh azure

usage(){
  echo "Usage: $0 <create|delete|list>"
}

ACTION="${1:-}"

### Key Vault

case "$ACTION" in
  create)
    if az keyvault show --name $AZURE_KEY_VAULT_NAME -o none 2>/dev/null; then
      echo "- Key Vault $AZURE_KEY_VAULT_NAME already exists, skipping"
    else
      echo "- Creating Key Vault $AZURE_KEY_VAULT_NAME in $AZURE_RESOURCE_GROUP"
      az keyvault create  --name $AZURE_KEY_VAULT_NAME  --resource-group $AZURE_RESOURCE_GROUP  --location $AZURE_REGION  --enable-rbac-authorization true || exit 1
    fi

    ### Role Assignment

    PRINCIPAL_ID="$(az ad signed-in-user show --query id -o tsv)"
    VAULT_SCOPE="$(az keyvault show --name $AZURE_KEY_VAULT_NAME --query id -o tsv)"
    if [ -n "$(az role assignment list --assignee $PRINCIPAL_ID --role "Key Vault Secrets Officer" --scope "$VAULT_SCOPE" --query "[].id" -o tsv)" ]; then
      echo "- Role Key Vault Secrets Officer already assigned on $AZURE_KEY_VAULT_NAME, skipping"
    else
      echo "- Granting Key Vault Secrets Officer on $AZURE_KEY_VAULT_NAME to $PRINCIPAL_ID"
      az role assignment create  --role "Key Vault Secrets Officer" --assignee $PRINCIPAL_ID --scope "$VAULT_SCOPE" || exit 1
    fi

    # Needed by issue-cert.sh's digicert/globalsign path (certificate issuer/certificate data
    # actions), which Key Vault Secrets Officer above does not cover.
    if [ -n "$(az role assignment list --assignee $PRINCIPAL_ID --role "Key Vault Certificates Officer" --scope "$VAULT_SCOPE" --query "[].id" -o tsv)" ]; then
      echo "- Role Key Vault Certificates Officer already assigned on $AZURE_KEY_VAULT_NAME, skipping"
    else
      echo "- Granting Key Vault Certificates Officer on $AZURE_KEY_VAULT_NAME to $PRINCIPAL_ID"
      az role assignment create  --role "Key Vault Certificates Officer" --assignee $PRINCIPAL_ID --scope "$VAULT_SCOPE" || exit 1
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
      echo "- Secrets in $AZURE_KEY_VAULT_NAME:"
      az keyvault secret list --vault-name $AZURE_KEY_VAULT_NAME --query "[].{name:name, updated:attributes.updated, enabled:attributes.enabled}" -o table
    else
      echo "- Key Vault $AZURE_KEY_VAULT_NAME does not exist"
    fi
    ;;

  *)
    usage
    exit 1
    ;;
esac
