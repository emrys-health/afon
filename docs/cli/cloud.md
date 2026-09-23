# Cloud infrastructure 

**NOTE:** every command below is also a script command on `afon-cli.sh`, for `afon-cli-scripts.sh <dir>/<name>.sh` 
— see [Script commands](cli.md#script-commands).
Eg `afon-cli.sh aws-login` is the script command for `afon-cli-scripts.sh aws/login.sh`; 
both accept the same `--home`, `--cdktf`, `--cloud`, `--config-env`, `--cdktf-env`, `--env` flags 
(or the equivalent `AFON_*` env var).

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
afon-cli.sh <cloud>-create [as-admin]
```
where cloud is one of `{aws|azure|gcp}`.  On `azure` the boolean arg determines if you're running as admin.  If not, the script `azure/create-admin` must be run by an admin.   By default, `as-admin=false`.

## Destroy

To destroy the cloud infrastructure
```shell
afon-cli.sh <cloud>-destroy
```
where cloud is one of `{aws|azure|gcp}`.  On `azure` the boolean arg determines if you're running as admin.  If not, the script `azure/destroy-admin` must be run by an admin.  By default, `as-admin=false`.

