# Note: you must source a properties file before running this script
# For example:
# set -a && source afon.properties && source afon-azure.properties && set +a

APP_ID=$(az ad app list --display-name AfonMultiTenantApp --query "[].appId" -o tsv)
echo APP_ID=$APP_ID
echo Deleting role for $AZURE_BUCKET
az role assignment delete  --assignee $APP_ID  --role "Storage Blob Data Contributor" --scope /subscriptions/$AZURE_SUBSCRIPTION/resourceGroups/$AZURE_RESOURCE_GROUP/providers/Microsoft.Storage/storageAccounts/$AZURE_STORAGE_ACCOUNT/blobServices/default/containers/$AZURE_BUCKET
echo Deleting role for $AZURE_EXTERNAL_BUCKET
az role assignment delete  --assignee $APP_ID  --role "Storage Blob Data Contributor" --scope /subscriptions/$AZURE_SUBSCRIPTION/resourceGroups/$AZURE_RESOURCE_GROUP/providers/Microsoft.Storage/storageAccounts/$AZURE_STORAGE_ACCOUNT/blobServices/default/containers/$AZURE_EXTERNAL_BUCKET
echo Deleting role for $AZURE_STORAGE_ACCOUNT
az role assignment delete  --assignee $APP_ID  --role "Storage Blob Delegator"        --scope /subscriptions/$AZURE_SUBSCRIPTION/resourceGroups/$AZURE_RESOURCE_GROUP/providers/Microsoft.Storage/storageAccounts/$AZURE_STORAGE_ACCOUNT

echo Deleting service principle $APP_ID
az ad sp delete --id $APP_ID
echo Deleting app $APP_ID
az ad app delete  --id $APP_ID

echo Deleting bucket $AZURE_BUCKET
az storage container delete  --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_BUCKET
echo Deleting bucket $AZURE_EXTERNAL_BUCKET
az storage container delete  --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_EXTERNAL_BUCKET
echo Deleting $AZURE_BUCKET_FORBIDDEN
az storage container delete  --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_BUCKET_FORBIDDEN
echo Deleting account $AZURE_STORAGE_ACCOUNT
az storage account delete  --name $AZURE_STORAGE_ACCOUNT --resource-group $AZURE_RESOURCE_GROUP  --yes
echo Deleting resource group $AZURE_RESOURCE_GROUP
az group delete  --name $AZURE_RESOURCE_GROUP --yes
