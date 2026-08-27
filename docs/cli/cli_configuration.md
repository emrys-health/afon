# CLI Configuration

## Config dir

All configuration is read from the config dir, assumed to be a child dir named `config` in the Afon home dir, 
which by default points to `$HOME/.afon`.  The default config dir is then `$HOME/.afon/config`.

The Afon home can be overridden by the `AFON_HOME` env. 

### Init config
Whichever Afon home you decide to use, you can initialise the config files by running:

```shell
$AFON_CLI_DIR/afon-cli-scripts.sh config/init.sh
```
This copies empty config files from templates to the config dir.  

**NOTE**: The config-init command will create a new config dir, if not found, but it also can work with an existing config dir.
However, in case of an existing config dir, any existing files will not be overwritten and will need to be removed manually.

Once initialised, the config values need to be completed.

### Validate config

Once completed, you can validate the config files by running: 
```shell
[AFON_CLOUD=<cloud>] $AFON_CLI_DIR/afon-cli-scripts.sh config/validate.sh
```
The validation checks that all the template properties have a corresponding non-empty property in the config dir.
optional `AFON_CLOUD=<cloud>` allows you to check only the cloud-specific config. 

**IMPORTANT:** Some functionality still requires the config files for all clouds to be present, although only the
values for the cloud you are using need be completed.  There is [open ticket to fix issue](https://github.com/emrys-health/afon-core/issues/159).  
The recommended workaround is to init without specifying the cloud and to validate specifying it.

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
