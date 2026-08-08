# Stacks

## Pre-requisites 

## Setup

See [setup](setup.md).

## Command API

The top-level `afon.sh` acts the command API for all the functionality:

```shell
./afon.sh -h
```

**NOTE:** In the commands, all JVM packages are intended relative to the base package `health.emrys.afon`. 
If you want, you may specify the full package or change the base package via the `--exec-package` option.

## Stack deployment

To deploy a stack, we use the ops.Deploy*Main classes, e.g.:

```shell
./afon.sh -a exec --exec-class ops.DeployAwsFullStackMain
./afon.sh -a exec --exec-class ops.DeployAzurePolarisMain
./afon.sh -a exec --exec-class ops.DeployGcpPolarisMain
```

The credentials in the cloud-specific env properties in [config dir](configuration.md) are used by the deployed Polaris instance.

**NOTE:** Currently, only AWS has the full stack.
Azure and GCP just host Polaris at the moment. There's no particular reason for this other than expediency.

### AWS full stack

The AWS full stack includes Polaris, Spark (via Spark Connect), and Jupyter (behind Guacamole with OAuth2).

```shell
./afon.sh -a exec --exec-class ops.DeployAwsFullStackMain
```
Steps executed:

1. `clean_aws` — destroy any existing AWS stack
2. `aws_spark` — deploy Polaris + Spark to AWS via CDKTF + OpenTofu
3. `wait_on_emryspolaris` — wait for DNS to propagate
4. `all_catalogs_in_emryspolaris` — configure all catalogs in Polaris
5. `aws_spark_talks_to_aws` — smoke test: Spark writes and reads via Polaris

### Deploy only Polaris to Azure

```shell
./afon.sh -a exec --exec-class ops.DeployAzurePolarisMain  
```
Steps: `clean_azure` → `azure_polaris` → `wait_on_emryspolarisazure` → `all_catalogs_in_emryspolarisazure`

### Deploy only Polaris to GCP

```shell
./afon.sh -a exec --exec-class ops.DeployGcpPolarisMain  
```
Steps: `clean_gcp` → `gcp_polaris` → `wait_on_emryspolarisgcp` → `all_catalogs_in_emryspolarisgcp`

### Deploy cross-cloud external catalogs (AWS + Azure)

To deploy AWS and Azure Polaris instances and configure AWS Polaris to federate to Azure Polaris for cross-cloud querying.

```shell
./afon.sh -a exec --exec-class ops.DeployExternalCatalogsMain  
```

Steps: `clean_aws` → `aws_spark` → `wait_on_emryspolaris` → `all_catalogs_in_emryspolaris` → `clean_azure` → `azure_polaris` → `wait_on_emryspolarisazure` → `emryspolarisazure_purge_azure` → `foreign_catalog_in_emryspolarisazure` → `external_azure_in_emryspolarisazure` → `aws_spark_talks_to_azure_external`

### Resume a failed stack deployment from a specific step

If a deployment fails partway through, you can resume from a specific step, e.g.:

```shell
./afon.sh -a exec --exec-class ops.DeployExternalCatalogsMain --exec-args "azure_polaris"
```

### The afon-cdk module

The afon-cdk module generates the Terraform plan using the CDKTF API to manage the cloud environment and delegates it to tofu to execute it. The plan is in `afon-ops/cdktf.out/stacks/{aws,azure,gcp}`.

Thereafter, you can run tofu apply or destroy commands via
```shell
./afon.sh -a tofu-{apply,destroy}
```

**NOTE:** The `ops.Deploy*Main` classes generate the plan and execute it without needing the `tofu-apply` execution.
You may re-run the `tofu-apply` if needed.

## Stack teardown 

The stack can be brought down using tofu, which will use the locally stored tfstate.
```shell
./afon.sh -a tofu-destroy
```

If tofu-destroy hangs on an instance of a managed type, rm manage type from tofu state and re-run destroy:
```shell
./afon.sh -a tofu-state --tofu-args "rm helm_release.prometheusRelease"
```

By default, assumes we are targeting `aws` cloud.   A different cloud can be set via the `-c {aws,azure,gcp}` option.

## Recovery

If things go astray, you may leave the stack in an unstable state.
Use the cleanup helpers to bring your environment to a clean and stable state:

```shell
./afon.sh -a exec --exec-class cdk.aws.AwsCleanupHelper --exec-args aws-cleanup.sh
./afon.sh -a exec --exec-class cdk.azure.AzureCleanupHelper --exec-args azure-cleanup.sh
./afon.sh -a exec --exec-class cdk.gcp.GcpCleanupHelper --exec-args gcp-cleanup.sh
```
The cleanup helpers output shell scripts that you can run.
You may need to run them multiple times for the VPC to disappear from your cloud.

### AWS

To check the AWS VPC has been destroyed:

```shell
aws ec2 describe-vpcs --query "Vpcs[?Tags[?Key=='Name' && Value=='cdktf-eks-vpc-spark-cluster']].VpcId"   --output text
```

And if all else fails, login to the [AWS VPC Console](https://eu-west-2.console.aws.amazon.com/vpcconsole/home?region=eu-west-2#vpcs:)

### Azure 

When Azure resource group is stuck:

```shell
az group delete --name afon_resource_group_aks --yes --no-wait
```

When Azure Terraform import is needed:

```shell
tofu import azurestack_resource_group.XXX \
  /subscriptions/YOUR_SUB/resourceGroups/your_resource_group_aks
```

If the import fails with `Invalid provider configuration`, temporarily comment out the offending provider lines in the generated JSON and retry.


## GitOps considerations

Currently, the generated Terraform can contain confidential details (secrets, credentials). 

Before adopting a GitOps workflow where Terraform state is checked into Git:

1. **Separate secrets** — move all sensitive values into Kubernetes secrets or a secrets manager (Vault, AWS Secrets Manager, etc.) rather than embedding them in CDKTF output
2. **Use remote state** — store Terraform state in a remote backend (S3 + DynamoDB for AWS, Azure Storage for Azure, GCS for GCP) with encryption
3. **CI/CD pipeline** — run `tofu plan` on PRs and `tofu apply` on merge to main
4. **State locking** — ensure concurrent runs are prevented via state locking

This is noted as a future improvement in the project.

## Summary of deployment commands

```shell
# AWS full stack (Polaris + Spark + Jupyter)
./afon.sh -a exec --exec-class ops.DeployAwsFullStackMain 

# Azure Polaris (standalone)
./afon.sh -a exec --exec-class ops.DeployAzurePolarisMain

# GCP Polaris (standalone)
./afon.sh -a exec --exec-class ops.DeployGcpPolarisMain 

# Cross-cloud external catalogs (AWS + Azure federation)
./afon.sh -a exec --exec-class ops.DeployExternalCatalogsMain 

# Snowflake reading cross-cloud data
./afon.sh -a exec --exec-class ops.DeploySnowflakeReadingGcpViaAwsMain 
./afon.sh -a exec --exec-class ops.DeploySnowflakeReadingAzureViaAwsMain 

# Snowflake writing to Azure
./afon.sh -a exec --exec-class ops.DeploySnowflakeWritingAzureViaAwsMain 
```

