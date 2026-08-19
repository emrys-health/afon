source $AFON_ROOT/scripts/config/afon-source.sh azure

APP_NAME="${1:-AfonMultiTenantApp}"
echo "- Creating app $APP_NAME"
if [ "$(az group exists --name $AZURE_RESOURCE_GROUP)" = "true" ]; then
  echo "- Resource group $AZURE_RESOURCE_GROUP already exists, skipping"
else
  echo "- Creating group $AZURE_RESOURCE_GROUP in $AZURE_REGION"
  az group create  --name $AZURE_RESOURCE_GROUP   --location $AZURE_REGION || exit 1
fi

if az storage account show --name $AZURE_STORAGE_ACCOUNT --resource-group $AZURE_RESOURCE_GROUP -o none 2>/dev/null; then
  echo "- Storage account $AZURE_STORAGE_ACCOUNT already exists, skipping"
else
  echo "- Creating storage account $AZURE_STORAGE_ACCOUNT"
  az storage account create  --name $AZURE_STORAGE_ACCOUNT  --resource-group $AZURE_RESOURCE_GROUP  --location $AZURE_REGION  --sku Standard_LRS  --kind StorageV2 --enable-hierarchical-namespace false || exit 1
fi

if [ "$(az storage container exists --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_BUCKET --auth-mode login --query exists -o tsv)" = "true" ]; then
  echo "- Bucket $AZURE_BUCKET already exists, skipping"
else
  echo "- Creating bucket $AZURE_BUCKET"
  az storage container create  --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_BUCKET  --auth-mode login || exit 1
fi

if [ "$(az storage container exists --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_EXTERNAL_BUCKET --query exists -o tsv)" = "true" ]; then
  echo "- Bucket $AZURE_EXTERNAL_BUCKET already exists, skipping"
else
  echo "- Creating bucket $AZURE_EXTERNAL_BUCKET"
  az storage container create  --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_EXTERNAL_BUCKET || exit 1
fi

if [ "$(az storage container exists --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_BUCKET_FORBIDDEN --query exists -o tsv)" = "true" ]; then
  echo "- Bucket $AZURE_BUCKET_FORBIDDEN already exists, skipping"
else
  echo "- Creating bucket $AZURE_BUCKET_FORBIDDEN"
  az storage container create  --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_BUCKET_FORBIDDEN || exit 1
fi

APP_IDS=$(az ad app list --display-name $APP_NAME --query "[].appId" -o tsv)
if [ -z "$APP_IDS" ]; then
  echo "- No existing $APP_NAME found, creating one"
  az ad app create  --display-name "$APP_NAME"  --sign-in-audience "AzureADMultipleOrgs" || exit 1
  echo "Waiting 30s for app to be registered..."
  sleep 30
  APP_IDS=$(az ad app list --display-name $APP_NAME --query "[].appId" -o tsv)
else
  echo "- Found existing $APP_NAME app(s), reusing rather than creating a duplicate:"
  echo "$APP_IDS"
fi

APP_ID=$(echo "$APP_IDS" | head -n1)
if [ -z "$APP_ID" ]; then
  echo "-- Failed to find or create $APP_NAME"
  exit 1
fi
if [ "$(echo "$APP_IDS" | wc -l)" -gt 1 ]; then
  echo "-- As multiple $APP_NAME apps exist, using the first one: $APP_ID"
  echo "-- Run destroy.sh to remove ALL of them, then re-run create.sh for a clean single app."
fi
echo "APP_ID=$APP_ID"

if az ad sp show --id $APP_ID -o none 2>/dev/null; then
  echo "- Service principal for $APP_ID already exists, skipping"
else
  echo "- Creating service principal for $APP_ID"
  az ad sp create --id $APP_ID || exit 1
fi

BUCKET_SCOPE="/subscriptions/$AZURE_SUBSCRIPTION/resourceGroups/$AZURE_RESOURCE_GROUP/providers/Microsoft.Storage/storageAccounts/$AZURE_STORAGE_ACCOUNT/blobServices/default/containers/$AZURE_BUCKET"
if [ -n "$(az role assignment list --assignee $APP_ID --role "Storage Blob Data Contributor" --scope "$BUCKET_SCOPE" --query "[].id" -o tsv)" ]; then
  echo "- Role Storage Blob Data Contributor already assigned on $AZURE_BUCKET, skipping"
else
  echo "- Permissioning bucket $AZURE_BUCKET"
  az role assignment create  --role "Storage Blob Data Contributor" --assignee $APP_ID --scope "$BUCKET_SCOPE" || exit 1
fi

EXTERNAL_BUCKET_SCOPE="/subscriptions/$AZURE_SUBSCRIPTION/resourceGroups/$AZURE_RESOURCE_GROUP/providers/Microsoft.Storage/storageAccounts/$AZURE_STORAGE_ACCOUNT/blobServices/default/containers/$AZURE_EXTERNAL_BUCKET"
if [ -n "$(az role assignment list --assignee $APP_ID --role "Storage Blob Data Contributor" --scope "$EXTERNAL_BUCKET_SCOPE" --query "[].id" -o tsv)" ]; then
  echo "- Role Storage Blob Data Contributor already assigned on $AZURE_EXTERNAL_BUCKET, skipping"
else
  echo "- Permissioning bucket $AZURE_EXTERNAL_BUCKET"
  az role assignment create  --role "Storage Blob Data Contributor" --assignee $APP_ID --scope "$EXTERNAL_BUCKET_SCOPE" || exit 1
fi

STORAGE_ACCOUNT_SCOPE="/subscriptions/$AZURE_SUBSCRIPTION/resourceGroups/$AZURE_RESOURCE_GROUP/providers/Microsoft.Storage/storageAccounts/$AZURE_STORAGE_ACCOUNT"
if [ -n "$(az role assignment list --assignee $APP_ID --role "Storage Blob Delegator" --scope "$STORAGE_ACCOUNT_SCOPE" --query "[].id" -o tsv)" ]; then
  echo "- Role Storage Blob Delegator already assigned on $AZURE_STORAGE_ACCOUNT, skipping"
else
  echo "- Assigning roles"
  az role assignment create  --role "Storage Blob Delegator" --assignee $APP_ID --scope "$STORAGE_ACCOUNT_SCOPE" || exit 1
fi

if az network dns zone show --resource-group $AZURE_RESOURCE_GROUP --name $AZURE_DOMAIN_NAME -o none 2>/dev/null; then
  echo "- DNS zone $AZURE_DOMAIN_NAME already exists, skipping"
else
  echo "- Creating DNS zone $AZURE_DOMAIN_NAME"
  az network dns zone create  --resource-group $AZURE_RESOURCE_GROUP  --name $AZURE_DOMAIN_NAME || exit 1
fi

echo "Azure env created."
# App credential reset not idempotent by design: Azure never re-exposes an existing secret's value,
# so reset command must always be run to print a new secret, invalidating any previous secret.
APP_SECRET_RESET="${APP_SECRET_RESET:-}"
if [ -n "$APP_SECRET_RESET" ]; then
  echo "- Resetting app credential."
  echo "- From the output below, add the following properties in your config/secret-azure.properties"
  echo "AZURE_CLIENT_ID=<appId>"
  echo "AZURE_TENANT_ID=<tenant>"
  echo "AZURE_CLIENT_SECRET=<password>"
  az ad app credential reset --id $APP_ID
else
  echo "- Not resetting app credentials, as APP_SECRET_RESET is not set."
fi

