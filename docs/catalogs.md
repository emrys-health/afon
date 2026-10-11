###  Deploy the catalogs

To deploy Polaris to AWS and Azure, populate catalogs, and run a Spark smoke test, set up both stacks and run the scenario that
federates AWS to Azure, with the flow `cross-cloud-azure`:

```shell
afon-cli.sh manage-stack run cross-cloud-azure --replace
```

It is a [flow](stacks.md#run-a-flow-of-stacks) of three items, which can also be run one at a time:

```shell
afon-cli.sh manage-stack setup aws-full --replace
afon-cli.sh manage-stack setup azure-polaris --replace
afon-cli.sh manage-stack run aws-full external-azure
```

It takes approximately 45 minutes.


### Configure Polaris catalogs

When a new Polaris instance starts, it has no catalogs. You can create all catalogs at once for `aws` cloud:
```shell
afon-cli.sh manage-polaris-catalogs create aws -lc INFO
```
This creates catalogs for `{aws,azure,gcp}` and their external/forbidden variants in a single call.

Creating leaves any catalog that already exists as it is, with its roles and tables, so it is safe to rerun and logs no
warnings for the catalogs that exist. To start again from scratch, recreate them, which deletes
them all first:
```shell
afon-cli.sh manage-polaris-catalogs recreate aws -lc INFO
```
Deleting a catalog, with `delete` or `recreate`, deletes its roles first, as Polaris does not drop a catalog that has
them, and its namespaces and tables, except for an external catalog, a federation to another Polaris whose namespaces
and tables are left alone. The catalog is given by its id, as for creating it.

To configure a single catalog, eg `azure_external`, for `azure` cloud:
```shell
afon-cli.sh manage-polaris-catalogs create azure azure_external -lc INFO
```

### Cross-cloud querying

Cross-cloud querying works through Polaris catalog federation. The key configuration is:

```
POLARIS_FEATURES_ENABLE_CATALOG_FEDERATION=true
POLARIS_FEATURES_SUPPORTED_CATALOG_CONNECTION_TYPES="ICEBERG_REST,HIVE"
```

When enabled, a Polaris instance in one cloud (e.g. AWS) can be configured with an **external catalog** that points to a Polaris instance in another cloud (e.g. Azure). When Spark queries the external catalog, the AWS Polaris proxies the request to the Azure Polaris, which vends Azure credentials. Spark then reads directly from Azure Blob Storage — no data passes through the Polaris servers.

The `AfonCatalog` defines the catalog types:

- `aws` — internal catalog, data in S3
- `gcp` — internal catalog, data in GCS
- `azure` — internal catalog, data in Azure Blob
- `{azure|gcp}_external` — external/federated catalog pointing to another Polaris
- `{aws|azure|gcp}_forbidden` — catalogs pointing at buckets the user should NOT have access to (used for security testing)


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
