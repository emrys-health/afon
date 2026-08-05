# Connect Snowflake to cross-cloud data via Polaris

Afon can configure Snowflake to read Iceberg tables from any cloud via Polaris as a catalog integration.

The following commands deploy the AWS full stack, write test data to the target cloud, then create a Snowflake catalog integration and iceberg table that reads through Polaris.

## Read AWS data via AWS Polaris:**

```shell
./afon.sh -a exec --exec-class ops.DeploySnowflakeReadingAwsViaAwsMain  
```

## Read GCP data via AWS Polaris

```shell
./afon.sh -a exec --exec-class ops.DeploySnowflakeReadingGcpViaAwsMain  
```

## Read Azure data via AWS Polaris:**

```shell
./afon.sh -a exec --exec-class ops.DeploySnowflakeReadingAzureViaAwsMain  
```

## Write data from Snowflake to Azure blob storage

In summary:

1. Create a storage integration in Snowflake pointing at Azure
2. Create an external volume with Azure storage details
3. Click the consent URL output by `DESCRIBE EXTERNAL VOLUME` to grant Snowflake access
4. Assign the Storage Blob Data Contributor role to the Snowflake app in Azure
5. Create an Iceberg table and insert data

The deployment is automated via:

```shell
./afon.sh -a exec --exec-class ops.DeploySnowflakeWritingAzureViaAwsMain  
```

### Connecting to Snowflake

### Via a service account

If you didn't use a service account, you would have to open a browser.

```sql
CREATE USER ETL_SERVICE_USER
PASSWORD = 'temporary_password'
LOGIN_NAME = 'ETL_SERVICE_USER'
DEFAULT_WAREHOUSE = 'ETL_WH'
DEFAULT_ROLE = 'ETL_ROLE'
MUST_CHANGE_PASSWORD = FALSE;

ALTER USER ETL_SERVICE_USER
SET RSA_PUBLIC_KEY='-----BEGIN PUBLIC KEY-----
Yadda, yadda, yadda
-----END PUBLIC KEY-----
';
```

Generate the key using:

```shell
openssl genrsa 2048 | openssl pkcs8 -topk8 -v2 des3 -inform PEM -out private_key.p8 -nocrypt
openssl rsa -in private_key.p8 -pubout -out public_key.pub
```

and it's the contents of that `public_key.pub` file that you put in `RSA_PUBLIC_KEY`.
The same public key is then to be used in the client code.
See [SnowflakeConnectionSmoke.java](../afon-ops/src/main/java/health/emrys/afon/ops/smoke/SnowflakeConnectionSmoke.java) as an example.

You then need to give the SA access to a table.
In this example, we let it talk to `aws_acceptance_Test`

```sql      
GRANT USAGE ON WAREHOUSE COMPUTE_WH TO USER ETL_SERVICE_USER;
GRANT USAGE ON DATABASE SNOWFLAKE_LEARNING_DB TO USER ETL_SERVICE_USER;
GRANT SELECT ON TABLE SNOWFLAKE_LEARNING_DB.PUBLIC.aws_acceptance_Test TO USER ETL_SERVICE_USER;
GRANT ROLE ACCOUNTADMIN TO USER ETL_SERVICE_USER;
```

### Writing from Snowflake to Azure

In Snowflake, run:

```sql
CREATE STORAGE INTEGRATION azure_int
  TYPE = EXTERNAL_STAGE
  STORAGE_PROVIDER = AZURE
  ENABLED = TRUE -- abfss://afonstorage@afonstorageaccount.dfs.core.windows.net
  STORAGE_ALLOWED_LOCATIONS = ('azure://afonstorageaccount.blob.core.windows.net/afonstorage')
  AZURE_TENANT_ID='316c25d7-0cae-4757-af4d-9e6e091be7fd';

DESC STORAGE INTEGRATION azure_int;

CREATE EXTERNAL VOLUME my_external_volume
  STORAGE_LOCATIONS = (
    (
      NAME = 'azure_loc'
      STORAGE_PROVIDER = 'AZURE'
      STORAGE_BASE_URL = 'azure://afonstorageaccount.blob.core.windows.net/afonstorage/'
      -- STORAGE_INTEGRATION = azure_int
      AZURE_TENANT_ID='316c25d7-0cae-4757-af4d-9e6e091be7fd'
    )
  );

DESCRIBE EXTERNAL VOLUME my_external_volume; 
```
This last command will print a "consent" URL.
Click it adn login to the cloud you want to talk to.
It adds a principle to our cloud.
This is an application you can see [here](https://portal.azure.com/#view/Microsoft_AAD_IAM/StartboardApplicationsMenuBlade/~/Overview)
It will have a name similar to the `AZURE_MULTI_TENANT_APP_NAME` in teh `DESCRIBE` above.
Make a note of the `Application ID`.
Let's call it `SNOWFLAKE_APP_ID`

Then run:
```shell
RESOURCE_GROUP=afon_resource_group
STORAGE_ACCOUNT_NAME=afonstorageaccount
SCOPE=$(az storage account show --name $STORAGE_ACCOUNT_NAME --resource-group $RESOURCE_GROUP --query id --output tsv)
az role assignment create --assignee $SNOWFLAKE_APP_ID --role "Storage Blob Data Contributor" --scope $SCOPE
```
Once this has propagated (it might take a few minutes), return to Snowflake and run:
```sql
USE DATABASE SNOWFLAKE_LEARNING_DB;

CREATE OR REPLACE ICEBERG TABLE my_azure_iceberg_table (
    user_id INT,
    event_name STRING,
    event_date DATE
)
  EXTERNAL_VOLUME = 'MY_EXTERNAL_VOLUME'
  CATALOG = 'SNOWFLAKE'
  BASE_LOCATION = 'my_project_folder/';

INSERT INTO my_azure_iceberg_table (user_id, event_name, event_date)
VALUES
    (1, 'Alice', CURRENT_TIMESTAMP()),
    (2, 'Bob', CURRENT_TIMESTAMP());
```
Then you should be able to see somthing in that folder, ie:
```shell
az storage blob list --account-name afonstorageaccount --container-name afonstorage --output table --prefix my_project_folder.U0rMUke6/
```