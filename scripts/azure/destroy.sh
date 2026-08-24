source $AFON_ROOT/scripts/config/afon-source.sh azure

APP_NAME="${1:-AfonMultiTenantApp}"
echo "- Destroying app $APP_NAME"
APP_IDS=$(az ad app list --display-name $APP_NAME --query "[].appId" -o tsv)
if [ -z "$APP_IDS" ]; then
  echo "-- No app $APP_NAME found, skipping app/role cleanup"
else
  echo "-- Found $APP_NAME apps(s):"
  echo "$APP_IDS"
  for APP_ID in $APP_IDS; do
    echo "- Deleting app $APP_ID"

    BUCKET_SCOPE="/subscriptions/$AZURE_SUBSCRIPTION/resourceGroups/$AZURE_RESOURCE_GROUP/providers/Microsoft.Storage/storageAccounts/$AZURE_STORAGE_ACCOUNT/blobServices/default/containers/$AZURE_BUCKET"
    if [ -n "$(az role assignment list --assignee $APP_ID --role "Storage Blob Data Contributor" --scope "$BUCKET_SCOPE" --query "[].id" -o tsv)" ]; then
      echo "- Deleting role for $AZURE_BUCKET"
      az role assignment delete  --assignee $APP_ID  --role "Storage Blob Data Contributor" --scope "$BUCKET_SCOPE"
    else
      echo "- No role assignment for $AZURE_BUCKET, skipping"
    fi

    EXTERNAL_BUCKET_SCOPE="/subscriptions/$AZURE_SUBSCRIPTION/resourceGroups/$AZURE_RESOURCE_GROUP/providers/Microsoft.Storage/storageAccounts/$AZURE_STORAGE_ACCOUNT/blobServices/default/containers/$AZURE_EXTERNAL_BUCKET"
    if [ -n "$(az role assignment list --assignee $APP_ID --role "Storage Blob Data Contributor" --scope "$EXTERNAL_BUCKET_SCOPE" --query "[].id" -o tsv)" ]; then
      echo "- Deleting role for $AZURE_EXTERNAL_BUCKET"
      az role assignment delete  --assignee $APP_ID  --role "Storage Blob Data Contributor" --scope "$EXTERNAL_BUCKET_SCOPE"
    else
      echo "- No role assignment for $AZURE_EXTERNAL_BUCKET, skipping"
    fi

    STORAGE_ACCOUNT_SCOPE="/subscriptions/$AZURE_SUBSCRIPTION/resourceGroups/$AZURE_RESOURCE_GROUP/providers/Microsoft.Storage/storageAccounts/$AZURE_STORAGE_ACCOUNT"
    if [ -n "$(az role assignment list --assignee $APP_ID --role "Storage Blob Delegator" --scope "$STORAGE_ACCOUNT_SCOPE" --query "[].id" -o tsv)" ]; then
      echo "- Deleting role for $AZURE_STORAGE_ACCOUNT"
      az role assignment delete  --assignee $APP_ID  --role "Storage Blob Delegator" --scope "$STORAGE_ACCOUNT_SCOPE"
    else
      echo "- No role assignment for $AZURE_STORAGE_ACCOUNT, skipping"
    fi

    echo "- Deleting service principal $APP_ID"
    az ad sp delete --id $APP_ID
    echo "- Deleting app $APP_ID"
    az ad app delete --id $APP_ID
  done
fi

if az storage account show --name $AZURE_STORAGE_ACCOUNT --resource-group $AZURE_RESOURCE_GROUP -o none 2>/dev/null; then
  if [ "$(az storage container exists --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_BUCKET --auth-mode login --query exists -o tsv)" = "true" ]; then
    echo "- Deleting bucket $AZURE_BUCKET"
    az storage container delete  --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_BUCKET
  else
    echo "- Bucket $AZURE_BUCKET does not exist, skipping"
  fi

  if [ "$(az storage container exists --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_EXTERNAL_BUCKET --query exists -o tsv)" = "true" ]; then
    echo "- Deleting bucket $AZURE_EXTERNAL_BUCKET"
    az storage container delete  --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_EXTERNAL_BUCKET
  else
    echo "- Bucket $AZURE_EXTERNAL_BUCKET does not exist, skipping"
  fi

  if [ "$(az storage container exists --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_BUCKET_FORBIDDEN --query exists -o tsv)" = "true" ]; then
    echo "- Deleting bucket $AZURE_BUCKET_FORBIDDEN"
    az storage container delete  --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_BUCKET_FORBIDDEN
  else
    echo "= Bucket $AZURE_BUCKET_FORBIDDEN does not exist, skipping"
  fi

  echo "- Deleting account $AZURE_STORAGE_ACCOUNT"
  az storage account delete  --name $AZURE_STORAGE_ACCOUNT --resource-group $AZURE_RESOURCE_GROUP  --yes
else
  echo "- Storage account $AZURE_STORAGE_ACCOUNT does not exist, skipping account and bucket cleanup"
fi

if [ "$(az group exists --name $AZURE_RESOURCE_GROUP)" = "true" ]; then
  echo "- Deleting resource group $AZURE_RESOURCE_GROUP"
  az group delete  --name $AZURE_RESOURCE_GROUP --yes
else
  echo "- Resource group $AZURE_RESOURCE_GROUP does not exist, skipping"
fi
