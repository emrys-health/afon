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

To create the cloud infrastructure, which includes storage buckets, IAM roles, service accounts, and DNS zones,
run the cloud create scripts:

```shell
afon-cli.sh <cloud>-create [as-admin] <stack-id>
```
where cloud is one of `{aws|azure|gcp}`.  On `azure` the boolean arg determines if you're running as admin.  If not, the script `azure/create-admin` must be run by an admin.   By default, `as-admin=false`.
On `aws` and `azure` it requires the id of the stack the env is created for, which decides what it creates, in any order with the boolean arg: on `azure`, `azure-polaris` creates the DNS zone of Polaris, `azure-keycloak` that of `OIDC_DOMAIN_NAME`, and `azure-full` those of Polaris and Jupyter, and `OIDC_DOMAIN_NAME` too unless `AZURE_KEYCLOAK_EXTERNAL=true`; `aws-full` and `azure-full` also create the bucket or container of Gitea, `AWS_GITEA_BUCKET` or `AZURE_GITEA_BUCKET`, which must be set, and any stack the one of Polaris' logs, `AWS_POLARIS_LOG_BUCKET` or `AZURE_POLARIS_LOG_BUCKET`, if set, eg `afon-cli.sh azure-create false azure-full` or `afon-cli.sh aws-create aws-full`.

## Destroy

To destroy the cloud infrastructure
```shell
afon-cli.sh <cloud>-destroy
```
where cloud is one of `{aws|azure|gcp}`.  On `azure` the boolean arg determines if you're running as admin.  If not, the script `azure/destroy-admin` must be run by an admin.  By default, `as-admin=false`.
