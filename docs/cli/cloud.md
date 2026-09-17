# Cloud infrastructure 

**NOTE:** every `afon-cli-scripts.sh <path>/<script>.sh` command below also has a shorthand `afon-cli.sh` command,
so you don't need to switch between the two entry points — see [Shorthand commands](cli.md#shorthand-commands).
Eg `afon-cli-scripts.sh aws/login.sh` is the same as `afon-cli.sh aws-login`; both accept the same `--home`, `--cdktf`,
`--cloud`, `--config-env`, `--cdktf-env`, `--config-zip`, `--env` flags (or the equivalent `AFON_*` env var).

## Authentication

**IMPORTANT:** Ensure you have done a login with the cloud cli before executing the create or destroy scripts.

```shell
$AFON_CLI_DIR/afon-cli-scripts.sh <cloud>/login.sh 
```
where cloud is one of `{aws|azure|gcp}`.

To show the account used by the cloud cli, once logged in:
```shell
$AFON_CLI_DIR/afon-cli-scripts.sh <cloud>/account.sh 
```
where cloud is one of `{aws|azure|gcp}`.

## Create 

To create the cloud infrastructure, which includes storage buckets, IAM roles, service accounts, and DNS zones,
run the cloud create scripts:

```shell
$AFON_CLI_DIR/afon-cli-scripts.sh <cloud>/create.sh 
```
where cloud is one of `{aws|azure|gcp}`.

## Destroy

To destroy the cloud infrastructure
```shell
$AFON_CLI_DIR/afon-cli-scripts.sh <cloud>/destroy.sh 
```
where cloud is one of `{aws|azure|gcp}`.

