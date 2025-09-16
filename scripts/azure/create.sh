az group create  --name afon_resource_group   --location uksouth
az storage account create  --name afonstorageaccount  --resource-group afon_resource_group  --location uksouth  --sku Standard_LRS  --kind StorageV2 --enable-hierarchical-namespace false
az storage container create  --account-name afonstorageaccount --name afonstorage  --auth-mode login
az ad app create  --display-name "AfonMultiTenantApp"  --sign-in-audience "AzureADMultipleOrgs"
APP_ID=$(az ad app list --display-name AfonMultiTenantApp --query "[].appId" -o tsv)
az ad sp create --id $APP_ID
az role assignment create  --assignee $APP_ID  --role "Storage Blob Data Contributor" --scope /subscriptions/ffae1297-4c57-47e0-9a89-5583b97835cf/resourceGroups/afon_resource_group

