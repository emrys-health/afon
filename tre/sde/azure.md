# SDE Azure 

In the following, we detail what is required to perform a deployment to an SDE Azure env. 

We focus, for the moment, on deploying the Polaris stack.

## Pre-requisites 

An Azure subscription with the permissions to create the different elements of the env.
As we recognise that different SDE will have different security policies in place, we list the full set of permissions,
but we allow for some of these to have been already configured.

Creating the Azure env involves an admin part, because the `az ad` commands (Entra ID app/service
principal management) are generally permitted for partner org and must be run by their local admin.

```shell
./afon.sh -a script --script-path azure/create.sh [<as-admin>]
```
where `<as-admin>` is a boolean flag to indicate if you have the local admin rights.
The script reads the afon config properties in `$AFON_HOME/config` (both afon.properties and afon-azure.properties)
and executes the Azure CLI commands.

If you don't have the local admin rights, you'll to ask the local admin to run `azure/create-admin.sh` and 
provide you with `APP_ID` and `APP_SECRET`
```shell
az ad app create --display-name "$APP_NAME"  --sign-in-audience "AzureADMultipleOrgs" 
az ad app credential reset --id $APP_ID
```

### Creating an Azure env from scratch

```shell
# admin
az ad app create  --display-name "$APP_NAME"  --sign-in-audience "AzureADMultipleOrgs"
az ad sp create --id $APP_ID
az ad app credential reset --id $APP_ID
# non-admin
az group create  --name $AZURE_RESOURCE_GROUP   --location $AZURE_REGION 
az storage account show --name $AZURE_STORAGE_ACCOUNT --resource-group $AZURE_RESOURCE_GROUP
az storage account create  --name $AZURE_STORAGE_ACCOUNT  --resource-group $AZURE_RESOURCE_GROUP  --location $AZURE_REGION  --sku Standard_LRS  --kind StorageV2 --enable-hierarchical-namespace false 
az storage container create  --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_BUCKET  --auth-mode login
az role assignment create  --role "Storage Blob Data Contributor" --assignee $APP_ID --scope "$BUCKET_SCOPE"
az network dns zone show --resource-group $AZURE_RESOURCE_GROUP --name $AZURE_DOMAIN_NAME
az network dns zone create  --resource-group $AZURE_RESOURCE_GROUP  --name $AZURE_DOMAIN_NAME
```

## Updating an Azure env partially configured 

Both `azure/create-admin.sh` and `azure/create.sh` are idempotent, so they check if any of the required
elements are already configured and skip the step if so.

