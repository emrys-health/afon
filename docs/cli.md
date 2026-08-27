# Afon CLI

## Pre-requisites 

### Setup Local Env

On linux, the minimum list of apt packages to execute the CLI commands are:

- openjdk-17-jre 
- tofu
- kubectl

In addition, according to your chosen cloud provider:

- awscli 
- eksct 
- azure-cli 
- google-cloud-cli

**NOTE:** for the `ekscli` package on linux follow the [eksctl installation](https://docs.aws.amazon.com/eks/latest/eksctl/installation.html).

## Afon CLI distribution

The Afon CLI distribution is a zip consisting of:

- afon-cli.jar, a standalone executable which contains all the dependencies it requires
- afon-cli.sh, a script that sets all the runtime JVM args the jar requires and executes the afon-cli.jar.
- docs, the docs relating the running of the commands.

The binary distribution `afon-distribution-cli` allows the use of Afon without needing access to the source codebase.

In the following, we assume that the `afon-distribution-cli` zip has been unpacked in the dir `~/.afon/cli`.   
You can add it to the PATH in your shell so that it can be executed from anywhere:

```shell
AFON_CLI_DIR=$HOME/.afon/cli
PATH=$AFON_CLI_DIR:$PATH
export PATH
```

In the following, we'll assume that `afon-cli.sh` in the PATH.  Else, simply prefix it with its full path.  
You don't need to be in the location of the `afon-cli.sh` file to execute it.

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

For example, to run command with console logging: 

```shell
AFON_LOGGING_CONSOLE=INFO afon-cli.sh <command>
```

**IMPORTANT:** the `*-stack` commands use `$AFON_CDKTF` as their `tofu` state dir, so if you deploy or setup a stack (ie 
deploy a stack plus configure the Polaris catalogs) you must take care not move your `$AFON_CDKTF` until the teardown of 
the stack, other `tofu` will not know the information to destroy the stack.

## CLI configuration 

The CLI requires the [configuration](configuration.md) to be completed for the cloud env you are targeting.

## Stack deployment

To deploy a stack, using the CLI command, we run the corresponding command:

```shell
afon-cli.sh deploy-aws-full-stack
afon-cli.sh deploy-azure-polaris-stack
afon-cli.sh deploy-gcp-polaris-stack
```

**NOTE:** Currently, only AWS has the full stack.
Azure and GCP just host Polaris at the moment. There's no particular reason for this other than expediency.
We could just as well have a full stack on Azure or GCP.

### Deploy AWS full stack

The AWS full stack includes Polaris, Spark (via Spark Connect), and Jupyter (behind Guacamole with OAuth2).

```shell
afon-cli.sh deploy-aws-full-stack
```
Steps executed:

1. `clean_aws` — destroy any existing AWS stack
2. `aws_spark` — deploy Polaris + Spark to AWS via CDKTF + OpenTofu
3. `wait_on_emryspolaris` — wait for DNS to propagate
4. `all_catalogs_in_emryspolaris` — configure all catalogs in Polaris
5. `aws_spark_talks_to_aws` — smoke test: Spark writes and reads via Polaris

### Deploy Azure Polaris only

```shell
afon-cli.sh deploy-azure-polaris-stack
```
Steps: `clean_azure` → `azure_polaris` → `wait_on_emryspolarisazure` → `all_catalogs_in_emryspolarisazure`

### Deploy GCP Polaris only

```shell
afon-cli.sh deploy-gcp-polaris-stack
```
Steps: `clean_gcp` → `gcp_polaris` → `wait_on_emryspolarisgcp` → `all_catalogs_in_emryspolarisgcp`

### Deploy cross-cloud external catalogs (AWS + Azure)

To deploy AWS and Azure Polaris instances and configure AWS Polaris to federate to Azure Polaris for cross-cloud querying.

```shell
afon-cli.sh deploy-external-catalogs
```

Steps: `clean_aws` → `aws_spark` → `wait_on_emryspolaris` → `all_catalogs_in_emryspolaris` → `clean_azure` → `azure_polaris` → `wait_on_emryspolarisazure` → `emryspolarisazure_purge_azure` → `foreign_catalog_in_emryspolarisazure` → `external_azure_in_emryspolarisazure` → `aws_spark_talks_to_azure_external`

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
