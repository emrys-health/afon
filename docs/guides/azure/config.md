# Azure config and common setup

The config and setup steps common to the Azure env of a [TRE](tre.md) (stack `azure-full`) and
of a [data provider](data.md) (stack `azure-polaris`).  Do these first, then follow the doc of your side.

## 1. Pre-requisites

- An Azure subscription with the permissions to create the env's elements (see [roles](../../../scripts/azure/roles.md)).
- A registered domain name for each endpoint the stack serves (see [DNS configuration](../../dns_config.md)), eg
  `<env>polarisazure.click`.  Its DNS zone is created by `dns-create-zones azure`, after which the domain must be
  delegated to the Azure nameservers.
- A Linux or macOS machine.

## 2. Install the Afon CLI

Unzip the latest `afon-<version>-cli.zip` to `$HOME/.afon/cli` and add it to the path, in `~/.bash_profile`:
```shell
export AFON_CLI_DIR=$HOME/.afon/cli
PATH=$AFON_CLI_DIR:$PATH
export PATH
```
Check with `afon-cli.sh`, which lists the commands.

## 3. Install the local tools

```shell
afon-cli.sh local-setup -c azure
```
Installs git, jdk, maven, docker, opentofu, kubernetes-cli, `azure-cli` and `kubelogin`.

## 4. Admin part: the Entra ID app

The `az ad` commands (Entra ID app/service principal management) are generally allowed only to a local admin
(role **Application Owner**, see [roles](../../../scripts/azure/roles.md)), so the admin part of the env creation,
`azure/create-admin.sh`, is run by an admin, who passes the values on to the deployer for `secret-azure.properties`.

We assume the admin and the deployer are different people: the admin runs this step, the deployer runs everything else
and never needs the `az ad` rights.

### Run create-admin.sh (as the admin)

The admin needs the Afon CLI ([§2](#2-install-the-afon-cli)) and `azure-cli` ([§3](#3-install-the-local-tools)), but no env config or other step of this doc: the script only uses `az`.

1. Log in to the tenant and select the subscription:
   ```shell
   az login --tenant <TENANT_ID>
   az account set --subscription <SUBSCRIPTION_ID>
   ```
2. Run the script, with `APP_CREDENTIAL_RESET` set to non-empty so it also prints a new app secret.
   The optional argument is the app name, `AfonMultiTenantApp` by default:
   ```shell
   APP_CREDENTIAL_RESET=true afon-cli.sh azure-create-admin [<app-name>]
   ```
   It creates the multi-tenant app, waits for it to register (30s), then creates its service principal, retrying
   while Azure AD replicates.  It is idempotent: an existing app and service principal are reused, with a warning if there is more than one app of that name.
3. Read the values from the output:
   ```
   - APP_ID=<APP_ID>
   - APP_SP_ID=<APP_SP_ID>
   ...
   {
     "appId": "<APP_ID>",
     "password": "<APP_SECRET>",
     "tenant": "<TENANT_ID>"
   }
   ```
   `password` is `APP_SECRET`, and `tenant` is `TENANT_ID`.

**NOTE:** The secret is shown only once: Azure never re-exposes an existing one.  Re-running with `APP_CREDENTIAL_RESET`
set issues a new secret and invalidates the previous one, so do it only if the secret is lost.  Without it, only `APP_ID` and `APP_SP_ID` are printed.

If you need to look up the other values later:
```shell
az account tenant list --query "[].tenantId" -o tsv                 # TENANT_ID
az account show --query id -o tsv                                   # SUBSCRIPTION_ID
az ad app list --display-name AfonMultiTenantApp --query "[].appId" -o tsv   # APP_ID
az ad sp show --id <APP_ID> --query id -o tsv                       # APP_SP_ID
```

### Hand over to the deployer

The admin securely provides `SUBSCRIPTION_ID`, `TENANT_ID`, `APP_ID`, `APP_SP_ID` and `APP_SECRET`, which the deployer sets in step 5 as:

| Value | Property | File |
|-------|----------|------|
| `SUBSCRIPTION_ID` | `AZURE_SUBSCRIPTION` | `afon-azure.properties` |
| `TENANT_ID` | `AZURE_TENANT_ID` | `secret-azure.properties` |
| `APP_ID` | `AZURE_APP_ID` | `secret-azure.properties` |
| `APP_SP_ID` | `AZURE_SP_ID` | `secret-azure.properties` |
| `APP_SECRET` | `AZURE_APP_SECRET` | `secret-azure.properties` |

The deployer then runs `create.sh` ([§9](#9-create-the-env-and-check-the-storage)) without admin rights.  Both scripts are idempotent.

## 5. Create and fill in the config

Choose the env name, eg `xyz`.  This creates `~/.afon/config-xyz` (and the dir for the `tofu` state):
```shell
afon-cli.sh config-init -e xyz -c azure
afon-cli.sh cdktf-init -e xyz -c azure
```
Set in `afon.properties`:
```shell
ENV_NAME=xyz
```
Set in `afon-azure.properties` (the properties common to every Azure stack):
```shell
AZURE_SUBSCRIPTION=<SUBSCRIPTION_ID>
AZURE_REGION=<region>
AZURE_RESOURCE_GROUP=<resource group>
AZURE_STORAGE_ACCOUNT=${ENV_NAME}afonstorageaccount
AZURE_KEY_VAULT_NAME=${ENV_NAME}-afon-key-vault    # globally unique
```
Set in `secret-azure.properties`, with the values the admin provided:
```shell
AZURE_TENANT_ID=<TENANT_ID>
AZURE_APP_ID=<APP_ID>
AZURE_APP_SECRET=<APP_SECRET>
AZURE_SP_ID=<APP_SP_ID>
```
Set the Polaris user in `secret.properties` (`USER_ID`).  The stack-specific properties are in the docs of each side.

Validate, then switch to the env, which just symlinks `~/.afon/config` to `~/.afon/config-xyz`
(never switch env while a command is running):
```shell
afon-cli.sh config-validate -c azure
afon-cli.sh config-switch -e xyz
```
Config can also be shared with `afon-cli.sh config-{import|export} <zip> -e xyz`.
See [configuration](../../configuration.md) for all the properties.

## 6. Login

Logs in with `$AZURE_TENANT_ID` and sets the account to `$AZURE_SUBSCRIPTION`:
```shell
afon-cli.sh azure-login
```
Use `azure-account` to select the subscription again without logging in, eg after switching env.

## 7. DNS zones

After `azure-create` (step 9), which creates the resource group, `afon-cli.sh dns-create-zones azure <stack>` creates one DNS zone per domain
the stack serves.  Then delegate each domain to it, at the registrar or, for a subdomain, with `afon-cli.sh dns-delegate azure <stack>`
(see [DNS configuration](../../dns_config.md)), and check:
```shell
az network dns zone show --name <domain> --resource-group $AZURE_RESOURCE_GROUP --query nameServers
dig +short A <domain>
```

## 8. TLS certificate in the Key Vault

Advisable, as ACME limits how many free certs can be issued in a week; if the cert is not in the vault, a new one is issued.
Run after `azure-create`, once for each domain, with the domain set in the config:
```shell
afon-cli.sh azure-issue-cert                       # free ACME issuer
afon-cli.sh azure-issue-cert <digicert|globalsign> # paying issuers: set AZURE_CA_ID and AZURE_CA_PASSWORD in secret-azure.properties first
afon-cli.sh azure-keyvault list                    # check the certs are stored
```

## 9. Create the env and check the storage

```shell
afon-cli.sh azure-create [<as-admin>] <stack-id>   # stack-id: azure-full|azure-keycloak|azure-polaris, or its role tre|catalog|identity
```
The flag defaults to `false`.  The stack decides what is created: the Gitea
container `AZURE_GITEA_BUCKET` (stacks with Git, ie `azure-full`) and the Polaris logs container `AZURE_POLARIS_LOG_BUCKET` if set.

`azure-create` ends by checking that `AZURE_CONNECTION_STRING` is the storage account's, which it is not if the account
was recreated.  Get the current one, set it in `secret-azure.properties`, and check:
```shell
az storage account show-connection-string --resource-group $AZURE_RESOURCE_GROUP --name $AZURE_STORAGE_ACCOUNT --query connectionString -o tsv
afon-cli.sh azure-check-storage
```

## 10. Useful checks and teardown

```shell
afon-cli.sh azure-cluster <stack|role>              # is the AKS cluster up?
afon-cli.sh azure-update-kube-config <stack|role>   # point kubectl at it
afon-cli.sh azure-polaris                           # is Polaris serving?
afon-cli.sh azure-keycloak                          # is KeyCloak serving?
afon-cli.sh azure-destroy                           # then a local admin runs destroy-admin.sh
```
