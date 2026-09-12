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

## Updating an Azure env partially configured 

Both `azure/create-admin.sh` and `azure/create.sh` are idempotent, so they check if any of the required
elements are already configured and skip the step if so.

## Roles and Permissions

Refer to [roles and permissions](../../scripts/azure/roles.md) document for a detailed breakdown the roles and permissions
required to set up the Azure env.

To check what roles are assigned to your identity:
```shell
./afon.sh -a script -sp azure/roles.sh
```

## An end-to-end example

Here we detail the creation of an Azure env with a concrete usecase: the TVS SDE.

We assume in the following that domain `tvspolarisazure.click` has been registered at Route53 or another registrar.

Per SDE policy, the `create-admin.sh` must be run by a local admin, who'll then be able to provide the following:
- SUBSCRIPTION
- TENANT
- APP_ID
- APP_SP_ID
- APP_SECRET

First run, to init the env `tvs`:
```shell
./afon.sh -a config-init -e tvs
```
The config properties will be initialised in `~/.afon/config-tvs`.

Set in `afon.properties`
```shell
ENV_NAME=tvs
```
Set in `afon-azure.properties`
```shell
AZURE_SUBSCRIPTION=<SUBSCRIPTION>
AZURE_POLARIS_DOMAIN_NAME=<POLARIS_DOMAIN_NAME>
```
where the Polaris domain name can be something like: `${ENV_NAME}polarisazure.click`.

Set in `secret-azure.properties`
```shell
AZURE_TENANT_ID=<TENANT>
AZURE_APP_ID=<APP_ID>
AZURE_APP_SECRET=<APP_SECRET>
AZURE_SP_ID=<APP_SP_ID>
```

After all properties are set, switch to using env `tvs`:
```shell
./afon.sh -a config-switch -e tvs
```
This simply creates a symlink `~/.afon/config` to `~/.afon/config-tvs`.   Thereafter, you don't need to specify the env any more.

Next, login: 
```shell
./afon.sh -a script -sp azure/login.sh 
./afon.sh -a script -sp azure/account.sh
```
The `login.sh` logs in the user with the given `$AZURE_TENANT_ID`, while `account.sh` sets the `$AZURE_SUBSCRIPTION`.

Finally, to create the env:
```shell
./afon.sh -a script -sp azure/create.sh
```

Once the env is created, you can issue a TLS certificate for the Polaris domain name and store it in the Azure Key Vault (AKV)

Set in `afon-azure.properties`
```shell
AZURE_KEY_VAULT_NAME=${ENV_NAME}-afon-key-vault
```
and run
```shell
./afon.sh -a script -sp azure/issue-cert.sh
```
You can check if the certs have been stored in the AKV:
```shell
./afon.sh -a script -sp azure/keyvault.sh -sa list
```

**NOTE:**  Although technically not mandatory, it is highly advisable to issue and store the TLS cert in the AKV. If the cert is not found in the vault or the vault is not defined, a new cert will be issued by ACME, but there is a stringent limit of how many free certs can be issued in a week. 

Finally, to destroy the env:
```shell
./afon.sh -a script -sp azure/destroy.sh
```
Following this, `destroy-admin.sh` must be run by a local admin.
