# Cloud infrastructure

## Authentication

**IMPORTANT:** Ensure you have done a login with the cloud cli before executing the create or destroy scripts.

```shell
afon-cli.sh <cloud>-login
```
where cloud is one of `{aws|azure|gcp}`.

To show the account used by the cloud cli, once logged in:
```shell
afon-cli.sh <cloud>-account 
```
where cloud is one of `{aws|azure|gcp}`.  On `azure` it also sets the configured subscription to your account.

## Create

To create the cloud infrastructure, which includes storage buckets, IAM roles and service accounts,
run the cloud create scripts:

```shell
afon-cli.sh <cloud>-create [as-admin] <stack-id>
```
where cloud is one of `{aws|azure|gcp}`.  On `azure` the boolean arg determines if you're running as admin.  If not, the script `azure/create-admin` must be run by an admin.   By default, `as-admin=false`.
On `aws` and `azure` it requires the stack the env is created for, which decides what it creates, in any order with the boolean arg. Give either its id, or its role in the cloud, ie `identity`, `catalog` or `tre`, which `stacks.yaml` maps to a stack (see [Define your own stacks](stacks.md#define-your-own-stacks)), so that it works whatever the ids are. The stack is looked up in `stacks.yaml` and the script is given what it needs as env vars, whichever way it is run: by `afon-cli.sh`, whose launcher does it before the script starts, or by `afon.sh`, which sources the script in bash with no JVM, so the script asks the ops command `stack-needs` (a Maven run, so a few seconds slower) for them. They are: `AFON_STACK_ID`, `AFON_STACK_ROLE`, `AFON_STACK_DOMAINS` (the properties of the domain names its components hold) and `AFON_STACK_COMPONENTS` (the names of its components), derived from the stack as `stacks.yaml` and its components define it, for the config in use. So what is created follows the stack, not its id (the DNS zones of the domains the stack holds, eg on `azure` those of Polaris, Jupyter, and `OIDC_DOMAIN_NAME` unless `AZURE_KEYCLOAK_EXTERNAL=true`, are not created here but by `dns-create-zones <cloud> <stack>`, see [DNS configuration](dns_config.md)): the bucket or container of Gitea, `AWS_GITEA_BUCKET` or `AZURE_GITEA_BUCKET`, which must be set, if the stack deploys Git; and for any stack the one of Polaris' logs, `AWS_POLARIS_LOG_BUCKET` or `AZURE_POLARIS_LOG_BUCKET`, if set. Eg `afon-cli.sh azure-create false tre`, `./afon.sh -a aws-create tre` or `afon-cli.sh aws-create aws-full`. Run directly, with neither, the script asks the launcher in its own tree (`afon-cli.sh` of the distribution, or `afon.sh` of the source tree), else `$AFON_CLI_DIR/afon-cli.sh`, and stops, saying so, if there is none. It does not prefer `$AFON_CLI_DIR` to its own tree, as that may be an installed distribution that does not have the command yet.

## Destroy

To destroy the cloud infrastructure
```shell
afon-cli.sh <cloud>-destroy
```
where cloud is one of `{aws|azure|gcp}`.  On `azure` the boolean arg determines if you're running as admin.  If not, the script `azure/destroy-admin` must be run by an admin.  By default, `as-admin=false`.
