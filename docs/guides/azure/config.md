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

#### Federated credential instead of a secret

The admin may give the app registration a federated credential, which replaces the secret: a token of an issuer that the credential trusts, for a subject it names,
acts as the app.  Then there is no `APP_SECRET`, and the deployer sets `AZURE_WORKLOAD_IDENTITY=true` in `secret-azure.properties` and leaves `AZURE_APP_SECRET`
blank.  The flag is explicit and never inferred from a blank secret: with `AZURE_WORKLOAD_IDENTITY=false` (the default) a blank `AZURE_APP_SECRET` is an error.

The app needs two credentials, for two different jobs, so that nothing holds a secret:

| Credential | For | Issuer | Subject | Created |
|------------|-----|--------|---------|---------|
| deployer's login | the deploy logging in as the app | the one the pipeline's identity provider has, see 2 | that of the pipeline, eg `sc://<organization>/<project>/<service connection name>` | once |
| Polaris pod | Polaris reading and writing the storage | that of the AKS cluster, which exists only once the cluster is up | `system:serviceaccount:default:polaris` | for each cluster, again if it is rebuilt |

The audience of both is `api://AzureADTokenExchange`.

**Step by step.**  The commands that need the rights to change the app registration (Application Owner or Application Administrator) are the admin's, as in step 1.

1. *Admin: create the app and its service principal, without a secret.*  As in [Run create-admin.sh](#run-create-adminsh-as-the-admin), but leave `APP_CREDENTIAL_RESET` unset,
   so no secret is created.  `azure-login` logs in to `AZURE_TENANT_ID` and selects `AZURE_SUBSCRIPTION` of the config:
   ```shell
   afon-cli.sh azure-login
   afon-cli.sh azure-create-admin [<app-name>]
   ```
   Read `APP_ID` and `APP_SP_ID` from the output, as above.

2. *Admin: give the app the credential of the deployer's login.*  Which issuer and subject depends on where the deploy runs:
   - *An Azure DevOps Services pipeline.*  Create the service connection (Azure Resource Manager, workload identity federation, manual, for the existing app
     registration), which shows its issuer (new connections use Microsoft Entra, the older Azure DevOps issuer is being retired) and subject.  Then:
     ```shell
     afon-cli.sh azure-identity-admin afon-deployer "<issuer of the service connection>" "sc://<organization>/<project>/<service connection name>" <APP_ID>
     ```
   - *Agents in a Kubernetes cluster* (self-hosted Azure Pipelines agents, eg the `afon-azure-agent` image).  The token is the projected service account token of the agent
     pod, so the issuer is that of the agent cluster:
     ```shell
     ISSUER=$(az aks show -g <agent cluster resource group> -n <agent cluster> --query oidcIssuerProfile.issuerUrl -o tsv)
     afon-cli.sh azure-identity-admin afon-agent "$ISSUER" "system:serviceaccount:<namespace>:<agent service account>" <APP_ID>
     ```
   - *A machine that is neither, for testing, eg the deployer's laptop.*  First, it needs no credential to deploy: log in as yourself with `afon-cli.sh azure-login`, and only
     the credential of the Polaris pod in step 6 is needed.  Only to try `azure-login-app` and the token path from a machine, give it an issuer of its own with
     `azure-issue-token`, a throwaway one for testing, as whoever has its key can act as the app.  Entra ID checks a token against two small files of the issuer, which hold
     only its public key and must be public over HTTPS, eg in an Azure Storage static website or GitHub Pages:
     ```shell
     afon-cli.sh azure-issue-token init https://<host>/<path> [<subject>]
     ```
     This creates the key and the files in `$AFON_HOME/azure-issue-token` (`AZURE_ISSUE_TOKEN_DIR`), says where to publish each of them, and prints the command for the admin,
     which trusts the issuer for the subject (`deployer-test` by default):
     ```shell
     afon-cli.sh azure-identity-admin afon-test-machine "https://<host>/<path>" "deployer-test" <APP_ID>
     ```
     Then step 4 gets a token of ten minutes for each login, signed with the key:
     ```shell
     export AZURE_FEDERATED_TOKEN_FILE=$(afon-cli.sh azure-issue-token test-token)
     ```
     Keep the key on that machine only, and delete the credential (`az ad app federated-credential delete`) and the directory when done.
   It is idempotent, a credential of the same issuer and subject is left as it is.  Azure DevOps Server does not issue these tokens as far as is known, so use the
   agent cluster there.

3. *Deployer: set the values from the admin.*  As in the table above, plus the flag, in `secret-azure.properties`:
   ```shell
   AZURE_TENANT_ID=<TENANT_ID>
   AZURE_APP_ID=<APP_ID>
   AZURE_SP_ID=<APP_SP_ID>
   AZURE_APP_SECRET=
   AZURE_WORKLOAD_IDENTITY=true
   ```
   and check them:
   ```shell
   afon-cli.sh config-validate -c azure
   ```

4. *Deployer: log in as the app.*  In a pipeline, run the following steps in an `AzureCLI@2` step that uses the service connection: `az` is then logged in as the app already, and
   no login command is needed.  Elsewhere, with a file that has the token (a JWT of the issuer and subject of step 2):
   ```shell
   export AZURE_FEDERATED_TOKEN_FILE=<file with the token>
   afon-cli.sh azure-login-app
   ```
   On an agent pod in AKS with workload identity the webhook sets `AZURE_FEDERATED_TOKEN_FILE`, and the kubelet refreshes the file; in an `AzureCLI@2` step, `$idToken` (`addSpnToEnvironment: true`)
   is the token.  A person logs in as themself with `afon-cli.sh azure-login`.

5. *Deployer: create the env and deploy the stack*, as in [§9](#9-create-the-env-and-check-the-storage) and the deploy of the stack:
   ```shell
   afon-cli.sh azure-create <stack-id>
   afon-cli.sh manage-stack setup <stack-id>
   ```
   The stack enables AKS workload identity on the cluster, creates the service account `polaris` in the `default` namespace, annotated with `AZURE_APP_ID`, and labels the
   Polaris pod `azure.workload.identity/use: "true"`.  Polaris is not given `AZURE_CLIENT_SECRET`, and gets no token until step 6.

6. *Give the app the credential of the cluster*, as soon as the cluster is up.  The deployer checks it, and prints the command for the admin if it is missing:
   ```shell
   afon-cli.sh azure-identity <stack-id>
   ```
   The admin then runs the `azure-identity-admin` command it prints, with the issuer of the cluster and the subject `system:serviceaccount:default:polaris`.
   If the deployer may change the app registration, `afon-cli.sh azure-identity true <stack-id>` does it, like `azure-create true` does with `azure-create-admin`.
   Until it takes effect, which can take a few minutes, Polaris fails to get a token (AADSTS70021: no matching federated identity record).  Repeat it when the
   cluster is rebuilt, as its issuer changes.

7. *Check.*  The pod has no secret, but has a token:
   ```shell
   kubectl exec deploy/<polaris> -- env | grep AZURE_     # AZURE_CLIENT_ID, AZURE_TENANT_ID, AZURE_FEDERATED_TOKEN_FILE, no AZURE_CLIENT_SECRET
   ```
   then run the Azure smoke test, which writes to the container through Polaris.

**Trying it on an env that uses the secret.**  The same app can hold both.  Skip steps 1 to 2, keep the IDs and the secret, run step 6, set `AZURE_WORKLOAD_IDENTITY=true`, plan and apply
the stack, and check as in step 7: the secret is then ignored, with a warning.  Set the flag back to `false` to return to the secret.

**Notes**
- `issue-cert.sh` with Let's Encrypt gives acme.sh the token of the deployer's `az` login for Azure DNS, so the deployer needs to write DNS records in the resource group, eg
  as its Contributor.
- The token of the login lasts minutes, so a long deploy can fail late with `AADSTS700024`: log in again with a fresh token.  `azure-login-oidc` does this for GitHub Actions;
  for Azure DevOps it is not supported yet.

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
