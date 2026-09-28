# Stacks

## Pre-requisites

### Afon entry point

You can choose one of the Afon [entry points](afon_entry_points.md).

### Setup Local Env

You need to have set up you [local env](setup_local.md).

## Stack deployment

To deploy a stack, using the CLI command, we run the corresponding command:

```shell
afon-cli.sh deploy-aws-full-stack
afon-cli.sh deploy-azure-polaris-stack
afon-cli.sh deploy-gcp-polaris-stack
```

**NOTE:** To run the `delete-*-stack`, `deploy-*-stack`, `setup-*-stack` commands you need to be
logged in with the corresponding [cloud cli](cloud.md).

**IMPORTANT:** the `*-stack` commands use `$AFON_CDKTF` as their `tofu` state dir, so if you deploy or setup a stack (ie
deploy a stack plus configure the Polaris catalogs) you must take care not move your `$AFON_CDKTF` until the teardown of
the stack, other `tofu` will not know the information to destroy the stack.

In the command names, we use the convention that `deploy` just deploys the stack (waiting for DNS to propagate) and `setup` does the deployment and configures the Polaris catalogs.

### Validate a stack's config

To validate a stack's config without deploying it, eg that the config dir has all the properties the stack needs,
you can synthesise just the cdktf plan of a stack, which needs no cloud login:
```shell
afon-cli.sh validate-stack <stack>  # eg azure-polaris or aws-full, run without <stack> to list the stacks
```
This fails fast, eg with `Property X not found`, if the config is incomplete.
It overwrites the plan in `$AFON_CDKTF/stacks/<cloud>` (ie `cdk.tf.json` and `assets`), but not the state file
`$AFON_CDKTF/terraform.<cloud>.tfstate`, as it doesn't run tofu.  To leave the plan of a running or previous deploy
untouched, synthesise into another cdktf dir, eg `afon-cli.sh --cdktf /tmp/cdktf validate-stack azure-polaris`.

### Setup AWS full stack

The AWS full stack includes Polaris, Spark (via Spark Connect), and Jupyter (behind Guacamole with OAuth2).

```shell
afon-cli.sh setup-aws-full-stack
```
Steps executed:

1. `delete_aws_stack` — delete any existing AWS stack
2. `deploy_aws_full_stack` — deploy AWS full stack
3. `wait_on_emryspolaris` — wait for DNS to propagate
4. `create_all_catalogs_for_cloud_aws` — create all catalogs in AWS Polaris
5. `aws_spark_talks_to_cloud_aws` — smoke test: Spark writes and reads via Polaris

### Access Polaris and Jupyter

For development purposes, we've registered for `aws` domains `emryspolaris.click` and `emrysjupyter.click`.

Once the AWS full stack is deployed, we can access Polaris and Jupyter via the URLs:
- `https://emryspolaris.click`
- `https://emrysjupyter.click/guacamole`

### Deploy AWS Keycloak only

The AWS Keycloak stack deploys only the Keycloak server, ie the EKS cluster with Keycloak behind its HTTPS ingress
at `OIDC_DOMAIN_NAME`, without Polaris, eg to serve as a standalone OIDC provider.

```shell
afon-cli.sh deploy-aws-keycloak-stack
```
Steps: `delete_aws_stack` → `deploy_aws_keycloak_stack` → `wait_on_emrysoauth` → `deploy_complete_for_cloud_aws`

It requires the Keycloak properties (`OIDC_DOMAIN_NAME`, `KEYCLOAK_*`, `AWS_KEYCLOAK_DNS_ZONE`, `AWS_KEYCLOAK_CERT_ARN`)
and the AWS cluster properties, but none of the `AWS_POLARIS_*` properties.
Once deployed, you can check the OIDC provider is ready with `afon-cli.sh check-oidc`.

### Setup AWS Keycloak only

To deploy the AWS Keycloak stack and configure it with the realm, the `POLARIS_ID` client and the users (and
their confidential clients), plus the Jupyter browser client if `AWS_JUPYTER_DOMAIN_NAME` is set:

```shell
afon-cli.sh setup-aws-keycloak-stack
```
Steps: `delete_aws_stack` → `deploy_aws_keycloak_stack` → `wait_on_emrysoauth` → `configure_oidc_users_for_cloud_aws` → `setup_complete_for_cloud_aws`

### Setup Azure Polaris only

```shell
afon-cli.sh setup-azure-polaris-stack
```
Steps: `delete_azure_stack` → `deploy_azure_polaris_stack` → `wait_on_emryspolarisazure_then_wait_on_emrysoauth` → `create_all_catalogs_for_cloud_azure`

### Setup GCP Polaris only

```shell
afon-cli.sh setup-gcp-polaris-stack
```
Steps: `delete_gcp_stack` → `deploy_gcp_polaris_stack` → `wait_on_emryspolarisgcp_then_wait_on_emrysoauth` → `create_all_catalogs_for_cloud_gcp`

### Setup cross-cloud external catalogs (AWS + Azure)

To deploy AWS and Azure Polaris instances and configure AWS Polaris to federate to Azure Polaris for cross-cloud querying.

```shell
afon-cli.sh setup-aws-full-stack-with-azure-polaris-hosting-external-catalog
```

Steps: `delete_aws_stack → deploy_aws_full_stack → wait_on_emryspolaris_then_wait_on_emrysoauth → create_all_catalogs_for_cloud_aws → aws_spark_talks_to_catalog_aws → delete_azure_stack → deploy_azure_polaris_stack → wait_on_emryspolarisazure_then_wait_on_emrysoauth → delete_table_azure.namespaceph.test_table_for_cloud_azure → create_external_catalog_azure_for_cloud_azure → aws_spark_talks_to_catalog_azure_external`

### Resume a failed stack deployment from a specific step

If a deployment fails partway through, you can resume from a specific step, e.g.:

```shell
afon-cli.sh setup-aws-full-stack-with-azure-polaris-hosting-external-catalog "deploy_azure_polaris_stack"
```

### Teardown a deployed stack

The stack teardown can be done via replacing `deploy` with `delete` in the command, eg:
```shell
afon-cli.sh delete-aws-stack
afon-cli.sh delete-azure-stack
afon-cli.sh delete-gcp-stack
```

The `delete` commands are equivalent to running `tofu destroy` on the appropriate `$AFON_CDKTF` dir.

### The Afon cdktf dir

The afon-cdk module generates the Terraform plan using the cdktf API to manage the cloud environment and delegates it to tofu to execute it.   The afon-cdk module writes to the cdktf dir, which can be set either via option `--cdktf` in `afon.sh` or via env variable `AFON_CDKTF`.  It defaults to `$AFON_HOME/cdktf`.

The cdktf plan is written `$AFON_CDKTF/stacks/<cloud>` where `<cloud>={aws,azure,gcp}`.
The cdktf dir also holds the Terraform state files: `$AFON_CDKTF/terraform.<cloud>.tfstate` where `<cloud>={aws,azure,gcp}`.

You can run tofu apply or destroy commands via
```shell
afon-cli.sh tofu-{apply,destroy}
```
**NOTE:** The `ops.Deploy*Main` classes generate the plan and execute it without needing the `tofu-apply` execution.
You may re-run the `tofu-apply` if needed.

To validate a stack's config without deploying it, eg that the config dir has all the properties the stack needs,
you can synthesise just the cdktf plan of a stack, which needs no cloud login:
```shell
afon-cli.sh validate-stack <stack>  # eg azure-polaris or aws-full, run without <stack> to list the stacks
```
This fails fast, eg with `Property X not found`, if the config is incomplete.
It overwrites the plan in `$AFON_CDKTF/stacks/<cloud>` (ie `cdk.tf.json` and `assets`), but not the state file
`$AFON_CDKTF/terraform.<cloud>.tfstate`, as it doesn't run tofu.  To leave the plan of a running or previous deploy
untouched, synthesise into another cdktf dir, eg `--cdktf /tmp/cdktf`.

#### Command cdktf-switch

To support multiple config envs, say a `dev` and a `stg` env, you can use the `cdktf-switch` action:
```shell
afon-cli.sh cdktf-switch -e {dev|stg} # cdktf symlink for <env> 
```
This will create or move the symlink `$AFON_CDKTF` pointing to `$AFON_CDKTF-<env>`.

**NOTE**: If you're using multiple config envs, the cdktf dir must be a symlink and not a dir.  
Create cdktf env dirs `$AFON_CDKTF-<env>` and ensure there is no cdktf dir.   
The cdktf-switch action will remove any existing symlink.

#### Command cdktf-status

To show the status of the current cdktf dir, you can use the `cdktf-status` action:
```shell
afon-cli.sh cdktf-status
```
If found, it will output the content of the file `manifest.json` in the cdktf dir, which can be used to
provide information to the user of the cdktf content.  It will also list the files in the cdktf dir.

## Stack teardown 

The stack can be brought down using tofu, which will use the locally stored tfstate.
```shell
afon-cli.sh tofu-destroy
```

## Recovery

If things go astray, you may leave the stack in an unstable state.
Use the cleanup helpers to bring your environment to a clean and stable state:

```shell
afon-cli.sh generate-cleanup-script <cloud>
```
You can run the generated cleanup scripts.
You may need to run them multiple times for the VPC to disappear from your cloud.

### AWS

To check the AWS VPC has been destroyed:

```shell
afon-cli.sh aws-vpcs  # same as: afon-cli-scripts.sh aws/vpcs.sh
```

And if all else fails, login to the [AWS VPC Console](https://eu-west-2.console.aws.amazon.com/vpcconsole/home?region=eu-west-2#vpcs:)

### Azure 

When Azure resource group is stuck:

```shell
az group delete --name afon_resource_group_aks --yes --no-wait
```

## Summary of deployment commands

```shell
# AWS full stack (Keycloak + Polaris + Spark + Jupyter)
afon-cli.sh deploy-aws-full-stack

# AWS Keycloak (standalone)
afon-cli.sh deploy-aws-keycloak-stack

# Azure Polaris (standalone)
afon-cli.sh deploy-azure-polaris-stack

# GCP Polaris (standalone)
afon-cli.sh deploy-gcp-polaris-stack

# Cross-cloud external catalogs (AWS + Azure federation)
afon-cli.sh setup-aws-full-stack-with-polaris-hosting-external-catalog

# Snowflake reading cross-cloud data
afon-cli.sh deploy-snowflake-reading-gcp-via-aws
afon-cli.sh deploy-snowflake-reading-azure-via-aws

# Snowflake writing to Azure
afon-cli.sh deploy-snowflake-writing-azure-via-aws
```

## Deployment checklist

- [ ] **Domain names** purchased via your domain registrar, one per cloud (e.g. `polaris.yourdomain.com`)
- [ ] **DNS zones** created in each target cloud and nameservers configured at your domain registrar
- [ ] **SSL certificates** requested and validated (ACM for AWS, Let's Encrypt for others)
- [ ] **External Polaris database** provisioned for Polaris metadata storage
- [ ] **OIDC provider** configured OIDC provider for Jupyter access
- [ ] **Cloud IAM roles** created via `scripts/<cloud>/create.sh`
- [ ] **Storage buckets** created (primary, external, and forbidden for each cloud)
- [ ] **Configuration** properties and users values populated in `config` dir
- [ ] **Kubernetes cluster** creation permissions confirmed
