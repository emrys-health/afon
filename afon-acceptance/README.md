# Automated acceptance tests

These tests are run against cloud cluster, using the afon-ops module.

There is a once only preparation for them. 
Scripts to set AWS, GCP and Azure live at:

```shell
../scripts/aws/create.sh  
../scripts/azure/create.sh  
../scripts/gcp/create.sh
```

and to tear down your cloud:

```shell
./scripts/aws/destroy.sh  
./scripts/azure/destroy.sh  
./scripts/gcp/destroy.sh
```