# Afon CLI

## Afon CLI distribution

The binary distribution `afon-distribution-cli` allows the use of Afon without needing access to the source codebase.

The Afon CLI distribution is a zip consisting of:

- `afon-cli.jar`: a standalone executable which contains all the dependencies it requires.
- `afon-cli.sh`: a script that executes the afon-cli.jar, setting all the runtime JVM args and env variables.
- `afon-cli-scripts.sh`: a script that executes the cli scripts, setting the appropriate env variables.
- `docs`: the docs relating the running of the commands.
- `templates`: the config templates.

## Installing Afon CLI 

The `afon-distribution-cli` zip is a self-contained application that can be installed anywhere by extracting zip 
to a given dir.  

Assuming it has been unpacked in the dir `~/.afon/cli` and calling this dir `AFON_CLI_DIR`,
you can add the `AFON_CLI_DIR` to the `PATH` in your shell so that it can be executed from anywhere:

```shell
AFON_CLI_DIR=$HOME/.afon/cli
PATH=$AFON_CLI_DIR:$PATH
export PATH
```

In the following, we'll assume that `afon-cli.sh` in the PATH.  Else, simply prefix it with its full path.  
You don't need to be in the location of the `afon-cli.sh` file to execute it.


## Configuring local env

On linux, the minimum list of apt packages to execute the CLI commands are:

- openjdk-17-jre
- tofu
- kubectl

In addition, according to your chosen cloud provider:

- awscli eksct
- azure-cli kubelogin
- google-cloud-cli

**NOTE:** for the `ekscli` package on linux follow the [eksctl installation](https://docs.aws.amazon.com/eks/latest/eksctl/installation.html) and
for the `kubelogin` run `az aks install-cli` after having installed `azure-cli`.

## Afon CLI configuration

The CLI requires the [configuration](configuration.md) to be completed for the cloud env you are targeting.

## Running Afon CLI

Running the `help` command gives a list of all the available CLI commands:
```shell
afon-cli.sh help
```

The commands are the (kebab-named) versions of the executable Java classes named `*Main` found in package `health.emrys.afon.ops` (in module `afon-ops`).

The script supports the following env variables:

- `AFON_HOME`: the Afon home dir, defaulting to `$HOME/.afon`.
- `AFON_CDKTF`: the Afon cdktf dir, defaulting to `$AFON_HOME/cdktf`.
- `AFON_CLOUD`: the cloud provider to target, one of `{aws,azure,gcp}`.  This is optional and only affects some functionality.
- `AFON_LOGGING`: the file logging level, defaulting to `INFO`.   The file logs are found the `$AFON_HOME/logs`.
- `AFON_LOGGING_CONSOLE`: the console logging level, defaulting to `OFF`.
- `AFON_ENVS`: the `NAME=VALUE` pairs of system properties passed to the CLI java process.  Can be used to override  file-based properties in config dir.

For example, to run command with console logging: 

```shell
AFON_LOGGING_CONSOLE=INFO afon-cli.sh <command>
```

**IMPORTANT:** the `*-stack` commands use `$AFON_CDKTF` as their `tofu` state dir, so if you deploy or setup a stack (ie 
deploy a stack plus configure the Polaris catalogs) you must take care not move your `$AFON_CDKTF` until the teardown of 
the stack, other `tofu` will not know the information to destroy the stack.

## Stack deployment

To deploy a stack, using the CLI command, we run the corresponding command:

```shell
afon-cli.sh deploy-aws-full-stack
afon-cli.sh deploy-azure-polaris-stack
afon-cli.sh deploy-gcp-polaris-stack
```

**NOTE:** To run the `delete-*-stak`, `deploy-*-stak`, `setup-*-stack` commands you need to be logged with [cloud cli](cloud.md).

In the command, we use the convention that `deploy` just deploy stack (waiting for DNS to propagate) and `setup` does the deployment
and configures the Polaris catalogs.

### Setup AWS full stack

The AWS full stack includes Polaris, Spark (via Spark Connect), and Jupyter (behind Guacamole with OAuth2).

```shell
afon-cli.sh setup-aws-full-stack
```
Steps executed:

1. `clean_aws` — destroy any existing AWS stack
2. `aws_spark` — deploy Polaris + Spark to AWS via CDKTF + OpenTofu
3. `wait_on_emryspolaris` — wait for DNS to propagate
4. `create_all_catalogs_for_cloud_aws` — create all catalogs in AWS Polaris
5. `aws_spark_talks_to_cloud_aws` — smoke test: Spark writes and reads via Polaris

### Setup Azure Polaris only

```shell
afon-cli.sh setup-azure-polaris-stack
```
Steps: `clean_azure` → `azure_polaris` → `wait_on_emryspolarisazure` → `create_all_catalogs_for_cloud_azure`

### Setup GCP Polaris only

```shell
afon-cli.sh setup-gcp-polaris-stack
```
Steps: `clean_gcp` → `gcp_polaris` → `wait_on_emryspolarisgcp` → `create_all_catalogs_for_cloud_gcp`

### Deploy cross-cloud external catalogs (AWS + Azure)

To deploy AWS and Azure Polaris instances and configure AWS Polaris to federate to Azure Polaris for cross-cloud querying.

```shell
afon-cli.sh deploy-external-catalogs
```

Steps: `clean_aws → aws_full → wait_on_emryspolaris_then_wait_on_emrysoauth → create_all_catalogs_for_aws → aws_spark_talks_to_aws → clean_azure → azure_polaris → wait_on_emryspolarisazure_then_wait_on_emrysoauth → delete_table_azure.namespaceph.test_table_for_cloud_azure → create_external_catalog_azure_for_cloud_azure → aws_spark_talks_to_catalog_azure_external`

### Resume a failed stack deployment from a specific step

If a deployment fails partway through, you can resume from a specific step, e.g.:

```shell
afon-cli.sh deploy-external-catalogs "azure_polaris"
```

### Teardown a deployed stack

The stack teardown can be done via replacing `deploy` with `delete` in the command, eg:
```shell
afon-cli.sh delete-aws-full-stack
afon-cli.sh delete-azure-polaris-stack
afon-cli.sh delete-gcp-polaris-stack
```

The `delete` commands are equivalent to running `tofu destroy` on the appropriate `$AFON_CDKTF` dir.
