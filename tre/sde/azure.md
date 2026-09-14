# SDE Azure env

In the following, we detail what is required to perform a deployment to an SDE Azure env. 

We focus here on creating an Azure env to deploy the Polaris stack.

## Pre-requisites 

An Azure subscription with the permissions to create the different elements of the env.

Since different SDEs will have different security policies in place, we allow for some of these to have been already configured by a local admin.

Creating the Azure env involves an admin part, because the `az ad` commands (Entra ID app/service
principal management) are generally allowed to run only by a local admin.

```shell
./afon.sh -a script -sp azure/create.sh [<as-admin>]
```
where `<as-admin>` is a boolean flag to indicate if you have the local admin rights.
The flag is optional, and by default you'll run **without** admin rights.  

The script reads the afon config properties in `$AFON_HOME/config` (`afon.properties`, `afon-azure.properties` and `secret-azure.properties`) and executes the Azure CLI commands.

If you don't have the local admin rights, you'll need to ask a local admin to run the admin part of the create script (`azure/create-admin.sh`) and provide the following variables:
- SUBSCRIPTION_ID
- TENANT_ID
- APP_ID
- APP_SP_ID
- APP_SECRET

**NOTE:** Both `azure/create-admin.sh` and `azure/create.sh` are idempotent, so they can be run any number of times.  If any of the required elements are already created, they will skip to the next.

## Roles and Permissions

To run the scripts, a certain number of roles and permissions are required (even for the non-admin part).  
Refer to [roles](../../scripts/azure/roles.md) document for a detailed breakdown of what is required to set up the Azure env.

To check what roles are assigned to your identity:
```shell
./afon.sh -a script -sp azure/roles.sh
```

## An end-to-end example

Here we detail the creation of an Azure env with a concrete use case: the XYZ SDE.

We assume that the domain `xyzpolarisazure.click` has been registered at Route53 or another registrar.

To start off, init the env `xyz`:
```shell
./afon.sh -a config-init -e xyz
```
The config properties will be initialised in `~/.afon/config-xyz`.

Set in `afon.properties`
```shell
ENV_NAME=xyz
```
and in `afon-azure.properties`
```shell
AZURE_POLARIS_DOMAIN_NAME=${ENV_NAME}polarisazure.click
```

Assuming a local admin has run `create-admin.sh`, fill in the following properties with the information provided by the admin.

Set in `afon-azure.properties`
```shell
AZURE_SUBSCRIPTION=<SUBSCRIPTION_ID>
```

Set in `secret-azure.properties`
```shell
AZURE_TENANT_ID=<TENANT_ID>
AZURE_APP_ID=<APP_ID>
AZURE_APP_SECRET=<APP_SECRET>
AZURE_SP_ID=<APP_SP_ID>
```

After all properties are set, switch to using env `xyz`:
```shell
./afon.sh -a config-switch -e xyz
```
This simply creates a symlink `~/.afon/config` to `~/.afon/config-xyz`.   Thereafter, you don't need to specify the env any more.

Next, login and set account subscription:
```shell
./afon.sh -a script -sp azure/login.sh 
./afon.sh -a script -sp azure/account.sh
```
The `login.sh` logs in the user with the given `$AZURE_TENANT_ID`, while `account.sh` sets account to the `$AZURE_SUBSCRIPTION`.

Finally, to create the env:
```shell
./afon.sh -a script -sp azure/create.sh
```

Once the env is created, you can issue a TLS certificate for the Polaris domain name and store it in the Azure Key Vault (AKV)

Set in `afon-azure.properties`
```shell
AZURE_KEY_VAULT_NAME=${ENV_NAME}-afon-key-vault
```
and run to issue cert with free ACME issuer:
```shell
./afon.sh -a script -sp azure/issue-cert.sh
```

The `issue-cert.sh` script also supports two paying issuers, `digicert` and `globalsign`, integrated in the AKV CA. If you choose to use them, first set the issuer credentials in the `secret-azure.properties`:
```shell
AZURE_CA_ID=<ISSUER_ID>
AZURE_CA_PASSWORD=<ISSUER_PASSWORD>
```
and run: 
```shell
./afon.sh -a script -sp azure/issue-cert.sh -sa <digicert|globalsign>
```

After issuance, you can check if the certs have been stored in the AKV:
```shell
./afon.sh -a script -sp azure/keyvault.sh -sa list
```

**NOTE:**  Although technically not mandatory, it is highly advisable to issue and store the TLS cert in the AKV. If the cert is not found in the vault or the vault name is not defined in the properties, a new cert will be issued by ACME, but there is a stringent limit of how many free certs can be issued in a week.

Once the env is created, you can proceed to the deployment of the [Azure Polaris stack](../../docs/stacks.md).

## Destroying an env

To destroy the env:
```shell
./afon.sh -a script -sp azure/destroy.sh
```
Following this, `destroy-admin.sh` must be run by a local admin.
