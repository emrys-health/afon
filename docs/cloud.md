# Cloud infrastructure 

## Create 
To create the cloud infrastructure, which includes storage buckets, IAM roles, service accounts, and DNS zones,
run the cloud create scripts:

```shell
./afon.sh -a exec -t script -c <cloud> --exec-script create.sh 
```
where cloud is one of `{aws|azure|gcp}`.

## Destroy
To destry the cloud infrastructure
```shell
./afon.sh -a exec -t script -c <cloud> --exec-script destroy.sh 
```
where cloud is one of `{aws|azure|gcp}`.

