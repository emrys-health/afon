# Stacks

## Pre-requisites 

### Setup Local Env

See [setup local env](setup_local.md).

## afon.sh commands

The script `afon.sh` at the top-level of the source tree acts as a front-end for all the functionality, including the build:

```shell
./afon.sh -h
```

**NOTE:** In the following, all JVM packages are intended relative to the base package `health.emrys.afon`. 
If you want, you may specify the full package or change the base package via the `--exec-package` option.

## Stack deployment

To deploy a stack, we execute the `ops.Deploy*Main` classes, e.g.:

```shell
./afon.sh -a exec --exec-class ops.DeployAwsFullStackMain
./afon.sh -a exec --exec-class ops.DeployAzurePolarisStackMain
./afon.sh -a exec --exec-class ops.DeployGcpPolarisStackMain
```

The credentials in the cloud-specific env properties in [config dir](configuration.md) are used by the deployed Polaris instance.

**NOTE:** Currently, only AWS has the full stack.
Azure and GCP just host Polaris at the moment. There's no particular reason for this other than expediency.

### Setup AWS full stack

The setup AWS full stack includes Polaris, Spark (via Spark Connect), and Jupyter (behind Guacamole with OAuth2).

```shell
./afon.sh -a exec --exec-class ops.SetupAwsFullStackMain
```
Steps executed:

- `clean_aws` — destroy any existing AWS stack
- `aws_full` — deploy AWS full stack
- `wait_on_emryspolaris_then_wait_on_emrysoauth` — wait for DNS to propagate
- `create_all_catalogs_for_emryspolaris` — configure all catalogs in Polaris
- `emryspolaris_spark_talks_to_aws` — smoke test: Spark writes and reads via Polaris

### Setup Azure Polaris only

```shell
./afon.sh -a exec --exec-class ops.SetupAzurePolarisStackMain  
```
Steps: `clean_azure → azure_polaris → wait_on_emryspolarisazure_then_wait_on_emrysoauth → create_all_catalogs_for_emryspolarisazure`

### Setup GCP Polaris only

```shell
./afon.sh -a exec --exec-class ops.SetupGcpPolarisStackMain  
```
Steps: `clean_gcp → gcp_polaris → wait_on_emryspolarisgcp_then_wait_on_emrysoauth → create_all_catalogs_for_emryspolarisgcp`

### Setup cross-cloud external catalogs (AWS + Azure)

To deploy AWS and Azure Polaris instances and configure AWS Polaris to federate to Azure Polaris for cross-cloud querying.

```shell
./afon.sh -a exec --exec-class ops.SetupAwsFullStackWithAzurePolarisHostingExternalCatalogMain  
```

Steps:
`clean_aws → aws_full → wait_on_emryspolaris_then_wait_on_emrysoauth → create_all_catalogs_for_emryspolaris → emryspolaris_spark_talks_to_aws → clean_azure → azure_polaris → wait_on_emryspolarisazure_then_wait_on_emrysoauth → azure_delete_table_namespaceph.test_table_for_emryspolarisazure → azure_create_external_catalog_for_emryspolarisazure → emryspolaris_spark_talks_to_azure_external`

### Resume a failed stack deployment from a specific step

If a deployment fails partway through, you can resume from a specific step, e.g.:

```shell
./afon.sh -a exec --exec-class ops.DeployAwsFullStackWithAzurePolarisMain --exec-args "azure_polaris"
```

### Deploy or delete the AWS stack from GitHub

The [Stack AWS (Linux)](../.github/workflows/aws-stack-linux.yml) workflow runs `afon.sh`'s
`stack-deploy` or `stack-delete` for AWS on the self-hosted Linux runner — ie
`ops.DeployAwsFullStackMain` or `ops.DeleteAwsFullStackMain`.

Run it from the Actions tab: it is `workflow_dispatch` only, with an `action` dropdown
(`deploy`/`delete`), a `from_step` to resume from (deploy only — blank runs every step, starting
with the teardown; ignored by delete, which has a single step) and a `confirm` input that must
match the chosen action. `deploy` tears the running stack down before rebuilding it; `delete` tears
it down and leaves it down — a later `deploy` run is what brings it back.

Both actions run on the *same* self-hosted runner (see the `runs-on` note in the workflow), because
both need the tofu state described below — `delete` just as much as `deploy`, since that state is
the only record of what there is to tear down.

Pre-requisites, on top of what the acceptance workflow needs:

- **Runner tooling**: the `aws` CLI, `tofu` and `kubectl` on the runner's PATH. Install them with
  `ansible-playbook runner-setup.yml -e install_cloud_tools=true` (see [runners](../scripts/github/runners.md)).
- **Config secrets**: the same base64 secrets the acceptance workflow uses, published with
  `scripts/github/secrets.sh push`.
- **AWS admin credentials**: the repository secrets `AWS_DEPLOY_ACCESS_KEY_ID` and
  `AWS_DEPLOY_SECRET_ACCESS_KEY`. These are the user admin credentials tofu and the CLI run as,
  **NOTE** the Polaris service account credentials in `secret-aws.properties` (see
  [configuration](configuration.md)). Leave them unset to use the credentials the runner already
  has, from an EC2 instance profile or its `~/.aws/credentials`.
- **Tofu state**: kept on the runner in `$AFON_CDKTF/<owner>/<repo>/aws`. 

**NOTE**: the `$AFON_CDKTF` points to `/data/afon/cdktf` and lives outside the GitHub workspace,
which is wiped out at each checkout.   On the runner, the `$AFON_HOME` points to the workspace.   

The region comes from `AWS_REGION` in `afon-aws.properties`, and the run's log is uploaded as the
`afon-stack-aws-logs-<run id>` artifact.

### The Afon cdktf dir

The afon-cdk module generates the Terraform plan using the cdktf API to manage the cloud environment and delegates it to tofu to execute it.   The afon-cdk module writes to the cdktf dir, which can be set either via option `--cdktf` in `afon.sh` or via env variable `AFON_CDKTF`.  It defaults to `$AFON_HOME/cdktf`.

The cdktf plan is written `$AFON_CDKTF/stacks/<cloud>` where `<cloud>={aws,azure,gcp}`.

Thereafter, you can run tofu apply or destroy commands via
```shell
./afon.sh -a tofu-{apply,destroy}
```

**NOTE:** The `ops.Deploy*Main` classes generate the plan and execute it without needing the `tofu-apply` execution.
You may re-run the `tofu-apply` if needed.

The cdktf dir also holds the Terraform state files: `$AFON_CDKTF/terraform.<cloud>.tfstate` where `<cloud>={aws,azure,gcp}`.

#### Command cdktf-switch

To support multiple config envs, say a `dev` and a `stg` env, you can use the `cdktf-switch` action:
```shell
./afon.sh -a cdktf-switch --cdktf-env {dev|stg} # cdktf symlink for <env> 
```
This will create or move the symlink `$AFON_CDKTF` pointing to `$AFON_CDKTF-<env>`.

**NOTE**: If you're using multiple config envs, the cdktf dir must be a symlink and not a dir.  
Create cdktf env dirs `$AFON_CDKTF-<env>` and ensure there is no cdktf dir.   
The cdktf-switch action will remove any existing symlink.

#### Command cdktf-status

To show the status of the current cdktf dir, you can use the `cdktf-status` action:
```shell
./afon.sh -a cdktf-status
```
If found, it will output the content of the file `manifest.json` in the cdktf dir, which can be used to
provide information to the user of the cdktf content.  It will also list the files in the cdktf dir.

## Stack teardown 

The stack can be brought down using tofu, which will use the locally stored tfstate.
```shell
./afon.sh -a tofu-destroy
```

If tofu-destroy hangs on an instance of a managed type, rm manage type from tofu state and re-run destroy:
```shell
./afon.sh -a tofu-state --tofu-args "rm helm_release.prometheusRelease" --tofu-cloud <cloud>
```
where `<cloud>={aws,azure,gcp}`, defaulting to `aws` cloud.   

**NOTE:** The cloud specification option for `tofu` is `--tofu-cloud` and not `-c|--cloud`.

## Recovery

If things go astray, you may leave the stack in an unstable state.
Use the cleanup helpers to bring your environment to a clean and stable state:

```shell
./afon.sh -a exec --exec-class ops.GenerateCleanupScriptMain --exec-args <cloud>
```
You can run the generated cleanup scripts.
You may need to run them multiple times for the VPC to disappear from your cloud.

### AWS

To check the AWS VPC has been destroyed:

```shell
./afon.sh -a stack-status
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
./afon.sh -a exec --exec-class ops.DeployAzurePolarisStackMain

# GCP Polaris (standalone)
./afon.sh -a exec --exec-class ops.DeployGcpPolarisStackMain 

# Cross-cloud external catalogs (AWS + Azure federation)
./afon.sh -a exec --exec-class ops.DeployAwsFullStackWithAzurePolarisMain 

# Snowflake reading cross-cloud data
./afon.sh -a exec --exec-class ops.DeploySnowflakeReadingGcpViaAwsMain 
./afon.sh -a exec --exec-class ops.DeploySnowflakeReadingAzureViaAwsMain 

# Snowflake writing to Azure
./afon.sh -a exec --exec-class ops.DeploySnowflakeWritingAzureViaAwsMain 
```

