# Talking to Snowflake

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

You then need to give the SA access to a table. 
In this example, we let it talk to `aws_acceptance_Test`

```sql      
GRANT USAGE ON WAREHOUSE COMPUTE_WH TO USER ETL_SERVICE_USER;
GRANT USAGE ON DATABASE SNOWFLAKE_LEARNING_DB TO USER ETL_SERVICE_USER;
GRANT SELECT ON TABLE SNOWFLAKE_LEARNING_DB.PUBLIC.aws_acceptance_Test TO USER ETL_SERVICE_USER;
GRANT ROLE ACCOUNTADMIN TO USER ETL_SERVICE_USER;
```

