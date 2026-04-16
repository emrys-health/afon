# Note: you must source a properties file before running this script
# For example:
# set -a && source afon.properties && set +a

az group create  --name $AZURE_RESOURCE_GROUP   --location $AZURE_REGION && \
az storage account create  --name $AZURE_STORAGE_ACCOUNT  --resource-group $AZURE_RESOURCE_GROUP  --location $AZURE_REGION  --sku Standard_LRS  --kind StorageV2 --enable-hierarchical-namespace false && \
az storage container create  --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_BUCKET  --auth-mode login && \
az storage container create  --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_EXTERNAL_BUCKET && \
az ad app create  --display-name "AfonMultiTenantApp"  --sign-in-audience "AzureADMultipleOrgs" && \
echo "Waiting 30s for app to be registered..." && \
sleep 30 && \
APP_ID=$(az ad app list --display-name AfonMultiTenantApp --query "[].appId" -o tsv) && \
az ad sp create --id $APP_ID && \
az role assignment create  --role "Storage Blob Data Contributor" --assignee $APP_ID --scope "/subscriptions/$AZURE_SUBSCRIPTION/resourceGroups/$AZURE_RESOURCE_GROUP/providers/Microsoft.Storage/storageAccounts/$AZURE_STORAGE_ACCOUNT/blobServices/default/containers/$AZURE_BUCKET" && \
az role assignment create  --role "Storage Blob Data Contributor" --assignee $APP_ID --scope "/subscriptions/$AZURE_SUBSCRIPTION/resourceGroups/$AZURE_RESOURCE_GROUP/providers/Microsoft.Storage/storageAccounts/$AZURE_STORAGE_ACCOUNT/blobServices/default/containers/$AZURE_EXTERNAL_BUCKET" && \
az role assignment create  --role "Storage Blob Delegator" --assignee $APP_ID --scope "/subscriptions/$AZURE_SUBSCRIPTION/resourceGroups/$AZURE_RESOURCE_GROUP/providers/Microsoft.Storage/storageAccounts/$AZURE_STORAGE_ACCOUNT" && \
echo "Environment created. Now put these details in your Polaris environment variables:"
az ad app credential reset --id $APP_ID

az storage container create  --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_BUCKET_FORBIDDEN

