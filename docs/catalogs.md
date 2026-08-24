###  Deploy the catalogs

To deploy Polaris to AWS and Azure, populates catalogs, and runs a Spark smoke test, run:

```shell
./afon.sh -a exec --exec-class ops.SetupAwsFullStackWithAzurePolarisHostingExternalCatalogMain
```

It takes approximately 45 minutes.


### Configure Polaris catalogs

When a new Polaris instance starts, it has no catalogs. You can create all catalogs for a domain at once:
```shell
./afon.sh -a exec --exec-class ops.ManagePolarisCatalogsMain --exec-args "create emryspolaris.click"
```
This creates catalogs for AWS, GCP, Azure, and their external/forbidden variants in a single call.

To configure a single catalog on a specific endpoint:
```shell
./afon.sh -a exec --exec-class ops.ConfigureEndpointPolarisMain --exec-args "AZURE" --exec-envs "POLARIS_ENDPOINT=emryspolarisazure.click"
```

### Cross-cloud querying

Cross-cloud querying works through Polaris catalog federation. The key configuration is:

```
POLARIS_FEATURES_ENABLE_CATALOG_FEDERATION=true
POLARIS_FEATURES_SUPPORTED_CATALOG_CONNECTION_TYPES="ICEBERG_REST,HIVE"
```

When enabled, a Polaris instance in one cloud (e.g. AWS) can be configured with an **external catalog** that points to a Polaris instance in another cloud (e.g. Azure). When Spark queries the external catalog, the AWS Polaris proxies the request to the Azure Polaris, which vends Azure credentials. Spark then reads directly from Azure Blob Storage — no data passes through the Polaris servers.

The `AfonCloud` defines the catalog types:

- `AWS` — internal catalog, data in S3
- `GCP` — internal catalog, data in GCS
- `AZURE` — internal catalog, data in Azure Blob
- `AZURE_EXTERNAL` — external/federated catalog pointing to another Polaris
- `GCP_EXTERNAL` — same for GCP
- `{AWS|AZURE|GCP}_FORBIDDEN` — catalogs pointing at buckets the user should NOT have access to (used for security testing)



### Diagnosing Polaris issues

To check table status:

```shell
TOKEN=$(curl -X POST "https://emryspolaris.click/api/catalog/v1/oauth/tokens" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=<client_id>&client_secret=<client_secret>&scope=PRINCIPAL_ROLE:ALL" \
  | jq -r '.access_token')

curl -X GET "https://emryspolaris.click/api/catalog/v1/aws/namespaces/acceptancetests/tables/YOUR_TABLE" \
  -H "Authorization: Bearer ${TOKEN}" | jq
```
