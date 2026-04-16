# Note: you must source a properties file before running this script
# For example:
# set -a && source afon.properties && set +a

APP_ID=$(az ad app list --display-name AfonMultiTenantApp --query "[].appId" -o tsv)
az role assignment delete  --assignee $APP_ID  --role "Storage Blob Data Contributor" --scope /subscriptions/$AZURE_SUBSCRIPTION/resourceGroups/$AZURE_RESOURCE_GROUP/providers/Microsoft.Storage/storageAccounts/$AZURE_STORAGE_ACCOUNT/blobServices/default/containers/$AZURE_BUCKET
az role assignment delete  --assignee $APP_ID  --role "Storage Blob Data Contributor" --scope /subscriptions/$AZURE_SUBSCRIPTION/resourceGroups/$AZURE_RESOURCE_GROUP/providers/Microsoft.Storage/storageAccounts/$AZURE_STORAGE_ACCOUNT/blobServices/default/containers/$AZURE_EXTERNAL_BUCKET
az role assignment delete  --assignee $APP_ID  --role "Storage Blob Delegator"        --scope /subscriptions/$AZURE_SUBSCRIPTION/resourceGroups/$AZURE_RESOURCE_GROUP/providers/Microsoft.Storage/storageAccounts/$AZURE_STORAGE_ACCOUNT

az ad sp delete --id $APP_ID
az ad app delete  --id $APP_ID
az storage container delete  --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_BUCKET
az storage account delete  --name $AZURE_STORAGE_ACCOUNT --resource-group $AZURE_RESOURCE_GROUP  --yes
az group delete  --name $AZURE_RESOURCE_GROUP --yes
