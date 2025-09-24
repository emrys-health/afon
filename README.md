# afon-core
core infrastructure required to deploy infrastructure across clouds that enables secure cross cloud querying

# Running the scripts

You'll need to define the environment variables first with:
```shell
. ./afon.properties
```

The scripts then live in `./scripts` for each cloud provider.

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
````