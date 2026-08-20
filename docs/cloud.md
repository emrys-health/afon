# Cloud infrastructure 

## Authentication

**IMPORTANT:** Ensure you have done a login or authentication with the cloud cli before executing the create or destroy scripts.

```shell
aws login help
az login --help
gcloud auth login --help
```

To show that the login has been successful and the account used for the login:
```shell
aws sts get-caller-identity --query "Arn"
az account show --query "user.name" -o tsv
gcloud auth list
```

## Create 
To create the cloud infrastructure, which includes storage buckets, IAM roles, service accounts, and DNS zones,
run the cloud create scripts:

```shell
./afon.sh -a exec -t script --exec-script <cloud>/create.sh 
```
where cloud is one of `{aws|azure|gcp}`.

## Destroy
To destroy the cloud infrastructure
```shell
./afon.sh -a exec -t script --exec-script <cloud>/destroy.sh 
```
where cloud is one of `{aws|azure|gcp}`.

