az group create  --name afon_resource_group   --location uksouth && \
az storage account create  --name afonstorageaccount  --resource-group afon_resource_group  --location uksouth  --sku Standard_LRS  --kind StorageV2 --enable-hierarchical-namespace false && \
az storage container create  --account-name afonstorageaccount --name afonstorage  --auth-mode login && \
az storage container create  --account-name afonstorageaccount --name afonstorage-2 && \
az ad app create  --display-name "AfonMultiTenantApp"  --sign-in-audience "AzureADMultipleOrgs" && \
echo "Waiting 30s for app to be registered..." && \
sleep 30 && \
APP_ID=$(az ad app list --display-name AfonMultiTenantApp --query "[].appId" -o tsv) && \
az ad sp create --id $APP_ID && \
az role assignment create  --role "Storage Blob Data Contributor" --assignee $APP_ID --scope "/subscriptions/ffae1297-4c57-47e0-9a89-5583b97835cf/resourceGroups/afon_resource_group/providers/Microsoft.Storage/storageAccounts/afonstorageaccount/blobServices/default/containers/afonstorage" && \
az role assignment create  --role "Storage Blob Data Contributor" --assignee $APP_ID --scope "/subscriptions/ffae1297-4c57-47e0-9a89-5583b97835cf/resourceGroups/afon_resource_group/providers/Microsoft.Storage/storageAccounts/afonstorageaccount/blobServices/default/containers/afonstorage-2" && \
az role assignment create  --role "Storage Blob Delegator" --assignee $APP_ID --scope "/subscriptions/ffae1297-4c57-47e0-9a89-5583b97835cf/resourceGroups/afon_resource_group/providers/Microsoft.Storage/storageAccounts/afonstorageaccount" && \
echo "Environment created. Now put these details in your Polaris environment variables:"
az ad app credential reset --id $APP_ID

az storage container create  --account-name afonstorageaccount --name afonstorage-forbidden

