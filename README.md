# afon-core
Core project required to deploy infrastructure across clouds that enables secure cross cloud querying

# Running the scripts

You'll need to define the environment variables first with:
```shell
. ./afon.properties
```

The scripts then live in `./scripts` for each cloud provider.

# Configuring the environment

See the [infra notes](afon-infra/README.md). 

Note that the Spark Docker image has been slightly modified (see `scripts/docker/spark/Dockerfile`).
This is to ensure that the samve verion of Python (3.11) is used as the Jupyter notebook.

Note that this is a one-off job of bootstrapping the Postgres database where Polaris persists the metadata.
You need to run:
```shell
docker run --rm -it   --env="polaris.persistence.type=relational-jdbc"   --env="quarkus.datasource.username=postgres"   --env="quarkus.datasource.password=$POLARIS_DB_PASSWORD"   --env="quarkus.datasource.jdbc.url=jdbc:postgresql://$POLARIS_DB_HOST:5432/polaris_db_aws"   apache/polaris-admin-tool:latest bootstrap -r POLARIS -c POLARIS,root,$POLARIS_SECRET
docker run --rm -it   --env="polaris.persistence.type=relational-jdbc"   --env="quarkus.datasource.username=postgres"   --env="quarkus.datasource.password=$POLARIS_DB_PASSWORD"   --env="quarkus.datasource.jdbc.url=jdbc:postgresql://$POLARIS_DB_HOST:5432/polaris_db_azure" apache/polaris-admin-tool:latest bootstrap -r POLARIS -c POLARIS,root,$POLARIS_SECRET
docker run --rm -it   --env="polaris.persistence.type=relational-jdbc"   --env="quarkus.datasource.username=postgres"   --env="quarkus.datasource.password=$POLARIS_DB_PASSWORD"   --env="quarkus.datasource.jdbc.url=jdbc:postgresql://$POLARIS_DB_HOST:5432/polaris_db_gcp"   apache/polaris-admin-tool:latest bootstrap -r POLARIS -c POLARIS,root,$POLARIS_SECRET
```
See below for the value of those environment variables.

# Running the Java code

The project is built with Maven using:

```shell
mvn clean install
```

*NOTE* that this will run the integration tests that talk to your cloud providers.
This means you must have an `env.properties` file in the top level directory of the project.
You must copy-and-paste the credentials (or the paths to them) into this file manually. 
It *must not be checked in* for security reasons. 
The file `env.properties` is listed in .gitignore to try and prevent this.


This is the (redacted) contents of my own file so you know what to add:

```shell
~/Code/Emrys/afon-core$ cat env.properties | perl -pe s/=.*/=/g
# AWS
AWS_ACCESS_KEY_ID=
AWS_SECRET_ACCESS_KEY=

# This is your ~/.config/gcloud/application_default_credentials.json with the newlines removed
GOOGLE_APPLICATION_CREDENTIALS=

# Get AZURE_CLIENT_ID with: az ad app list --display-name AfonMultiTenantApp --query "[].appId" -o tsv
AZURE_CLIENT_ID=
# this was output with: az ad app credential reset --id $AZURE_CLIENT_ID
AZURE_TENANT_ID=
AZURE_CLIENT_SECRET=
***REMOVED***

# Infra properties
OAUTH2_CLIENT_ID=
OAUTH2_CLIENT_SECRET=
***REMOVED***
POLARIS_DB_PASSWORD=
POLARIS_DB_HOST=
````


### Running the demo

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
