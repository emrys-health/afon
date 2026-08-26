# SDE Azure 

In the following, we detail what is required to perform a deployment to an SDE Azure env. 

We focus, for the moment, on deploying the Polaris stack.

## Pre-requisites

An Azure subscription with the following permissions:

az group create  --name $AZURE_RESOURCE_GROUP   --location $AZURE_REGION 
az storage account show --name $AZURE_STORAGE_ACCOUNT --resource-group $AZURE_RESOURCE_GROUP
az storage account create  --name $AZURE_STORAGE_ACCOUNT  --resource-group $AZURE_RESOURCE_GROUP  --location $AZURE_REGION  --sku Standard_LRS  --kind StorageV2 --enable-hierarchical-namespace false 
az storage container create  --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_BUCKET  --auth-mode login
az ad app create  --display-name "$APP_NAME"  --sign-in-audience "AzureADMultipleOrgs" 
az ad sp show --id $APP_ID
az ad sp create --id $APP_ID 
az role assignment create  --role "Storage Blob Data Contributor" --assignee $APP_ID --scope "$BUCKET_SCOPE"
az network dns zone show --resource-group $AZURE_RESOURCE_GROUP --name $AZURE_DOMAIN_NAME
az network dns zone create  --resource-group $AZURE_RESOURCE_GROUP  --name $AZURE_DOMAIN_NAME
az ad app credential reset --id $APP_ID

