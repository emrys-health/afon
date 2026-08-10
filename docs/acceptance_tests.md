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

## Continuous integration

The [Acceptance tests (Linux)](../.github/workflows/acceptance-tests-linux.yml) workflow runs the same
`./afon.sh -a build -p acceptance` command on the self-hosted Linux runner. It is triggered on push and
pull request to `main`, nightly at 03:00 UTC, and manually via `workflow_dispatch` — the manual trigger
takes a `cloud` input (`all`, `aws`, `azure` or `gcp`) which maps to the `--cloud` flag.

Because the specs write to shared buckets and catalogs, the workflow uses a `concurrency` group so that
only one run executes at a time.

### Runner requirements

The runner must have a Docker daemon reachable by the runner user, since the specs start KeyCloak,
Polaris and Trino via Testcontainers. Java 17 is provisioned by `actions/setup-java`. See
[scripts/github/runner.sh](../scripts/github/runner.sh) for installing and registering the runner
itself.

The runner also has to be big enough to hold the workload: the specs run a `local[4]` Spark driver
alongside the four containers, which needs 8GB of memory (16GB comfortably) and 30GB of disk. Below
that the build dies in javac with `OutOfMemoryError: Java heap space` before any spec runs, since the
default max heap is a quarter of the machine's RAM.

The tools the workflow needs — docker, maven, a JDK 17, and the shell tools used by `afon.sh`
(git, curl, unzip, perl, jq) — are installed by the ansible playbook in
[scripts/ansible](../scripts/ansible/README.md):

```shell
cd scripts/ansible && ansible-playbook runner-setup.yml
```

To report what a runner currently has installed and whether it is big enough, without changing it:

```shell
cd scripts/ansible && ansible-playbook runner-setup.yml --tags verify
```

### Config secrets

The workflow reconstructs `$AFON_HOME/config` from repository secrets — one base64-encoded secret per
config file, named after the file:

| Config file               | Secret                    |
|---------------------------|---------------------------|
| `afon.properties`         | `AFON_PROPERTIES`         |
| `afon-aws.properties`     | `AFON_AWS_PROPERTIES`     |
| `afon-azure.properties`   | `AFON_AZURE_PROPERTIES`   |
| `afon-gcp.properties`     | `AFON_GCP_PROPERTIES`     |
| `secret.properties`       | `SECRET_PROPERTIES`       |
| `secret-aws.properties`   | `SECRET_AWS_PROPERTIES`   |
| `secret-azure.properties` | `SECRET_AZURE_PROPERTIES` |
| `secret-gcp.properties`   | `SECRET_GCP_PROPERTIES`   |
| `users.csv`               | `USERS_CSV`               |

base64 is used so that multi-line values such as `SNOWFLAKE_PRIVATE_KEY` and
`GOOGLE_APPLICATION_CREDENTIALS` survive the round trip byte for byte.

Publish them from a local config dir with [scripts/github/secrets.sh](../scripts/github/secrets.sh),
which needs the `gh` CLI authenticated with the `repo` scope:

```shell
./scripts/github/secrets.sh push --dry-run # reports what would be published
./scripts/github/secrets.sh push           # publishes all config files, overwriting existing secrets
./scripts/github/secrets.sh list           # lists the secrets set on the repo
```

The workflow writes the config into `$GITHUB_WORKSPACE/.afon/config` rather than the runner's home, so
that it is scoped to the run, and deletes it again in an `always()` step.

