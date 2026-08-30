# Configuration

## Config dir

All configuration is read from the config dir, assumed to be a child dir named `config` in the Afon home dir, 
which by default points to `$HOME/.afon`.  The default config dir is then `$HOME/.afon/config`.

The Afon home can be overridden by the `--home <afon-home>` option of `afon.sh`.   
In your IDE, you can override via the `-Dafon.home=<afon-home>` system property.

### Command config-init
Whichever Afon home you decide to use, you can initialise the config files by running:

```shell
./afon.sh -a config-init
```
This copies empty config files from templates to the config dir.  

**NOTE**: The config-init command will create a new config dir, if not found, but it also can work with an existing config dir.
However, in case of an existing config dir, any existing files will not be overwritten and will need to be removed manually.

Once initialised, the config values need to be completed.

### Command config-validate 

Once completed, you can validate the config files by running: 
```shell
./afon.sh -a config-validate [-c <cloud>]
```
The validation checks that all the template properties have a corresponding non-empty property in the config dir.
The optional `-c <cloud>` allows you to check only the cloud-specific config. 

**IMPORTANT:** Some functionality still requires the config files for all clouds to be present, although only the
values for the cloud you are using need be completed.  There is [open ticket to fix issue](https://github.com/emrys-health/afon-core/issues/159).  
The recommended workaround is to init without specifying the cloud and to validate specifying it.

### Command config-import and config-export

You can also import or export the config files as a zip: 
```shell
./afon.sh -a config-{import|export} --config-zip <path-to-config-zip>
```
This can be useful when sharing a pre-defined configuration.

**NOTE**: The config-import command assumes that the config dir does not exist and is created in the process.  
If a config dir is already present, it will stop.

### Command config-switch

To support multiple config envs, say a `dev` and a `stg` env, you can use the `config-switch` action:
```shell
./afon.sh -a config-switch --config-env {dev|stg} # config symlink for <env> 
```
This will create or move the symlink `<afon-home>/config` pointing to `<afon-home>/config-<env>`.  

**NOTE**: If you're using multiple config envs, the config dir must be a symlink and not a dir.  
Create config env dirs `<afon-home>/config-<env>` and ensure there is no config dir.   
The config-switch action will remove any existing symlink.

### Command config-status

To show the status of the current config, you can use the `config-status` action:
```shell
./afon.sh -a config-status
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

- **`secret.properties`**: the properties containing sensitive configuration, such as credentials and secrets:

- [secret.properties](../templates/config/secret.properties)
- [secret-aws.properties](../templates/config/secret-aws.properties)
- [secret-azure.properties](../templates/config/secret-azure.properties)
- [secret-gcp.properties](../templates/config/secret-gcp.properties)

**NOTE:**  When running in the JVM, you use the Polaris service account credentials in `secret-aws.properties`.
When running in CLI (e.g. tofu apply|destroy), you use the user admin credentials in ~/.aws/credentials.

**TODO:**  Rename `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` in `secret-aws.properties` to `AWS_POLARIS_*`

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
AWS_S3_BUCKET_NAME=${ENV_NAME}-bucket
AWS_S3_BUCKET_NAME_TRINO=${AWS_S3_BUCKET_NAME}-trino # use of a property previously interpolated
```
Circular dependencies and undefined references are caught and result in failure to load properties.

### Users 

Afon supports two user configuration files in the config dir:

**`users.csv`** (list of users, one per line):

- [users.csv](../templates/config/users.csv)

**`users-acl.csv`** (list of users ACL, one per line):

- [users-acl.csv](../templates/config/users-acl.csv)
