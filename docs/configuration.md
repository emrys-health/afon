# Configuration

## Entry points

**NOTE:** The commands below apply to all [entry points](afon_entry_points.md).

## Config dir

All configuration is read from the config dir, assumed to be a child dir named `config` in the Afon home dir,
which by default points to `$HOME/.afon`.  The default config dir is then `$HOME/.afon/config`.

## Config Env

Afon supports different envs for configuration, eg to allow for an `dev` and a `stg` env.  

### Command config-init
You can initialise the config files for a given env by running:

```shell
afon-cli.sh config-init -e <env>
```
This copies config files from templates to the `config-<env>` dir and creates a symlink `config -> config-<env>`.  

**NOTE**: The config-init command will create a new config dir, if not found, but it also can work with an existing config dir.
However, in case of an existing config dir, any existing files will not be overwritten and will need to be removed manually.

Once initialised, the config values need to be filled in.

### Command config-switch

You can switch between config envs using the `config-switch` action:
```shell
afon-cli.sh config-switch -e <env> # config symlink for <env> 
```
This will create or move the symlink `config -> config-<env>`.

**NOTE**: If you're using multiple config envs, the config dir must be a symlink and not a dir.  

### Command config-validate 

Once completed, you can validate the config files by running: 
```shell
afon-cli.sh config-validate [-c <cloud>]
```
The validation checks that all the template properties have a corresponding non-empty property in the config dir.
The optional `-c <cloud>` allows you to check only the cloud-specific config. 

### Command config-import and config-export

You can also import or export the config files as a zip: 
```shell
afon-cli.sh config-{import|export} <path-to-config-zip> -e <env>
```
This can be useful when sharing a pre-defined configuration.

**NOTE**: The config-import command assumes that the `config-<env>` dir does not exist and is created in the process.  
If a config dir is already present, it will stop.

### Command config-status

To show the status of the current config, you can use the `config-status` action:
```shell
afon-cli.sh config-status
```
If found, it will output the content of the file `afon.md` in the config dir, which can be used to 
provide information to the user of the config content.  It will also list the files in the config dir.

## Properties

The properties configuration is currently file-based.

The configuration properties are cloud-specific but will include some properties that are common to all cloud providers.  

All cloud-specific configuration files will include the cloud reference in their names, e.g. `afon-aws.properties`.

Afon uses two types configuration properties in the config dir:

**`afon.properties`**: the properties containing non-sensitive configuration, such as cluster name, bucket names, domain names, regions, endpoints:

- [afon.properties](../templates/config/afon.properties)
- [afon-aws.properties](../templates/config/afon-aws.properties)
- [afon-azure.properties](../templates/config/afon-azure.properties)
- [afon-gcp.properties](../templates/config/afon-gcp.properties)

**`catalogs.properties`**: the properties containing catalogs configuration:

- [catalogs.properties](../templates/config/catalogs.properties)
- [catalogs-aws.properties](../templates/config/catalogs-aws.properties)
- [catalogs-azure.properties](../templates/config/catalogs-azure.properties)
- [catalogs-gcp.properties](../templates/config/catalogs-gcp.properties)

- **`secret.properties`**: the properties containing sensitive configuration, such as credentials and secrets:

- [secret.properties](../templates/config/secret.properties)
- [secret-aws.properties](../templates/config/secret-aws.properties)
- [secret-azure.properties](../templates/config/secret-azure.properties)
- [secret-gcp.properties](../templates/config/secret-gcp.properties)

**NOTE:**  When running in the JVM, you use the Polaris service account credentials in `secret-aws.properties`.
When running in CLI (e.g. tofu apply|destroy), you use the user admin credentials in ~/.aws/credentials.


## Property value interpolation

Property values can be interpolated to minimise duplication.

For example, in `afon.properties`:
```shell
ENV_NAME=dev
CLUSTER_NAME=${ENV_NAME}-cluster
OIDC_ISSUER_URL=https://${ENV_NAME}-oauth.click/realms/polaris
```
And in `afon-aws.properties`:
```shell
AWS_POLARIS_DOMAIN_NAME=${ENV_NAME}-polaris.click # the reference can be defined in common file
```
Circular dependencies and undefined references are caught and result in failure to load properties.

### Users 

Afon supports two user configuration files in the config dir:

**`users.csv`** (list of users, one per line):

- [users.csv](../templates/config/users.csv)

**`users-acl.csv`** (list of users ACL, one per line):

- [users-acl.csv](../templates/config/users-acl.csv)
