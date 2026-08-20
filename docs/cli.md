# Afon CLI

## Pre-requisites 

### Setup Local Env

See [setup local env](setup_local.md).

## Afon CLI files

The Afon CLI is a standalone executable jar which contains all the dependencies it needs.   It can then be distributed 
without need to have access to Afon source codebase. 

The afon-cli.jar comes with an afon-cli.sh script that sets all the runtime JVM args the jar requires and executes the jar.
In the following, we assume that the afon-cli files are found in the dir `~/.afon/cli`.

Running the `help` command gives a list of all the available CLI commands:
```shell
~/.afon/cli/afon-cli.sh help
```
The script supports the following env variables:

- `AFON_HOME`: the Afon home dir, defaulting to $HOME/.afon.
- `AFON_CLOUD`: the cloud provider to target, one of {aws,azure,gcp}.  This is optional and only affects some functionality.
- `AFON_LOGGING`: the file logging level, defaulting to INFO.   The file logs are found the `$AFON_HOME/logs`.
- `AFON_LOGGING_CONSOLE`: the console logging level, defaulting to OFF.

For example, to run command with console logging: 

```shell
AFON_LOGGING_CONSOLE=INFO ~/.afon/cli/afon-cli.sh <command>
```

## Stack deployment

To deploy a stack, using the CLI command, we run the corresponding command:

```shell
~/.afon/cli/afon-cli.sh deploy-aws-full-stack
~/.afon/cli/afon-cli.sh deploy-azure-polaris-stack
~/.afon/cli/afon-cli.sh deploy-gcp-polaris-stack
```

The credentials in the cloud-specific env properties in [config dir](configuration.md) are used by the deployed Polaris instance.

**NOTE:** Currently, only AWS has the full stack.
Azure and GCP just host Polaris at the moment. There's no particular reason for this other than expediency.

### Deploy AWS full stack

The AWS full stack includes Polaris, Spark (via Spark Connect), and Jupyter (behind Guacamole with OAuth2).

```shell
~/.afon/cli/afon-cli.sh deploy-aws-full-stack
```
Steps executed:

1. `clean_aws` — destroy any existing AWS stack
2. `aws_spark` — deploy Polaris + Spark to AWS via CDKTF + OpenTofu
3. `wait_on_emryspolaris` — wait for DNS to propagate
4. `all_catalogs_in_emryspolaris` — configure all catalogs in Polaris
5. `aws_spark_talks_to_aws` — smoke test: Spark writes and reads via Polaris

### Deploy Azure Polaris only

```shell
~/.afon/cli/afon-cli.sh deploy-azure-polaris-stack
```
Steps: `clean_azure` → `azure_polaris` → `wait_on_emryspolarisazure` → `all_catalogs_in_emryspolarisazure`

### Deploy GCP Polaris only

```shell
~/.afon/cli/afon-cli.sh deploy-gcp-polaris-stack
```
Steps: `clean_gcp` → `gcp_polaris` → `wait_on_emryspolarisgcp` → `all_catalogs_in_emryspolarisgcp`

### Deploy cross-cloud external catalogs (AWS + Azure)

To deploy AWS and Azure Polaris instances and configure AWS Polaris to federate to Azure Polaris for cross-cloud querying.

```shell
~/.afon/cli/afon-cli.sh deploy-external-catalogs
```

Steps: `clean_aws` → `aws_spark` → `wait_on_emryspolaris` → `all_catalogs_in_emryspolaris` → `clean_azure` → `azure_polaris` → `wait_on_emryspolarisazure` → `emryspolarisazure_purge_azure` → `foreign_catalog_in_emryspolarisazure` → `external_azure_in_emryspolarisazure` → `aws_spark_talks_to_azure_external`

### Resume a failed stack deployment from a specific step

If a deployment fails partway through, you can resume from a specific step, e.g.:

```shell
~/.afon/cli/afon-cli.sh deploy-external-catalogs "azure_polaris"
```

