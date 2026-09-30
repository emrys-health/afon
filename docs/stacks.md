# Stacks

## Pre-requisites

### Afon entry point

You can choose one of the Afon [entry points](afon_entry_points.md).

### Setup Local Env

You need to have set up you [local env](setup_local.md).

## Stack deployment

To deploy a stack, using the CLI command, we run [manage-stack](#manage-a-stack) with the `deploy` action, eg:

```shell
afon-cli.sh manage-stack deploy aws-full
afon-cli.sh manage-stack deploy azure-polaris
afon-cli.sh manage-stack deploy gcp-polaris
```

**NOTE:** To run `manage-stack` you need to be logged in with the corresponding [cloud cli](cloud.md).

**IMPORTANT:** `manage-stack` uses `$AFON_CDKTF` as its `tofu` state dir, so if you deploy or setup a stack (ie
deploy a stack plus configure the Polaris catalogs) you must take care not move your `$AFON_CDKTF` until the teardown of
the stack, other `tofu` will not know the information to destroy the stack.

Of the actions, `deploy` just deploys the stack (waiting for DNS to propagate) and `setup` does the deployment and configures the Polaris catalogs.

### Manage a stack

A single command manages any stack, given an action and a stack id:
```shell
afon-cli.sh manage-stack <action> <stack-id> [<step>] [--replace]  # run without args to list the stack ids and their actions
```
where:
- `<action>` is one of `delete`, `deploy` or `setup`;
- `<stack-id>` is `<cloud>-<stack-type>`, eg `aws-keycloak`, named as `validate-stack` lists the stacks.
  Only the stacks that can be deployed or set up are listed: `aws-full`, `aws-keycloak`, `aws-polaris`,
  `azure-polaris` and `gcp-polaris`, and, to delete every stack of a cloud, `<cloud>-all`, eg `aws-all`;
- `<step>`, if given, is the step from which to resume a failed run (see
  [Resume a failed stack deployment](#resume-a-failed-stack-deployment-from-a-specific-step));
- `--replace`, if given, lets `deploy` and `setup` delete the other stacks conflicting with the stack, see
  [stacks side by side](#stacks-side-by-side).  With `afon.sh`, pass it after `--`, eg
  `./afon.sh -a manage-stack -- deploy aws-full --replace`.

For example:
```shell
afon-cli.sh manage-stack setup aws-keycloak
afon-cli.sh manage-stack deploy aws-full "wait_on_emryspolaris_then_wait_on_emrysoauth"
afon-cli.sh manage-stack delete aws-full
```
With `afon.sh`, pass the same args positionally, eg `./afon.sh -a manage-stack setup aws-keycloak`.

The previous per-stack commands, `deploy-*-stack`, `setup-*-stack` and `delete-*-stack`, still work and run the same
steps, eg `setup-aws-keycloak-stack` is the same as `manage-stack setup aws-keycloak`, but they will be removed.

### Stacks side by side

Each stack has its own `tofu` state, so stacks run side by side, unless they conflict, ie they hold a common exclusive
resource: the DNS record of a domain name, eg `OIDC_DOMAIN_NAME`, or the pinned egress IP `AWS_EGRESS_IP`, which only
one running stack can hold.  So `deploy` and `setup` first delete the stacks deployed in the cloud that conflict with
the stack, including the stack itself.  They only delete the stack itself, if deployed, unless given `--replace`:
if any other stack conflicts, they fail before deleting any, saying what the other stack already deploys, eg
```
Cannot deploy aws-polaris, as stack aws-full is running and already deploys Polaris at emryspolaris.click (AWS_POLARIS_DOMAIN_NAME).
To deploy aws-polaris, delete it first:
    afon-cli.sh manage-stack delete aws-full
or rerun with --replace to delete it as part of this run.
```
The pinned egress IP `AWS_EGRESS_IP` is only held, and so only conflicts, when it is set.
The [AWS stack workflow](github_workflows.md#stack-aws-workflow) always runs with `--replace`.

`delete` deletes just the stack, while `delete <cloud>-all`, eg `delete aws-all`, deletes every stack deployed in the
cloud.  The legacy stack of a cloud, deployed before each stack had its own `tofu` state (see
[the Afon cdktf dir](#the-afon-cdktf-dir)), may be any stack, so it conflicts with every stack of the cloud, and
`delete` of any stack of the cloud deletes it too.  Likewise, a stack not listed above, eg one deployed from
`validate-stack aws-jupyter` and `tofu-apply`, conflicts with every stack of the cloud.

| Stack | Holds | Runs side by side with |
|---|---|---|
| `aws-keycloak` | `OIDC_DOMAIN_NAME` | `aws-polaris` |
| `aws-polaris` | `AWS_POLARIS_DOMAIN_NAME`, `AWS_EGRESS_IP` | `aws-keycloak` |
| `aws-full` | `OIDC_DOMAIN_NAME`, `AWS_POLARIS_DOMAIN_NAME`, `AWS_JUPYTER_DOMAIN_NAME`, `AWS_EGRESS_IP` | none |
| `aws-full`, with `AWS_KEYCLOAK_EXTERNAL=true` | `AWS_POLARIS_DOMAIN_NAME`, `AWS_JUPYTER_DOMAIN_NAME`, `AWS_EGRESS_IP` | `aws-keycloak` |

The Polaris stacks, `aws-polaris`, `azure-polaris` and `gcp-polaris`, deploy no KeyCloak of their own, but use the
KeyCloak server at `OIDC_DOMAIN_NAME`, which `aws-keycloak` deploys, so deploy (or set up) `aws-keycloak` first, eg:
```shell
afon-cli.sh manage-stack setup aws-keycloak
afon-cli.sh manage-stack setup aws-polaris
```
Their `deploy` and `setup` check first that KeyCloak answers at `OIDC_DOMAIN_NAME`, and fail fast if it doesn't.

The `aws-full` stack deploys its own KeyCloak, so it conflicts with both `aws-keycloak` and `aws-polaris`, unless
`AWS_KEYCLOAK_EXTERNAL=true` is set in `afon-aws.properties` (it is `false` when empty): it then uses the KeyCloak
`aws-keycloak` deploys, like `aws-polaris`, so it runs side by side with `aws-keycloak`, and its `deploy` and `setup`
likewise check first that KeyCloak answers.
Only the Polaris stacks pin their egress IP to `AWS_EGRESS_IP`, if set: `aws-keycloak` always gets a fresh one.

### Validate a stack's config

To validate a stack's config without deploying it, eg that the config dir has all the properties the stack needs,
you can synthesise just the cdktf plan of a stack, which needs no cloud login:
```shell
afon-cli.sh validate-stack <stack>  # eg azure-polaris or aws-full, run without <stack> to list the stacks
```
This fails fast, eg with `Property X not found`, if the config is incomplete.
It overwrites the plan in `$AFON_CDKTF/stacks/<stack-id>` (ie `cdk.tf.json` and `assets`), but not the state file
`$AFON_CDKTF/terraform.<stack-id>.tfstate`, as it doesn't run tofu.  To leave the plan of a running or previous deploy
of the same stack untouched, synthesise into another cdktf dir, eg `afon-cli.sh --cdktf /tmp/cdktf validate-stack azure-polaris`.

### Setup AWS full stack

The AWS full stack includes Polaris, Spark (via Spark Connect), and Jupyter (behind Guacamole with OAuth2).

```shell
afon-cli.sh manage-stack setup aws-full
```
Steps executed:

1. `delete_stacks_conflicting_with_aws_full` — delete the AWS stacks conflicting with it, ie any existing AWS stack
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
at `OIDC_DOMAIN_NAME`, without Polaris, eg to serve as a standalone OIDC provider, such as for `aws-polaris`, which
runs side by side with it.

```shell
afon-cli.sh manage-stack deploy aws-keycloak
```
Steps: `delete_stacks_conflicting_with_aws_keycloak` → `deploy_aws_keycloak_stack` → `wait_on_emrysoauth` → `deploy_complete_for_aws_keycloak`

It requires the Keycloak properties (`OIDC_DOMAIN_NAME`, `KEYCLOAK_*`, `AWS_KEYCLOAK_DNS_ZONE`, `AWS_KEYCLOAK_CERT_ARN`)
and the AWS cluster properties, but none of the `AWS_POLARIS_*` properties.
Once deployed, you can check the OIDC provider is ready with `afon-cli.sh check-oidc`.

### Setup AWS Keycloak only

To deploy the AWS Keycloak stack and configure it with the realm, the `POLARIS_ID` client and the users (and
their confidential clients), plus the Jupyter browser client if `AWS_JUPYTER_DOMAIN_NAME` is set:

```shell
afon-cli.sh manage-stack setup aws-keycloak
```
Steps: `delete_stacks_conflicting_with_aws_keycloak` → `deploy_aws_keycloak_stack` → `wait_on_emrysoauth` → `configure_oidc_users_for_cloud_aws` → `setup_complete_for_aws_keycloak`

### Setup Azure Polaris only

```shell
afon-cli.sh manage-stack setup azure-polaris
```
Steps: `delete_stacks_conflicting_with_azure_polaris` → `check_keycloak_answers` → `deploy_azure_polaris_stack` → `wait_on_emryspolarisazure_then_wait_on_emrysoauth` → `create_all_catalogs_for_cloud_azure`

### Setup GCP Polaris only

```shell
afon-cli.sh manage-stack setup gcp-polaris
```
Steps: `delete_stacks_conflicting_with_gcp_polaris` → `check_keycloak_answers` → `deploy_gcp_polaris_stack` → `wait_on_emryspolarisgcp_then_wait_on_emrysoauth` → `create_all_catalogs_for_cloud_gcp`

### Setup cross-cloud external catalogs (AWS + Azure)

To deploy AWS and Azure Polaris instances and configure AWS Polaris to federate to Azure Polaris for cross-cloud querying.

```shell
afon-cli.sh setup-aws-full-stack-with-azure-polaris-hosting-external-catalog
```

Steps: `delete_aws_all_stack → deploy_aws_full_stack → wait_on_emryspolaris_then_wait_on_emrysoauth → create_all_catalogs_for_cloud_aws → aws_spark_talks_to_catalog_aws → delete_azure_all_stack → deploy_azure_polaris_stack → wait_on_emryspolarisazure_then_wait_on_emrysoauth → delete_table_azure.namespaceph.test_table_for_cloud_azure → create_external_catalog_azure_for_cloud_azure → aws_spark_talks_to_catalog_azure_external`

### Resume a failed stack deployment from a specific step

If a deployment fails partway through, you can resume from a specific step, by adding it to the command, e.g.:

```shell
afon-cli.sh manage-stack setup aws-full "create_all_catalogs_for_cloud_aws"
afon-cli.sh setup-aws-full-stack-with-azure-polaris-hosting-external-catalog "deploy_azure_polaris_stack"
```

### Teardown a deployed stack

The stack teardown can be done via replacing `deploy` with `delete` in the command, eg:
```shell
afon-cli.sh manage-stack delete aws-full
afon-cli.sh manage-stack delete azure-polaris
afon-cli.sh manage-stack delete gcp-polaris
```

The `delete` action is equivalent to running `tofu destroy` on the appropriate `$AFON_CDKTF` dir: it deletes just the
stack, and `delete <cloud>-all` deletes every stack deployed in the cloud, see [stacks side by side](#stacks-side-by-side).

### The Afon cdktf dir

The afon-cdk module generates the Terraform plan using the cdktf API to manage the cloud environment and delegates it to tofu to execute it.   The afon-cdk module writes to the cdktf dir, which can be set either via option `--cdktf` in `afon.sh` or via env variable `AFON_CDKTF`.  It defaults to `$AFON_HOME/cdktf`.

Each stack has its own cdktf plan and Terraform state, named by its stack id `<cloud>-<stack-type>`, eg `aws-keycloak`:
the cdktf plan is written to `$AFON_CDKTF/stacks/<stack-id>` and the cdktf dir also holds the Terraform state files
`$AFON_CDKTF/terraform.<stack-id>.tfstate`.

Each AWS stack also has its own kubeconfig `$AFON_CDKTF/kube/<stack-id>.config`, eg `kube/aws-keycloak.config`,
written when its EKS cluster is created and deleted with the stack.  All the kubectl and helm commands the stack runs,
eg the cleanups when it is destroyed, use it via `KUBECONFIG`, so they only ever act on the stack's own cluster,
whichever cluster your current kubectl context points at.  Creating the cluster also points your current kubectl context
(`~/.kube/config`) at it, as `afon-cli.sh aws-update-kube-config <stack-type>` does, eg for the smoke tests.

**NOTE:** Before each stack had its own, the stacks of a cloud shared the plan `$AFON_CDKTF/stacks/<cloud>` and state
`$AFON_CDKTF/terraform.<cloud>.tfstate`, where `<cloud>={aws,azure,gcp}`.  A stack still deployed with these is deleted,
like any other stack of the cloud, by the `delete`, `deploy` and `setup` actions of any stack of the cloud.

You can run tofu apply or destroy commands via
```shell
afon-cli.sh tofu-{apply,destroy}
```
Given a stack id as first arg, eg `afon-cli.sh tofu-plan aws-polaris`, they run in its stack dir.  Else they run in
the dir of the only stack deployed in the cloud given by `--cloud` (default `aws`), ie the stack dir with a Terraform
state, else the only stack dir of the cloud.
**NOTE:** The `ops.Deploy*Main` classes generate the plan and execute it without needing the `tofu-apply` execution.
You may re-run the `tofu-apply` if needed.

To validate a stack's config without deploying it, eg that the config dir has all the properties the stack needs,
you can synthesise just the cdktf plan of a stack, which needs no cloud login:
```shell
afon-cli.sh validate-stack <stack>  # eg azure-polaris or aws-full, run without <stack> to list the stacks
```
This fails fast, eg with `Property X not found`, if the config is incomplete.
It overwrites the plan in `$AFON_CDKTF/stacks/<stack-id>` (ie `cdk.tf.json` and `assets`), but not the state file
`$AFON_CDKTF/terraform.<stack-id>.tfstate`, as it doesn't run tofu.  To leave the plan of a running or previous deploy
of the same stack untouched, synthesise into another cdktf dir, eg `--cdktf /tmp/cdktf`.

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
afon-cli.sh generate-cleanup-script <stack-id>  # eg aws-keycloak, generating $AFON_HOME/aws-keycloak-cleanup.sh
```
You can run the generated cleanup scripts.
You may need to run them multiple times for the VPC to disappear from your cloud.

### AWS

The AWS components of each stack are named from its cluster name `<stack-type>-<CLUSTER_NAME>`, eg
`keycloak-emrys-cluster` for stack `aws-keycloak`: its EKS cluster, its VPC `cdktf-eks-vpc-<cluster-name>`, its subnets,
its IAM roles and so on.  So the AWS scripts acting on a stack are given its stack type, eg:
```shell
afon-cli.sh aws-cluster keycloak             # is the EKS cluster up?
afon-cli.sh aws-update-kube-config keycloak  # point kubectl at the EKS cluster
```
Checking Polaris needs no stack type, as only one of the stacks serving Polaris runs at a time: it is found by its
EKS cluster, unless given, eg `aws-polaris full`:
```shell
afon-cli.sh aws-polaris                      # is Polaris serving?
```
The IAM role names are limited to 64 chars, so `CLUSTER_NAME` must be short enough for
`AmazonEKSLoadBalancerControllerRole<stack-type>-<CLUSTER_NAME>`, which a stack checks when it is synthesised.

**NOTE:** Before each stack had its own cluster name, the components were named from `CLUSTER_NAME` alone.

To check the AWS VPC has been destroyed:

```shell
afon-cli.sh aws-vpcs             # the VPCs of every stack of this env, same as: afon-cli-scripts.sh aws/vpcs.sh
afon-cli.sh aws-vpcs <stack-type>  # the VPC of the stack of the type, eg keycloak
```

And if all else fails, login to the [AWS VPC Console](https://eu-west-2.console.aws.amazon.com/vpcconsole/home?region=eu-west-2#vpcs:)

### Azure 

When Azure resource group is stuck:

```shell
az group delete --name afon_resource_group_aks --yes --no-wait
```

## Summary of deployment commands

```shell
# Any stack: <action>={delete|deploy|setup}, <stack-id>=<cloud>-<stack-type>, eg aws-keycloak
afon-cli.sh manage-stack <action> <stack-id>

# AWS full stack (Keycloak + Polaris + Spark + Jupyter)
afon-cli.sh manage-stack deploy aws-full

# AWS Keycloak (standalone)
afon-cli.sh manage-stack deploy aws-keycloak

# Azure Polaris (standalone)
afon-cli.sh manage-stack deploy azure-polaris

# GCP Polaris (standalone)
afon-cli.sh manage-stack deploy gcp-polaris

# Cross-cloud external catalogs (AWS + Azure federation)
afon-cli.sh setup-aws-full-stack-with-azure-polaris-hosting-external-catalog

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
