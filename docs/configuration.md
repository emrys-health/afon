# Configuration

## Config dir

All configuration is read from the config dir, assumed to be a child dir named `config` in the Afon home dir, 
which by default points to `$HOME/.afon`.  The default config dir is then `$HOME/.afon/config`.

The Afon home can be overridden by the `--home <afon-home>` option of `afon.sh`.   
In your IDE, you can override via the `-Dafon.home=<afon-home>` system property.

For example, if you need to support multiple envs, say a `dev` and a `stg` env, you can specify different Afon home dirs:
```shell
./afon.sh --home ~/.afon/envs/dev # config for dev will be in ~/.afon/envs/dev/config
./afon.sh --home ~/.afon/envs/stg # config for stg will be in ~/.afon/envs/stg/config
```
Equally, you could leave Afon home as the default and use symlinks to point to different configurations
```shell
ls -s ~/.afon/envs/dev/config ~/.afon/config # config symlink for dev 
ls -s ~/.afon/envs/stg/config ~/.afon/config # config symlink for stg 
```

Whichever Afon home you decide to use, you can initialise the config files by running:

```shell
./afon.sh -a config-init
```
This generates empty config files from templates in the config dir.  

**NOTE**: The config-init command assumes that the config dir does not exist and is created in the process. 
Once initialised, the config values need to be completed.

Once completed, you can validate the config files by running: 
```shell
./afon.sh -a config-validate
```
The validation checks that all the template properties have a corresponding non-empty property in the config dir.

You can also import the config files as a zip: 
```shell
./afon.sh -a config-import --config-source <path-to-config-zip>
```
This can be useful when sharing a pre-defined configuration.

**NOTE**: The config-import command assumes that the config dir does not exist and is created in the process.

## Properties

The properties configuration is currently file-based.

The configuration properties are cloud-specific but will include some properties that are common to all cloud providers.  

All cloud-specific configuration files will include the cloud reference in their names, e.g. `afon-aws.properties`.

Afon uses two types configuration properties in the config dir:

### Non-sensitive properties 

The `afon` properties contain non-sensitive configuration (bucket names, domain names, regions, Polaris credentials). 

Required non-sensitive properties:

| Property | Example | Description                     |
|---|---|---------------------------------|
| `CLUSTER_NAME` | `spark-cluster-wb2` | Name for your Kubernetes cluster |
| `POLARIS_ENDPOINT` | `localhost:8181` | URL of Polaris endpoint         |


Cloud-specific:

| Property | Example | Description |
|---|---|---|
| `AWS_REGION` | `eu-west-2` | AWS region |
| `AWS_S3_BUCKET_NAME` | `emrys-afon-bucket` | Primary S3 bucket |
| `AWS_S3_EXTERNAL_BUCKET_NAME` | `emrys-afon-bucket-2` | External S3 bucket |
| `AWS_POLARIS_DOMAIN_NAME` | `emryspolaris.click` | Domain for AWS Polaris |
| `AZURE_BUCKET` | `afonstorage` | Azure Blob container |
| `AZURE_STORAGE_ACCOUNT` | `afonstorageaccount` | Azure storage account |
| `AZURE_DOMAIN_NAME` | `emryspolarisazure.click` | Domain for Azure Polaris |
| `GCP_BUCKET_NAME` | `emrys_afon_core` | GCS bucket |
| `GCP_DOMAIN_NAME` | `emryspolarisgcp.click` | Domain for GCP Polaris |

### Sentitive properties

The `secret` properties contain sensitive configuration, such as credentials and secrets.

Required sensitive properties:

| Property | How to obtain                    |
|---|----------------------------------|
| `OAUTH2_CLIENT_ID` | Google Cloud Auth console        |
| `OAUTH2_CLIENT_SECRET` | Google Cloud Auth console        |
| `OAUTH2_COOKIE_SECRET` | `openssl rand -hex 16`           |
| `POLARIS_DB_PASSWORD` | Your external Postgres password  |
| `POLARIS_DB_HOST` | Your external Postgres host      |
| `POLARIS_ID` | Polaris client ID                |
| `POLARIS_SECRET` | Polaris client secret            |

Cloud-specific:

| Property | How to obtain |
|---|---|
| `AWS_ACCESS_KEY_ID` | AWS IAM console |
| `AWS_SECRET_ACCESS_KEY` | AWS IAM console |
| `AZURE_CLIENT_ID` | `az ad app list --display-name AfonMultiTenantApp --query "[].appId" -o tsv` |
| `AZURE_TENANT_ID` | `az ad app credential reset --id $AZURE_CLIENT_ID` |
| `AZURE_CLIENT_SECRET` | Same as above |
| `GOOGLE_APPLICATION_CREDENTIALS` | Contents of `~/.config/gcloud/application_default_credentials.json` with newlines removed |

**NOTE:**  When running in the JVM, you use the Polaris service account credentials in `secret-aws.properties`.
When running in CLI (e.g. tofu apply|destroy), you use the user admin credentials in ~/.aws/credentials.
**TODO:**  Rename `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` in `secret-aws.properties` to `AWS_POLARIS_*`

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

- [user.csv](../templates/config/users.csv)
