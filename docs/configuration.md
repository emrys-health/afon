# Configuration

## Properties

The properties configuration is currently file-based.

The configuration properties are cloud-specific but will include some properties that are common to all cloud providers.  

All cloud-specific configuration files will include the cloud reference in their names, e.g. `afon-aws.properties` and `env-aws.properties`.

The config dir is assumed to be a child dir of the AfonHome dir, which by default points to $HOME/.afon. 
The AfonHome can be overridden by the `--home <afon-home>` option of `afon.sh`.
The default config dir is then `$HOME/.afon/config`.

To initialise the config files execute:

```shell
./afon.sh -a exec -t script --exec-script config/initialise.sh
```
This generates empty config files from templates in the config dir.  

**NOTE**: Once initialised, the config values need to be completed. 

Afon uses two types configuration properties in the config dir:

### Non-sensitive properties 

The `afon` properties contain non-sensitive configuration (bucket names, domain names, regions, Polaris credentials). 

Required non-sensitive properties:

| Property | Example | Description |
|---|---|---|
| `CLUSTER_NAME` | `spark-cluster-wb2` | Name for your Kubernetes cluster |
| `POLARIS_ID` | `root` | Polaris client ID |
| `POLARIS_SECRET` | `s3cr3t` | Polaris client secret |

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

The `env` properties contain sensitive configuration and should be committed to source control.

Required sensitive properties:

| Property | How to obtain |
|---|---|
| `OAUTH2_CLIENT_ID` | Google Cloud Auth console |
| `OAUTH2_CLIENT_SECRET` | Google Cloud Auth console |
| `OAUTH2_COOKIE_SECRET` | `openssl rand -hex 16` |
| `POLARIS_DB_PASSWORD` | Your external Postgres password |
| `POLARIS_DB_HOST` | Your external Postgres host |

Cloud-specific:

| Property | How to obtain |
|---|---|
| `AWS_ACCESS_KEY_ID` | AWS IAM console |
| `AWS_SECRET_ACCESS_KEY` | AWS IAM console |
| `AZURE_CLIENT_ID` | `az ad app list --display-name AfonMultiTenantApp --query "[].appId" -o tsv` |
| `AZURE_TENANT_ID` | `az ad app credential reset --id $AZURE_CLIENT_ID` |
| `AZURE_CLIENT_SECRET` | Same as above |
| `GOOGLE_APPLICATION_CREDENTIALS` | Contents of `~/.config/gcloud/application_default_credentials.json` with newlines removed |

**NOTE:**  When running in the JVM, you use the Polaris service account credentials in `env-aws.properties`.
When running in CLI (e.g. tofu apply|destroy), you use the user admin credentials in ~/.aws/credentials.
**TODO:**  Rename `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` in `env-aws.properties` to `AWS_POLARIS_*`

### Properties templates

**`afon.properties`** (non-sensitive):

```properties
### Global
CLUSTER_NAME=your-production-cluster
POLARIS_ID=root
POLARIS_SECRET=CHANGE_ME_TO_A_STRONG_SECRET
POLARIS_ENDPOINT=your-polaris-domain.click
USER_POLARIS_ID=root
USER_POLARIS_SECRET=CHANGE_ME_TO_A_STRONG_SECRET
JUPYTER_VNC_PASSWORD=CHANGE_ME
OIDC_ISSUER_URL=https://accounts.google.com
OIDC_EMAIL_DOMAIN=yourdomain.com

### AWS
AWS_S3_EXTERNAL_BUCKET_NAME=your-org-afon-external
AWS_S3_BUCKET_NAME=your-org-afon-primary
AWS_S3_BUCKET_NAME_FORBIDDEN=your-org-afon-forbidden
AWS_S3_ROLE_ARN=arn:aws:iam::YOUR_ACCOUNT_ID:role/S3ReadWriteRole
AWS_REGION=eu-west-2
AWS_POLARIS_DOMAIN_NAME=your-polaris.yourdomain.com
AWS_JUPYTER_DOMAIN=your-jupyter.yourdomain.com
AWS_ACCOUNT_ID=YOUR_ACCOUNT_ID
AWS_ZONE_ID=YOUR_ZONE_ID
AWS_POLARIS_DNS_ZONE=YOUR_DNS_ZONE_ID
AWS_JUPYTER_DNS_ZONE=YOUR_JUPYTER_DNS_ZONE_ID
AWS_JUPYTER_CERT_ARN=arn:aws:acm:REGION:ACCOUNT:certificate/CERT_ID
AWS_POLARIS_CERT_ARN=arn:aws:acm:REGION:ACCOUNT:certificate/CERT_ID

### Azure
AZURE_EXTERNAL_BUCKET=yourstorage-external
AZURE_BUCKET_FORBIDDEN=yourstorage-forbidden
AZURE_BUCKET=yourstorage
AZURE_STORAGE_ACCOUNT=yourstorageaccount
AZURE_SUBSCRIPTION=YOUR_SUBSCRIPTION_ID
AZURE_REGION=uksouth
AZURE_RESOURCE_GROUP=your_resource_group
AZURE_DOMAIN_NAME=your-polaris-azure.yourdomain.com
AZURE_JUPYTER_DOMAIN_NAME=your-jupyter-azure.yourdomain.com

### GCP
GCP_BUCKET_NAME=your_org_afon
GCP_BUCKET_NAME_FORBIDDEN=your_org_afon_forbidden
GCP_EXTERNAL_BUCKET_NAME=your_org_afon_external
GCP_SERVICE_ACCOUNT=your-sa@your-project.iam.gserviceaccount.com
GCP_DOMAIN_NAME=your-polaris-gcp.yourdomain.com

### Snowflake
SNOWFLAKE_JDBC_URL=jdbc:snowflake://YOUR_ACCOUNT.snowflakecomputing.com/
```

**`env.properties`** (sensitive — never commit):

```properties
# AWS
AWS_ACCESS_KEY_ID=YOUR_ACCESS_KEY
AWS_SECRET_ACCESS_KEY=YOUR_SECRET_KEY

# Azure
AZURE_CLIENT_ID=YOUR_CLIENT_ID
AZURE_TENANT_ID=YOUR_TENANT_ID
AZURE_CLIENT_SECRET=YOUR_CLIENT_SECRET
DATABRICKS_TOKEN=YOUR_DATABRICKS_TOKEN

# GCP
GOOGLE_APPLICATION_CREDENTIALS=CONTENTS_OF_APPLICATION_DEFAULT_CREDENTIALS_JSON_WITH_NEWLINES_REMOVED

# OAuth2 (for Jupyter/Guacamole)
OAUTH2_CLIENT_ID=YOUR_GOOGLE_OAUTH_CLIENT_ID
OAUTH2_CLIENT_SECRET=YOUR_GOOGLE_OAUTH_CLIENT_SECRET
OAUTH2_COOKIE_SECRET=GENERATED_WITH_OPENSSL_RAND_HEX_16

# External Postgres for Polaris metadata
POLARIS_DB_PASSWORD=YOUR_STRONG_DB_PASSWORD
POLARIS_DB_HOST=YOUR_POSTGRES_HOST

# Snowflake
SNOWFLAKE_PRIVATE_KEY=YOUR_SNOWFLAKE_PRIVATE_KEY
```

#### Users

- **`users.csv`** — list of users, one per line. Copy the example and fill in your values:

```shell
cd config
cp users.csv.example users.csv
```


