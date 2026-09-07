source $AFON_ROOT/scripts/config/afon-source.sh azure
source $AFON_ROOT/scripts/config/secret-source.sh azure

AS_ADMIN="${1:-false}"
APP_ID="${AZURE_CLIENT_ID:-}"
APP_SP_ID=${AZURE_SP_ID:-}

echo "- Destroying env for subscription '$AZURE_SUBSCRIPTION'"

if [ -z "${APP_ID}" ] || [ -z "${APP_SP_ID}" ]; then
  echo "APP_ID and APP_SP_ID are required to run destroy.sh"
  echo "Set AZURE_CLIENT_ID and AZURE_SP_ID in config or export APP_ID and APP_SP_ID"
  exit 1;
fi

### Role Assignment

echo "- Deleting roles for app '$APP_ID'"

source $AFON_ROOT/scripts/azure/rbac.sh
source $AFON_ROOT/scripts/azure/account-principal.sh
require_role_assignment_rights $PRINCIPAL_ID delete destroy.sh

echo "- Using APP_SP_ID='$APP_SP_ID' as assignee for deleting role assignments"
ASSIGNEE_ID=$APP_SP_ID
STORAGE_BUCKET_ROLE="Storage Blob Data Contributor"
STORAGE_ACCOUNT_ROLE="Storage Blob Delegator"

BUCKET_SCOPE="/subscriptions/$AZURE_SUBSCRIPTION/resourceGroups/$AZURE_RESOURCE_GROUP/providers/Microsoft.Storage/storageAccounts/$AZURE_STORAGE_ACCOUNT/blobServices/default/containers/$AZURE_BUCKET"
if [ -n "$(az role assignment list --assignee-object-id $ASSIGNEE_ID --role "$STORAGE_BUCKET_ROLE" --scope "$BUCKET_SCOPE" --query "[].id" -o tsv)" ]; then
  echo "- Deleting role assignment '$STORAGE_BUCKET_ROLE' for bucket '$AZURE_BUCKET'"
  az role assignment delete --assignee-object-id $ASSIGNEE_ID --role "$STORAGE_BUCKET_ROLE" --scope "$BUCKET_SCOPE"
else
  echo "- No role assignment '$STORAGE_BUCKET_ROLE' for bucket '$AZURE_BUCKET', skipping"
fi

EXTERNAL_BUCKET_SCOPE="/subscriptions/$AZURE_SUBSCRIPTION/resourceGroups/$AZURE_RESOURCE_GROUP/providers/Microsoft.Storage/storageAccounts/$AZURE_STORAGE_ACCOUNT/blobServices/default/containers/$AZURE_EXTERNAL_BUCKET"
if [ -n "$(az role assignment list --assignee-object-id $ASSIGNEE_ID --role "$STORAGE_BUCKET_ROLE" --scope "$EXTERNAL_BUCKET_SCOPE" --query "[].id" -o tsv)" ]; then
  echo "- Deleting role assignment '$STORAGE_BUCKET_ROLE' for bucket '$AZURE_EXTERNAL_BUCKET'"
  az role assignment delete --assignee-object-id $ASSIGNEE_ID --role "$STORAGE_BUCKET_ROLE" --scope "$EXTERNAL_BUCKET_SCOPE"
else
  echo "- No role assignment '$STORAGE_BUCKET_ROLE' for bucket '$AZURE_EXTERNAL_BUCKET', skipping"
fi

STORAGE_ACCOUNT_SCOPE="/subscriptions/$AZURE_SUBSCRIPTION/resourceGroups/$AZURE_RESOURCE_GROUP/providers/Microsoft.Storage/storageAccounts/$AZURE_STORAGE_ACCOUNT"
if [ -n "$(az role assignment list --assignee-object-id $ASSIGNEE_ID --role "$STORAGE_ACCOUNT_ROLE" --scope "$STORAGE_ACCOUNT_SCOPE" --query "[].id" -o tsv)" ]; then
  echo "- Deleting role assignment '$STORAGE_ACCOUNT_ROLE' for '$AZURE_STORAGE_ACCOUNT'"
  az role assignment delete --assignee-object-id $ASSIGNEE_ID --role "$STORAGE_ACCOUNT_ROLE" --scope "$STORAGE_ACCOUNT_SCOPE"
else
  echo "- No role assignment '$STORAGE_ACCOUNT_ROLE' for '$AZURE_STORAGE_ACCOUNT', skipping"
fi

### Storage

echo "- Deleting storage"

if az storage account show --name $AZURE_STORAGE_ACCOUNT --resource-group $AZURE_RESOURCE_GROUP -o none 2>/dev/null; then
  if [ "$(az storage container exists --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_BUCKET --auth-mode login --query exists -o tsv)" = "true" ]; then
    echo "- Deleting bucket '$AZURE_BUCKET'"
    az storage container delete --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_BUCKET
  else
    echo "- Bucket '$AZURE_BUCKET' does not exist, skipping"
  fi

  if [ "$(az storage container exists --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_EXTERNAL_BUCKET --query exists -o tsv)" = "true" ]; then
    echo "- Deleting bucket '$AZURE_EXTERNAL_BUCKET'"
    az storage container delete --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_EXTERNAL_BUCKET
  else
    echo "- Bucket '$AZURE_EXTERNAL_BUCKET' does not exist, skipping"
  fi

  if [ "$(az storage container exists --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_BUCKET_FORBIDDEN --query exists -o tsv)" = "true" ]; then
    echo "- Deleting bucket '$AZURE_BUCKET_FORBIDDEN'"
    az storage container delete --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_BUCKET_FORBIDDEN
  else
    echo "= Bucket '$AZURE_BUCKET_FORBIDDEN' does not exist, skipping"
  fi

  echo "- Deleting account '$AZURE_STORAGE_ACCOUNT'"
  az storage account delete --name $AZURE_STORAGE_ACCOUNT --resource-group $AZURE_RESOURCE_GROUP --yes
else
  echo "- Storage account '$AZURE_STORAGE_ACCOUNT' does not exist, skipping account and bucket cleanup"
fi

### Resource Group

echo "- Deleting resource group"

if [ "$(az group exists --name $AZURE_RESOURCE_GROUP)" = "true" ]; then
  echo "- Deleting resource group '$AZURE_RESOURCE_GROUP'"
  az group delete --name $AZURE_RESOURCE_GROUP --yes
else
  echo "- Resource group '$AZURE_RESOURCE_GROUP' does not exist, skipping"
fi

if [ "$AS_ADMIN" = "true" ]; then
  echo "- AS_ADMIN=true, running destroy-admin.sh"
  set --
  source $AFON_ROOT/scripts/azure/destroy-admin.sh || exit 1
else
  echo "- Ask your admin to run destroy-admin.sh (or set AS_ADMIN=true if you have az ad permissions) to remove the app and service principal."
fi
