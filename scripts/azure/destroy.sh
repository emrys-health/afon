# Note: you must source a properties file before running this script
# For example:
# set -a && source afon.properties && set +a

APP_ID=$(az ad app list --display-name AfonMultiTenantApp --query "[].appId" -o tsv)
az role assignment delete  --assignee $APP_ID  --role "Storage Blob Data Contributor" --scope /subscriptions/ffae1297-4c57-47e0-9a89-5583b97835cf/resourceGroups/afon_resource_group/providers/Microsoft.Storage/storageAccounts/afonstorageaccount/blobServices/default/containers/$AZURE_BUCKET
az role assignment delete  --assignee $APP_ID  --role "Storage Blob Data Contributor" --scope /subscriptions/ffae1297-4c57-47e0-9a89-5583b97835cf/resourceGroups/afon_resource_group/providers/Microsoft.Storage/storageAccounts/afonstorageaccount/blobServices/default/containers/$AZURE_EXTERNAL_BUCKET
az role assignment delete  --assignee $APP_ID  --role "Storage Blob Delegator"        --scope /subscriptions/ffae1297-4c57-47e0-9a89-5583b97835cf/resourceGroups/afon_resource_group/providers/Microsoft.Storage/storageAccounts/afonstorageaccount

az ad sp delete --id $APP_ID
az ad app delete  --id $APP_ID
az storage container delete  --account-name afonstorageaccount --name $AZURE_BUCKET
az storage account delete  --name afonstorageaccount  --resource-group afon_resource_group  --yes
az group delete  --name afon_resource_group   --yes
