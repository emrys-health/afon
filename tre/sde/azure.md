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
The flag is optional, so by default you'll run without admin rights.

The script reads the afon config properties in `$AFON_HOME/config` (both afon.properties and afon-azure.properties)
and executes the Azure CLI commands.

If you don't have the local admin rights, you'll to ask the local admin to run `azure/create-admin.sh` and 
provide you with `APP_ID` and `APP_SECRET`.

## Creating an Azure env from scratch

The following `az` cli commands are required:
```shell
# admin
az ad app create  --display-name "$APP_NAME"  --sign-in-audience "AzureADMultipleOrgs"
az ad sp create --id $APP_ID
az ad app credential reset --id $APP_ID
# non-admin
az group create  --name $AZURE_RESOURCE_GROUP   --location $AZURE_REGION 
az network dns zone create  --resource-group $AZURE_RESOURCE_GROUP  --name $AZURE_DOMAIN_NAME
az storage account create  --name $AZURE_STORAGE_ACCOUNT  --resource-group $AZURE_RESOURCE_GROUP  --location $AZURE_REGION  --sku Standard_LRS  --kind StorageV2 --enable-hierarchical-namespace false 
az storage container create  --account-name $AZURE_STORAGE_ACCOUNT --name $AZURE_BUCKET  --auth-mode login
az role assignment create  --role "Storage Blob Data Contributor" --assignee $APP_ID --scope "$BUCKET_SCOPE"
```

## Updating an Azure env partially configured 

Both `azure/create-admin.sh` and `azure/create.sh` are idempotent, so they check if any of the required
elements are already configured and skip the step if so.

## Roles and Permissions

Refer to [roles and permissions](../../scripts/azure/roles.md) document for a detailed breakdown the roles and permissions
required to set up the Azure env.

## An end-to-end example

Here we detail the creation of an Azure env with a concrete usecase: the TVS SDE.

We assume in the following that domain `tvspolarisazure.click` has been registered at Route53 or another registrar.

Per SDE policy, the `create-admin.sh` must be run by a local admin, who'll then be able to provide the following:
- SUBSCRIPTION
- TENANT
- APP_ID
- APP_SECRET

First run:
```shell
./afon.sh -a config-init --config-env tvs
```
The config properties will be initialised in `~/.afon/config-tvs`.

Set in `afon.properties`
```shell
ENV_NAME=tvs
```
Set in `afon-azure.properties`
```shell
AZURE_SUBSCRIPTION=<SUBSCRIPTION>
```
Set in `secret-azure.properties`
```shell
AZURE_TENANT_ID=<TENANT>
AZURE_CLIENT_ID=<APP_ID>
AZURE_CLIENT_SECRET=<APP_SECRET>
```

After all properties are set, switch to using env `tvs`:
```shell
./afon.sh -a config-switch --config-env tvs
```
This simply creates a symlink `~/.afon/config` to `~/.afon/config-tvs`.

Next, login: 
```shell
./afon.sh -a script --script-path azure/login.sh 
./afon.sh -a script --script-path azure/account.sh
```
The `login.sh` logs in the user with the given `$AZURE_TENANT_ID`, while `account.sh` sets the `$AZURE_SUBSCRIPTION`.

Finally, to create the env:
```shell
./afon.sh -a script --script-path azure/create.sh
```
