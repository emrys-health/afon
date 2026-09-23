# CLI Configuration

`afon-cli-scripts.sh` accepts `--home`, `--cdktf`, `--cloud`/`-c`, `--config-env`, `--cdktf-env` and `--env`/`-e` as
flags, or the equivalent `AFON_*` env var (a flag wins if both are given) — see `afon-cli-scripts.sh --help`.  The
examples below use flags; the env var form still works exactly as before.  Options specific to a single script (eg
the zip path `config/export.sh`/`config/import.sh` need) are not flags here — pass them as plain script args instead.

## Config dir

All configuration is read from the config dir, assumed to be a child dir named `config` in the `$AFON_HOME` dir, 
which by default points to `$HOME/.afon`.  The default config dir is then `$HOME/.afon/config`.

The Afon home can be overridden with `--home`.

### Init config

You can initialise the config files by running:

```shell
afon-cli.sh config-init
```
This copies empty config files from templates to the config dir.  

**NOTE**: The command `config-init` will create a new config dir, if not found, but it also can work with an existing config dir. However, in case of an existing config dir, any existing files will not be overwritten and will need to be removed manually.

Once initialised, the config values need to be completed.

To init a named config env (see [Switching env](#switching-env)), pass `--config-env`:

```shell
afon-cli.sh config-init --config-env <env> 
```

### Switching env

If you maintain config for more than one env (eg `dev`, `prod`), each is kept in its own `$AFON_HOME/config-<env>` dir
and switched in via a symlink at `$AFON_HOME/config`.  `--config-env` is global: whenever it's set, the switch is
applied up front, before whichever script you're running — not just when the command is `config-switch`:

```shell
afon-cli.sh config-switch --config-env <env> 
```

Similarly, the cdktf (`tofu`/terraform state) dir can be switched between named envs with `--cdktf-env`, which is applied the
same way, up front, regardless of the script being run (see [Cdktf dir](#cdktf-dir)):

```shell
afon-cli.sh cdktf-switch --cdktf-env <env>
```

`--env`/`-e` is a shorthand for setting both `--config-env` and `--cdktf-env` to the same value (overriding either
if also given), so both dirs are switched together in one call:

```shell
afon-cli.sh config-validate --env <env>
```

### Import / export config

Config can be imported from, or exported to, a zip file, passed as a plain arg to the script (not a `--flag` — the
zip path only matters to these two scripts, so it isn't global like `--config-env`/`--cdktf-env`/`--env`).  For
import/export, the path can be absolute or relative to `$AFON_HOME`:

```shell
afon-cli.sh config-export <path-to-config.zip> 
afon-cli.sh config-import <path-to-config.zip> [--config-env <env>]
```

### Validate config

You can validate the config files by running: 
```shell
afon-cli.sh config-validate [--cloud <cloud>]
```
The validation checks that all the template properties have a corresponding non-empty property in the config dir.
optional `--cloud <cloud>` allows you to check only the cloud-specific config. 

**IMPORTANT:** Some functionality still requires the config files for all clouds to be present, although only the
values for the cloud you are using need be completed.  There is [open ticket to fix issue](https://github.com/emrys-health/afon-core/issues/159).  
The recommended workaround is to init without specifying the cloud and to validate specifying it.

## Cdktf dir

The `cdktf` scripts mirror the `config` ones, for managing the `$AFON_CDKTF` dir (the `tofu` state dir, defaulting to
`$AFON_HOME/cdktf`) used by the `*-stack` commands.

```shell
afon-cli.sh cdktf-init
afon-cli.sh cdktf-status
afon-cli.sh cdktf-switch --cdktf-env <env>
```

As with `config/init.sh`, pass `--cdktf-env` to `cdktf/init.sh` to create the dir for a named env rather than the
default one.

## Properties

The properties configuration is currently file-based.

The configuration properties are cloud-specific but will include some properties that are common to all cloud providers.  

All cloud-specific configuration files will include the cloud reference in their names, e.g. `afon-aws.properties`.

Afon uses two types configuration properties in the config dir:

### Non-sensitive properties 

The `afon` properties contain non-sensitive configuration (bucket names, domain names, regions, Polaris credentials). 

### Sentitive properties

The `secret` properties contain sensitive configuration, such as credentials and secrets.

### Properties & users templates

**`afon.properties`** (non-sensitive):

- [afon.properties](../templates/config/afon.properties)
- [afon-aws.properties](../templates/config/afon-aws.properties)
- [afon-azure.properties](../templates/config/afon-azure.properties)
- [afon-gcp.properties](../templates/config/afon-gcp.properties)

**`secret.properties`** (sensitive):

- [secret.properties](../templates/config/secret.properties)
- [secret-aws.properties](../templates/config/secret-aws.properties)
- [secret-azure.properties](../templates/config/secret-azure.properties)
- [secret-gcp.properties](../templates/config/secret-gcp.properties)

**`users.csv`** (list of users, one per line):

- [users.csv](../templates/config/users.csv)

**`users-acl.csv`** (list of users ACL, one per line):

- [users-acl.csv](../templates/config/users-acl.csv)
