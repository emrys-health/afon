# Acceptance tests

The acceptance tests in `afon-acceptance` verify that cross-cloud access works correctly — including that access to "forbidden" buckets is properly denied.

The acceptance tests can be run against all cloud providers or just a single one:
```shell
./afon.sh -a build -p acceptance # builds components running acceptance tests for all cloud providers
./afon.sh -a build -p acceptance -c <cloud> # builds components running acceptance tests for cloud <cloud>
```
## Acceptance test flow

The `AbstractAccessSpec` demonstrates the full lifecycle:

1. **Create catalog** — POST to `/api/management/v1/catalogs` with cloud-specific JSON
2. **Verify catalog** — GET to confirm Polaris acknowledges it
3. **Grant catalog admin** — PUT to assign admin roles
4. **Create namespace** — `CREATE NAMESPACE IF NOT EXISTS aws.acceptancetests`
5. **Write data** — `spark.createDataFrame(...).writeTo(tableNameFQN).create()`
6. **Read data** — `spark.read.table(tableNameFQN)` and verify row count
7. **Register table** — GET to confirm Polaris knows about the table
8. **Cross-cloud join** — `SELECT * FROM aws_table AS x INNER JOIN gcp_table AS y ON x.id = y.id`

Test specs include:

- [AwsAccessSpec](../afon-acceptance/src/test/scala/health/emrys/afon/acceptance/aws/AwsAccessSpec.scala) — write and read Iceberg tables via AWS Polaris to s3
- [AwsAccessForbiddenSpec](../afon-acceptance/src/test/scala/health/emrys/afon/acceptance/aws/AwsAccessForbiddenSpec.scala) — verify that access to forbidden buckets is denied for AWS
- [AzureAccessSpec](../afon-acceptance/src/test/scala/health/emrys/afon/acceptance/azure/AzureAccessSpec.scala) — write and read via AWS Polaris to Azure
- [AzureAccessForbiddenSpec](../afon-acceptance/src/test/scala/health/emrys/afon/acceptance/azure/AzureAccessForbiddenSpec.scala) — verify that access to forbidden buckets is denied for Azure
- [GcpAccessSpec](../afon-acceptance/src/test/scala/health/emrys/afon/acceptance/gcp/GcpAccessSpec.scala) — write and read via AWS Polaris to GCP
- [GcpAccessForbiddenSpec](../afon-acceptance/src/test/scala/health/emrys/afon/acceptance/gcp/GcpAccessForbiddenSpec.scala) — verify that access to forbidden buckets is denied for GCP
- [ExternalGlueTableSpec](../afon-acceptance/src/test/scala/health/emrys/afon/acceptance/aws/ExternalGlueTableSpec.scala) — cross-cloud: federated Glue catalog access
- [AzureExternalTableSpec](../afon-acceptance/src/test/scala/health/emrys/afon/acceptance/azure/AzureExternalTableSpec.scala) — cross-cloud: Spark talks to AWS Polaris which federates to Azure Polaris
- [GcpExternalTableSpec](../afon-acceptance/src/test/scala/health/emrys/afon/acceptance/gcp/GcpExternalTableSpec.scala) — cross-cloud: Spark talks to AWS Polaris which federates to GCP Polaris

