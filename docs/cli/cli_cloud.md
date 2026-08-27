# Cloud infrastructure 

## Authentication

**IMPORTANT:** Ensure you have done a login with the cloud cli before executing the create or destroy scripts.

```shell
$AFON_CLI_DIR/afon-cli-script.sh <cloud>/login.sh 
```
where cloud is one of `{aws|azure|gcp}`.

To show the account used by the cloud cli, once logged in:
```shell
$AFON_CLI_DIR/afon-cli-script.sh <cloud>/account.sh 
```
where cloud is one of `{aws|azure|gcp}`.

## Create 
To create the cloud infrastructure, which includes storage buckets, IAM roles, service accounts, and DNS zones,
run the cloud create scripts:

```shell
$AFON_CLI_DIR/afon-cli-script.sh <cloud>/create.sh 
```
where cloud is one of `{aws|azure|gcp}`.

## Destroy
To destroy the cloud infrastructure
```shell
$AFON_CLI_DIR/afon-cli-script.sh <cloud>/destroy.sh 
```
where cloud is one of `{aws|azure|gcp}`.

