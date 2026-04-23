# afon-core
Core project required to deploy infrastructure across clouds that enables secure cross cloud querying

## Developer Prerequisites

You'll need to have the following installed:
- AWS CLI
- Azure CLI
- GCloud CLI
- Docker
- Maven
- Java 17
- OpenTofu
- Kubernetes CLI (kubectl)

## User Prerequisites

You'll need to have the following installed:
- Docker
- Cloud provider CLI (AWS, Azure, GCP)

## Infrastructure

#### Domain names

To facilitate cross cloud querying, we need to be able to access the Polaris servers from different clouds.
To do this, we need to set up domain names that point to the respective cloud's ingress.

```
emryspolaris.click
emryspolarisgcp.click
emryspolarisazure.click
```
This is not currently automated, so you'll need to set up the domain names manually. 

### Running the demo

# Running the scripts

You'll need to define the environment variables first with:
```shell
. ./afon.properties
```

```
CLUSTER_NAME=will-spark-cluster

AWS_S3_EXTERNAL_BUCKET_NAME=
AWS_S3_BUCKET_NAME=
AWS_S3_BUCKET_NAME_FORBIDDEN=
AWS_S3_ROLE_ARN=a
AWS_REGION=
AWS_POLARIS_DOMAIN_NAME=
AWS_JUPYTER_DOMAIN=
AWS_ACCOUNT_ID=
AWS_ZONE_ID=

GLUE_S3_ROLE_ARN=
GLUE_CATALOG_NAME=

GCP_BUCKET_NAME=
GCP_BUCKET_NAME_FORBIDDEN=
GCP_EXTERNAL_BUCKET_NAME=
GCP_SERVICE_ACCOUNT=

AZURE_EXTERNAL_BUCKET=
AZURE_BUCKET_FORBIDDEN=
AZURE_BUCKET=
AZURE_STORAGE_ACCOUNT=
AZURE_SUBSCRIPTION=
AZURE_REGION=
AZURE_RESOURCE_GROUP=
AZURE_AKS_RESOURCE_GROUP=
AZURE_DOMAIN_NAME=

POLARIS_ID=
POLARIS_SECRET=
POLARIS_ENDPOINT=
```

Run the following to build and install the project:

```shell
mvn clean install
```

```shell
cd afon-infra
```

```shell
mvn  compile -Dexec.mainClass=health.emrys.afon.demo.DocumentExternalCatalogsMain  exec:java
```

Once that has run you should be able to open the readme for your infrastructure setup at 

```shell
cat afon-core/afon-infra/target/aws_azure_external_catalogs.md
```

and run 

```shell
mvn  compile -Dexec.mainClass=health.emrys.afon.demo.DeployExternalCatalogsMain  exec:exec -Pdemo
```
This will deploy the external catalogs to the respective clouds, it currently takes about 45 minutes to deploy along side some integration tests

You will be able to login via your own domain name e.g. https://emryspolaris.click


