# Afon CLI

## Afon CLI distribution

The AFON CLI distribution allows the use of Afon without requiring access to the source codebase. 

It is packaged as a zip consisting of:

- `afon-cli.jar`: a standalone executable which contains all the dependencies it requires.
- `afon-cli.sh`: a script that executes the afon-cli.jar, setting all the runtime JVM args and env variables.
- `afon-cli-scripts.sh`: a script that executes the cli scripts, setting the appropriate env variables.
- `docs`: the docs relating the running of the commands.
- `scripts`: the scripts that can be executed via both `afon-cli.sh` and `afon-cli-scripts.sh`
- `smoke`: the smoke tests, ie the regression suite `run-regression-suite.sh` with its suite files, and the OIDC
  notebook (see [smoke tests](smoke_tests.md)).
- `templates`: the config templates.

## Installing Afon CLI 

The CLI is a self-contained application that can be installed anywhere by extracting zip to a given dir.  

In bash, set in `~/.bash_profile` the location of the CLI and add it to the `PATH`:
```shell
export AFON_CLI_DIR=$HOME/.afon/cli
PATH=$AFON_CLI_DIR:$PATH
export PATH
```
Then to install: 
```shell
unzip afon-<version>-cli.zip -d $AFON_CLI_DIR
```

To check the CLI is properly installed: 
```shell
afon-cli.sh
```
You can suppress the banner via the env var `SPRING_MAIN_BANNER_MODE=off`.

In the following, we'll assume that `afon-cli.sh` in the `PATH`.  Else, simply prefix it with its full path.  
You don't need to be in the location of the `afon-cli.sh` file to execute it.

## Configuring local env

On linux or macOS, you can set up your local env by running:

```shell
afon-cli.sh local-setup
```

## Afon CLI configuration

The CLI requires the [configuration](configuration.md) to be completed for the cloud env you are targeting.

## Running Afon CLI

Running the `help` command gives a list of all the available CLI commands:
```shell
afon-cli.sh help
```

The script accepts the following options, either as a `--flag` or as the equivalent `AFON_*` env var — a flag wins if both are given:

- `--home` / `AFON_HOME`: the Afon home dir, defaulting to `$HOME/.afon`.
- `--cdktf` / `AFON_CDKTF`: the Afon cdktf dir, defaulting to `$AFON_HOME/cdktf`.
- `--cloud`/`-c` / `AFON_CLOUD`: the cloud provider to target, one of `{aws,azure,gcp}`.  This is optional and only affects some functionality.  Defaults to `ALL`.
- `--config-env` / `AFON_CONFIG_ENV`: the config env name.  If set, executes the `config-switch` command immediately, before running any command.
- `--cdktf-env` / `AFON_CDKTF_ENV`: the cdktf env name.  If set, executes the `cdktf-switch` command immediately, before running any command.
- `--env`/`-e` / `AFON_ENV`: sets both `--cdktf-env` and `--config-env`.  The `--env` flag overrides both of them; the `AFON_ENV` env var only overrides their env vars, not their flags.
- `--logging`/`-l` / `AFON_LOGGING`: the file logging level, defaulting to `INFO`.   The file logs are found the `$AFON_HOME/logs`.
- `--logging-console`/`-lc` / `AFON_LOGGING_CONSOLE`: the console logging level, defaulting to `OFF`.
- `AFON_ENVS` (env only): the `NAME=VALUE` pairs of system properties passed to the CLI java process.  Can be used to override  file-based properties in config dir.

## Script commands

Every script under `scripts/` is runnable via `afon-cli.sh` as well as `afon-cli-scripts.sh`, so you don't need to switch between the two entry points.  

The command name is the script's `<dir>/<name>` path relative to `scripts`, with the `/` replaced by `-` and the `.sh` removed.  So, eg:

```shell
afon-cli.sh config-switch --config-env dev      # same as: afon-cli-scripts.sh config/switch.sh --config-env dev
afon-cli.sh cdktf-status                        # same as: afon-cli-scripts.sh cdktf/status.sh
afon-cli.sh aws-login                           # same as: afon-cli-scripts.sh aws/login.sh
```

The scripts accept the same `--cloud`/`-c`, `--config-env`, `--cdktf-env`, `--env`/`-e` flags described above — placed
either before the command name (handled by `afon-cli.sh` itself) or after it (handled by the script command
directly); either way they're translated into the `AFON_*` env vars `afon-cli-scripts.sh` and the scripts expect.

Anything else is forwarded as-is to the script, positionally — eg `afon-cli.sh azure-create true azure-full` to pass
`AS_ADMIN=true` and the stack id, or `afon-cli.sh config-export myconfig.zip` to pass the zip path `config/export.sh` expects.    

To see the full command list, run: 
```shell
afon-cli.sh help
``` 

## Ops commands

The commands are the (kebab-named) versions of the executable Java classes named `*Main` found in package 
`health.emrys.afon.ops` and its subpackages (in module `afon-ops`), eg:

```md
`ManageStackMain` -> `manage-stack`
`ManagePolarisCatalogsMain` -> `manage-polaris-catalogs`
`ValidateStackMain` -> `validate-stack`
```

To see just the commands related to area of interest (eg all `config`-related [script command](#script-commands)), pass it 
followed by `-h`/`--help`:
```shell
afon-cli.sh config -h
```
This works because `config` isn't itself a command, only a filter — if the filter has no matching commands,
this reports that instead. It does not apply to a real command name (eg `afon-cli.sh config-status -h` runs
`config-status` with `-h` forwarded to it as a plain arg, rather than listing commands).

A real command can have its own docs, kept in `afon-ops/src/main/resources/help/<command>.md`, which `-h` prints
instead of running the command, eg:
```shell
afon-cli.sh validate-stack -h
```
A command without such docs still gets `-h` forwarded to it.

For example, to run command with console logging:
```shell
afon-cli.sh -lc INFO <command>
```

**NOTE:** The options `--logging`/`-l` and `--logging-console`/`-lc` are not supported by `afon-cli-scripts.sh` as the scripts it runs have no use for a logging level, so those two are only recognised before the command name, configuring this JVM's own logging — after the command name they're not recognised at all and get forwarded as plain args to the script.

## Smoke tests

The `smoke` dir of the distribution holds the smoke regression suite (see [smoke tests](smoke_tests.md#regression-suite)).
Run from the distribution, it runs its commands, the smoke tests' as well as those setting up or reaching the stacks,
with the distribution's `afon-cli.sh`, which resolves them as any other command, as if given `--cli`: the
distribution, being independent of the source tree, has no `afon.sh`.
```shell
$AFON_CLI_DIR/smoke/run-regression-suite.sh --env <env> [aws|azure]
```
It runs the tests of `smoke/regression-full.csv` by default, or those of another suite file given with `--suite`, against the
full stack of the cloud given, `aws` by default.
