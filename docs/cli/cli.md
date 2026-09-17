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

To see just the commands under a given prefix (eg every `config-*` [shorthand command](#script-commands)), pass it
followed by `-h`/`--help`:
```shell
afon-cli.sh config -h
```
This works because `config` isn't itself a command, only a shared prefix — if the prefix has no matching commands,
this reports that instead. It does not apply to a real command name (eg `afon-cli.sh config-status -h` runs
`config-status` with `-h` forwarded to it as a plain arg, rather than listing commands).

The script accepts the following options, either as a `--flag` (placed anywhere before the command, eg
`afon-cli.sh --cloud aws deploy-aws-full-stack`) or as the equivalent `AFON_*` env var — a flag wins if both are given:

- `--home` / `AFON_HOME`: the Afon home dir, defaulting to `$HOME/.afon`.
- `--cdktf` / `AFON_CDKTF`: the Afon cdktf dir, defaulting to `$AFON_HOME/cdktf`.
- `--cloud`/`-c` / `AFON_CLOUD`: the cloud provider to target, one of `{aws,azure,gcp}`.  This is optional and only affects some functionality.  Defaults to `ALL`.
- `--config-env` / `AFON_CONFIG_ENV`: the config env name.  Applies up front, for the [shorthand commands](#shorthand-commands) below.
- `--cdktf-env` / `AFON_CDKTF_ENV`: the cdktf env name.  Applies up front, for the [shorthand commands](#shorthand-commands) below.
- `--env`/`-e` / `AFON_ENV`: sets both `--cdktf-env` and `--config-env`.  Overrides them if also set.
- `--logging`/`-l` / `AFON_LOGGING`: the file logging level, defaulting to `INFO`.   The file logs are found the `$AFON_HOME/logs`.
- `--logging-console`/`-lc` / `AFON_LOGGING_CONSOLE`: the console logging level, defaulting to `OFF`.
- `AFON_ENVS` (env only): the `NAME=VALUE` pairs of system properties passed to the CLI java process.  Can be used to override  file-based properties in config dir.

For example, to run command with console logging: 

```shell
afon-cli.sh --logging-console INFO <command>
```

**NOTE:** The options `--logging`/`--logging-console` are not supported by `afon-cli-scripts.sh` as the scripts it runs have no use
for a  logging level, so those two are only recognized before the command name, configuring this JVM's own logging — after
the command name they're not recognized at all and get forwarded as plain args to the script.

## Script commands

Every script under `scripts/` (the ones runnable via [`afon-cli-scripts.sh`](configuration.md)) also has a shorthand 
`afon-cli.sh` command, so you don't need to switch between the two entry points.  
The command name is the script's path relative to `scripts`, with the `/` replaced by `-` and the `.sh` removed, eg:

```shell
afon-cli.sh config-switch --config-env dev      # same as: afon-cli-scripts.sh config/switch.sh --config-env dev
afon-cli.sh cdktf-status                        # same as: afon-cli-scripts.sh cdktf/status.sh
afon-cli.sh aws-login                           # same as: afon-cli-scripts.sh aws/login.sh
```

They accept the same `--cloud`/`-c`, `--config-env`, `--cdktf-env`, `--env`/`-e` flags described above — placed
either before the command name (handled by `afon-cli.sh` itself) or after it (handled by the shortcut command
directly); either way they're translated into the `AFON_*` env vars `afon-cli-scripts.sh` and the scripts expect.

Anything else is forwarded as-is to the script, positionally — eg `afon-cli.sh azure-create true` to pass
`AS_ADMIN=true`, or `afon-cli.sh config-export myconfig.zip` to pass the zip path `config/export.sh` expects.    
Run `afon-cli.sh help` to see the full command list alongside the `*-stack` commands.

## Stack deployment

To deploy a stack, using the CLI command, we run the corresponding command:

```shell
afon-cli.sh deploy-aws-full-stack
afon-cli.sh deploy-azure-polaris-stack
afon-cli.sh deploy-gcp-polaris-stack
```

**NOTE:** To run the `delete-*-stak`, `deploy-*-stak`, `setup-*-stack` commands you need to be logged with [cloud cli](cloud.md).

**IMPORTANT:** the `*-stack` commands use `$AFON_CDKTF` as their `tofu` state dir, so if you deploy or setup a stack (ie
deploy a stack plus configure the Polaris catalogs) you must take care not move your `$AFON_CDKTF` until the teardown of
the stack, other `tofu` will not know the information to destroy the stack.


In the command, we use the convention that `deploy` just deploy stack (waiting for DNS to propagate) and `setup` does the deployment
and configures the Polaris catalogs.

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
