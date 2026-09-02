source $AFON_ROOT/scripts/config/afon-source.sh azure
source $AFON_ROOT/scripts/config/secret-source.sh azure

AS_ADMIN="${1:-false}"
APP_ID="${AZURE_CLIENT_ID:-}"
APP_SP_ID="${AZURE_SP_ID:-}"

echo "- Creating env for subscription '$AZURE_SUBSCRIPTION'"

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

if az network dns zone show --resource-group $AZURE_RESOURCE_GROUP --name $AZURE_DOMAIN_NAME -o none 2>/dev/null; then
  echo "- DNS zone for domain name '$AZURE_DOMAIN_NAME' already exists, skipping"
else
  echo "- Creating DNS zone for domain name '$AZURE_DOMAIN_NAME'"
  az network dns zone create --resource-group $AZURE_RESOURCE_GROUP --name $AZURE_DOMAIN_NAME || exit 1
fi

# Storage

echo "- Creating storage"

## Storage Resource Provider

if [ "$(az provider show --namespace Microsoft.Storage --query registrationState -o tsv)" = "Registered" ]; then
  echo "- Resource provider 'Microsoft.Storage' already registered, skipping"
else
  echo "- Registering resource provider 'Microsoft.Storage'"
  az provider register --namespace Microsoft.Storage --wait || exit 1
fi

## Storage Account

if az storage account show --name $AZURE_STORAGE_ACCOUNT --resource-group $AZURE_RESOURCE_GROUP -o none 2>/dev/null; then
  echo "- Storage account '$AZURE_STORAGE_ACCOUNT' already exists, skipping"
else
  echo "- Creating storage account '$AZURE_STORAGE_ACCOUNT'"
  az storage account create --name $AZURE_STORAGE_ACCOUNT --resource-group $AZURE_RESOURCE_GROUP --location $AZURE_REGION --sku Standard_LRS --kind StorageV2 --enable-hierarchical-namespace false || exit 1
fi

## Storage Buckets

if [ "$(az storage container exists --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_BUCKET --auth-mode login --query exists -o tsv)" = "true" ]; then
  echo "- Bucket '$AZURE_BUCKET' already exists, skipping"
else
  echo "- Creating bucket '$AZURE_BUCKET'"
  az storage container create --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_BUCKET --auth-mode login || exit 1
fi

if [ "$(az storage container exists --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_EXTERNAL_BUCKET --auth-mode login --query exists -o tsv)" = "true" ]; then
  echo "- Bucket '$AZURE_EXTERNAL_BUCKET' already exists, skipping"
else
  echo "- Creating bucket '$AZURE_EXTERNAL_BUCKET'"
  az storage container create --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_EXTERNAL_BUCKET --auth-mode login || exit 1
fi

if [ "$(az storage container exists --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_BUCKET_FORBIDDEN --auth-mode login --query exists -o tsv)" = "true" ]; then
  echo "- Bucket '$AZURE_BUCKET_FORBIDDEN' already exists, skipping"
else
  echo "- Creating bucket '$AZURE_BUCKET_FORBIDDEN'"
  az storage container create --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_BUCKET_FORBIDDEN --auth-mode login || exit 1
fi

## Role Assignment

echo "- Creating role assignments"

source $AFON_ROOT/scripts/azure/account-principal.sh
if az role assignment list --assignee $PRINCIPAL_ID --all --query "[?roleDefinitionName=='Owner' || roleDefinitionName=='User Access Administrator' || roleDefinitionName=='Role Based Access Control Administrator']" -o tsv | grep -q .; then
  echo "- Current principal '$PRINCIPAL_ID' has rights to create role assignments, proceeding"
else
  echo "- FAILURE: Current principal '$PRINCIPAL_ID' lacks RBAC rights needed to create role assignments. Ask your admin to grant one of these roles on '$AZURE_RESOURCE_GROUP', then re-run create.sh."
  exit 1
fi

echo "- Using APP_SP_ID='$APP_SP_ID' as assignee for creating role assignments"
ASSIGNEE_ID="$APP_SP_ID"
STORAGE_BUCKET_ROLE="Storage Blob Data Contributor"
STORAGE_ACCOUNT_ROLE="Storage Blob Delegator"

BUCKET_SCOPE="/subscriptions/$AZURE_SUBSCRIPTION/resourceGroups/$AZURE_RESOURCE_GROUP/providers/Microsoft.Storage/storageAccounts/$AZURE_STORAGE_ACCOUNT/blobServices/default/containers/$AZURE_BUCKET"
if [ -n "$(az role assignment list --assignee-object-id $ASSIGNEE_ID --role "$STORAGE_BUCKET_ROLE" --scope "$BUCKET_SCOPE" --query "[].id" -o tsv)" ]; then
  echo "- Role '$STORAGE_BUCKET_ROLE' already assigned on '$AZURE_BUCKET', skipping"
else
  echo "- Assigning role '$STORAGE_BUCKET_ROLE' to bucket '$AZURE_BUCKET'"
  az role assignment create --role "$STORAGE_BUCKET_ROLE" --assignee-object-id $ASSIGNEE_ID --assignee-principal-type ServicePrincipal --scope "$BUCKET_SCOPE" || exit 1
fi

EXTERNAL_BUCKET_SCOPE="/subscriptions/$AZURE_SUBSCRIPTION/resourceGroups/$AZURE_RESOURCE_GROUP/providers/Microsoft.Storage/storageAccounts/$AZURE_STORAGE_ACCOUNT/blobServices/default/containers/$AZURE_EXTERNAL_BUCKET"
if [ -n "$(az role assignment list --assignee-object-id $ASSIGNEE_ID --role "$STORAGE_BUCKET_ROLE" --scope "$EXTERNAL_BUCKET_SCOPE" --query "[].id" -o tsv)" ]; then
  echo "- Role '$STORAGE_BUCKET_ROLE' already assigned on '$AZURE_EXTERNAL_BUCKET', skipping"
else
  echo "- Assigning role '$STORAGE_BUCKET_ROLE' to bucket '$AZURE_EXTERNAL_BUCKET'"
  az role assignment create --role "$STORAGE_BUCKET_ROLE" --assignee-object-id $ASSIGNEE_ID --assignee-principal-type ServicePrincipal --scope "$EXTERNAL_BUCKET_SCOPE" || exit 1
fi

STORAGE_ACCOUNT_SCOPE="/subscriptions/$AZURE_SUBSCRIPTION/resourceGroups/$AZURE_RESOURCE_GROUP/providers/Microsoft.Storage/storageAccounts/$AZURE_STORAGE_ACCOUNT"
if [ -n "$(az role assignment list --assignee-object-id $ASSIGNEE_ID --role "$STORAGE_ACCOUNT_ROLE" --scope "$STORAGE_ACCOUNT_SCOPE" --query "[].id" -o tsv)" ]; then
  echo "- Role '$STORAGE_ACCOUNT_ROLE' already assigned on '$AZURE_STORAGE_ACCOUNT', skipping"
else
  echo "- Assigning role '$STORAGE_ACCOUNT_ROLE' to '$AZURE_STORAGE_ACCOUNT'"
  az role assignment create --role "$STORAGE_ACCOUNT_ROLE" --assignee-object-id $ASSIGNEE_ID --assignee-principal-type ServicePrincipal --scope "$STORAGE_ACCOUNT_SCOPE" || exit 1
fi

echo "Env created."

