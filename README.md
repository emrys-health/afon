# Afon Documentation

Afon is the core infrastructure project that deploys [Apache Polaris](https://polaris.apache.org/) across AWS, Azure, and GCP to enable secure cross-cloud querying of Iceberg tables via Spark and Snowflake.

---

## Quickstart — How I set it up

### Prerequisites

Install the following on your machine:

- **Java 17** (enforced by the Maven enforcer plugin)
- **Maven**
- **Docker**
- **OpenTofu** (Terraform-compatible)
- **Kubernetes CLI** (`kubectl`)
- **AWS CLI**
- **Azure CLI**
- **GCloud CLI**

### 1. Clone and build

```shell
git clone https://github.com/emrys-health/afon-core.git
cd afon-core
mvn clean install
```

### 2. Configure properties

Afon uses two properties files in the project root:

- **`afon.properties`** — non-sensitive configuration (bucket names, domain names, regions, Polaris credentials). Copy the example and fill in your cloud-specific values:

```shell
cp afon.properties afon.properties  # or create from the template in README.md
```

Key properties to set:

| Property | Example | Description |
|---|---|---|
| `CLUSTER_NAME` | `spark-cluster-wb2` | Name for your Kubernetes cluster |
| `AWS_REGION` | `eu-west-2` | AWS region |
| `AWS_S3_BUCKET_NAME` | `emrys-afon-bucket` | Primary S3 bucket |
| `AWS_S3_EXTERNAL_BUCKET_NAME` | `emrys-afon-bucket-2` | External S3 bucket |
| `AWS_POLARIS_DOMAIN_NAME` | `emryspolaris.click` | Domain for AWS Polaris |
| `GCP_BUCKET_NAME` | `emrys_afon_core` | GCS bucket |
| `GCP_DOMAIN_NAME` | `emryspolarisgcp.click` | Domain for GCP Polaris |
| `AZURE_BUCKET` | `afonstorage` | Azure Blob container |
| `AZURE_STORAGE_ACCOUNT` | `afonstorageaccount` | Azure storage account |
| `AZURE_DOMAIN_NAME` | `emryspolarisazure.click` | Domain for Azure Polaris |
| `POLARIS_ID` | `root` | Polaris client ID |
| `POLARIS_SECRET` | `s3cr3t` | Polaris client secret |

- **`env.properties`** — sensitive credentials. Copy from `env.properties.example` and fill in:

```shell
cp env.properties.example env.properties
```

Required sensitive properties:

| Property | How to obtain |
|---|---|
| `AWS_ACCESS_KEY_ID` | AWS IAM console |
| `AWS_SECRET_ACCESS_KEY` | AWS IAM console |
| `GOOGLE_APPLICATION_CREDENTIALS` | Contents of `~/.config/gcloud/application_default_credentials.json` with newlines removed |
| `AZURE_CLIENT_ID` | `az ad app list --display-name AfonMultiTenantApp --query "[].appId" -o tsv` |
| `AZURE_TENANT_ID` | `az ad app credential reset --id $AZURE_CLIENT_ID` |
| `AZURE_CLIENT_SECRET` | Same as above |
| `OAUTH2_CLIENT_ID` | Google Cloud Auth console |
| `OAUTH2_CLIENT_SECRET` | Google Cloud Auth console |
| `OAUTH2_COOKIE_SECRET` | `openssl rand -hex 16` |
| `POLARIS_DB_PASSWORD` | Your external Postgres password |
| `POLARIS_DB_HOST` | Your external Postgres host |

### 3. Set up cloud infrastructure

Source your properties file, then run the cloud setup scripts:

```shell
set -a && source afon.properties && set +a

# AWS
./scripts/aws/create.sh

# Azure
./scripts/azure/create.sh

# GCP
./scripts/gcp/create.sh
```

These scripts create storage buckets, IAM roles, service accounts, and DNS zones.

### 4. Set up domain names

You need domain names that point to each cloud's Kubernetes ingress. See [DNS Configuration](#how-to-set-up-dns-and-domain-names) for full instructions. In summary:

1. Buy domain names (e.g. from AWS Route 53)
2. Create DNS zones in each target cloud
3. Update the domain registrar's nameservers to point at the cloud's nameservers
4. Request SSL certificates (e.g. via AWS Certificate Manager or Let's Encrypt)

### 5. Generate infrastructure documentation

```shell
cd afon-infra
mvn compile -Dexec.mainClass=health.emrys.afon.demo.DocumentExternalCatalogsMain exec:java
```

Read the generated docs:

```shell
cat target/aws_azure_external_catalogs.md
```

### 6. Deploy the demo

```shell
mvn compile -Dexec.mainClass=health.emrys.afon.demo.DeployExternalCatalogsMain exec:exec -Pdemo
```

This deploys Polaris to AWS and Azure, populates catalogs, and runs a Spark smoke test. It takes approximately 45 minutes.

> **Preferred:** describe the deployment in a single `afon-deployment.yaml` and
> deploy with `DeployFromConfigMain` instead — see
> [Deploy from a single YAML config](#deploy-from-a-single-yaml-config-new). The
> per-topology `Deploy*Main` classes are legacy and scheduled for removal
> ([Phase 4 TODO](#todo--phase-4-legacy-removal-breaking-changes)).

### 7. Access Polaris

Once deployed, log in via your domain name:

```
https://emryspolaris.click
```

You can also access Jupyter at:

```
https://emrysjupyter.click/guacamole
```

---

## Tutorials — How I use it effectively

### Query data across clouds with Spark

Afon deploys Polaris as a REST catalog server. Spark connects to Polaris, which vends short-lived cloud credentials so Spark can read and write Iceberg tables stored in S3, GCS, or Azure Blob Storage — all from a single Spark session.

**Generate PySpark code for Jupyter:**

```shell
cd afon-infra
mvn compile -Dexec.mainClass=health.emrys.afon.core.configuration.SparkContextConfigurationPrinter exec:java
```

This prints ready-to-run PySpark code that:

1. Creates a Spark session connected to the Spark Connect server
2. Writes test data to AWS, GCP, and Azure catalogs
3. Joins data across all three clouds in a single query

The generated code looks like:

```python
from pyspark.sql import SparkSession

spark = SparkSession.builder.appName("test").remote("sc://spark-connector-0-driver-svc:15002").getOrCreate()

df_test = spark.createDataFrame([["test", 1]], ['name', 'num'])

# AWS
aws_namespace = "aws.acceptancetests"
spark.sql(f"CREATE NAMESPACE IF NOT EXISTS {aws_namespace}")
df_test.writeTo(f"{aws_namespace}.smoketesttable").create()

# GCP
gcp_namespace = "gcp.acceptancetests"
spark.sql(f"CREATE NAMESPACE IF NOT EXISTS {gcp_namespace}")
df_test.writeTo(f"{gcp_namespace}.smoketesttable").create()

# Azure
az_namespace = "azure.acceptancetests"
spark.sql(f"CREATE NAMESPACE IF NOT EXISTS {az_namespace}")
df_test.writeTo(f"{az_namespace}.smoketesttable").append()

# Cross-cloud join
df_gcp = spark.read.table(f"{gcp_namespace}.smoketesttable")
df_azure = spark.read.table(f"{az_namespace}.smoketesttable")
df_aws = spark.read.table(f"{aws_namespace}.smoketesttable")

df_joined = (
    df_gcp
    .join(df_azure, on="name", how="inner")
    .join(df_aws, on="name", how="inner")
)
df_joined.show()
```

### Configure Polaris catalogs

When a new Polaris instance starts, it has no catalogs. You can configure all catalogs at once:

```shell
cd afon-infra
mvn compile -Dexec.mainClass=health.emrys.afon.infra.PurgeAndPopulatePolaris -Dexec.args="emryspolaris.click" exec:java
```

This creates catalogs for AWS, GCP, Azure, and their external/forbidden variants in a single call.

To configure a single catalog on a specific endpoint:

```shell
mvn -DPOLARIS_ENDPOINT=emryspolarisazure.click compile \
  -Dexec.mainClass=health.emrys.afon.infra.EndpointPolarisConfiguration \
  -Dexec.args="AZURE" exec:java
```

### Run acceptance tests

The acceptance tests in `afon-acceptance-tests` verify that cross-cloud access works correctly — including that access to "forbidden" buckets is properly denied.

```shell
cd afon-acceptance-tests
mvn test
```

Test specs include:

- `AwsAccessSpec` — write and read Iceberg tables via AWS Polaris to S3
- `GcpAccessSpec` — write and read via AWS Polaris to GCS
- `AzureAccessSpec` — write and read via AWS Polaris to Azure Blob
- `ExternalAzureDBxTableSpec` — cross-cloud: Spark talks to AWS Polaris which federates to Azure Polaris
- `ExternalGcpTableSpec` — cross-cloud: Spark talks to AWS Polaris which federates to GCP Polaris
- `ExternalGlueTableSpec` — federated Glue catalog access
- `AwsAccessForbiddenSpec` / `GcpAccessForbiddenSpec` / `AzureAccessForbiddenSpec` — verify that access to forbidden buckets is denied

---

## How-to Guides — for specific problems

### Deploy from a single YAML config (new)

Deployments can now be described in one declarative file (`afon-deployment.yaml`)
instead of picking a per-topology `Deploy*Main` class. The design and roadmap
live in [docs/config_driven_architecture.md](docs/config_driven_architecture.md);
the work landed in four stages:

| Stage | What it delivered |
|---|---|
| **1. Schema + parser** | A JSON Schema (`afon-config/src/main/resources/afon-deployment.schema.json`) and matching Java records (`health.emrys.afon.config.deployment`) that parse and validate the YAML — clouds, catalogs, query engines, workbench. Secrets are never in the YAML: fields like `jdbcUrlRef` *name* a property in `afon.properties`/`env.properties`. `LegacyConfigExporter` generates a starter YAML from your current properties. Example config: `docs/examples/emrys-demo.yaml`. |
| **2. Plugin extraction** | The cloud stacks, engines and workbench became plugins behind `health.emrys.afon.platform`: `AwsCloudPlugin`/`AzureCloudPlugin`/`GcpCloudPlugin` (via `PluginRegistry`), `SparkEngineDeployer`/`TrinoEngineDeployer`, `JupyterWorkbenchDeployer`. The legacy `Deploy*Main` classes still work and now delegate to the same code — `PluginParityTest` asserts both paths synthesise byte-identical Terraform, so `tofu apply` never recreates live resources. |
| **3. Planner + entry point** | `DeploymentPlanner` turns the YAML into the familiar ordered step list (per cloud: clean → deploy → wait for DNS → configure catalogs, then Snowflake catalog integrations last). `DeployFromConfigMain` runs it; `DocumentFromConfigMain` prints it as markdown. `SnowflakeEngineDeployer` emits the same `CREATE CATALOG INTEGRATION` SQL as the `DeploySnowflakeReading*Main` demos. `DeploymentPlannerTest` asserts the config-driven AWS deployment is identical to the legacy `DeployFullStackAwsMain` stack. |
| **3b. Workbenches + UI** | Every workbench (`jupyter`, `vscode`, `rstudio`) deploys behind the same oauth2-proxy gate via a shared `AuthProxyConfig`; `VsCodeWorkbench` and `RStudioWorkbench` join `JupyterWorkbenchDeployer` (synth-tested by `WebWorkbenchSynthTest`). `afon-ui/` is a static config generator (React + Tailwind + `ajv`, no backend, no credentials) that validates against the same JSON Schema as the Java planner and downloads `afon-deployment.yaml`; a Playwright test gates it. Acceptance: `WorkbenchAuthGateSpec` (any workbench refuses unauthenticated access) and `ConfigDrivenAccessSpec` (runs the per-cloud access specs for exactly the clouds the YAML declares). |

**1. Generate a config from your current properties** (or start from
`docs/examples/emrys-demo.yaml`):

```shell
cd afon-config
mvn compile -Dexec.mainClass=health.emrys.afon.config.deployment.LegacyConfigExporter \
  -Dexec.args="../my-deployment.yaml" exec:java
```

**2. Preview the plan** — parses, validates, and prints the steps without
deploying anything:

```shell
cd afon-infra
mvn compile -Dexec.mainClass=health.emrys.afon.platform.DocumentFromConfigMain \
  -Dexec.args="../docs/examples/emrys-demo.yaml" exec:java
```

**3. Deploy:**

```shell
mvn compile -Pdemo -Dexec.mainClass=health.emrys.afon.platform.DeployFromConfigMain \
  -Dexec.args="../docs/examples/emrys-demo.yaml" exec:exec
```

**4. Resume a failed run from a named step** (same mechanism as the legacy
mains — pass the step name after the config path):

```shell
mvn compile -Pdemo -Dexec.mainClass=health.emrys.afon.platform.DeployFromConfigMain \
  -Dexec.args="../docs/examples/emrys-demo.yaml deploy_azure" exec:exec
```

**5. Build a config in the browser** — `afon-ui` is a static single-page app
(no backend, no cloud credentials) that walks through clouds → catalogs →
engines → workbench, validates live against the shared JSON Schema, and
downloads the YAML for review:

```shell
cd afon-ui
npm install && npm run dev   # http://localhost:5173
```

Current constraints: cloud `id`s must still be `aws`/`azure`/`gcp` (state and
tooling key off `SupportedCloudsEnum` until Phase 4). The legacy
`Deploy*Main` commands below all keep working unchanged until then.

### TODO — Phase 4: legacy removal (breaking changes)

Phases 1–3 of [docs/config_driven_architecture.md](docs/config_driven_architecture.md)
are delivered; the YAML workflow above is the preferred path. Phase 4 removes
the legacy path. It has **not** been executed yet because it deliberately
breaks entry points and the synth-parity safety net. What needs to be done,
and what breaks:

**Clean-up tasks**

1. **Delete the per-topology mains** — the 9 `Deploy*Main` classes in
   `health.emrys.afon.demo` (`DeployFullStackAwsMain`,
   `DeployFullStackAzureMain`, `DeployAzurePolarisMain`, `DeployGcpPolarisMain`,
   `DeployExternalCatalogsMain`, the four `DeploySnowflake*Main`s), their
   `Document*Main` companions, and `StepsProvider`. Keep `StepRunner`,
   `StepOperations` and `StepActions` — `DeployFromConfigMain` runs on them.
2. **Retire the parity gates** — `PluginParityTest` and the legacy-vs-config
   assertions in `DeploymentPlannerTest` compare against code deleted in (1).
   Replace them with config-driven synth snapshot tests so accidental infra
   recreation is still caught.
3. **Derive Trino's catalog list from `catalogs:`** — today it iterates
   `SupportedCloudsEnum` (deferred from Phase 3 because changing it alters the
   synthesised Helm values, which the parity gate forbids). Unblocked once (2)
   lands.
4. **Collapse `SupportedCloudsEnum`** — the biggest chunk: ~52 files across 7
   modules reference it. Providers (`aws`/`azure`/`gcp`) should come from
   `clouds[].provider`; the topology values (`AWS_TRINO`, `*_FORBIDDEN`,
   `*_EXTERNAL`) are really catalog names and should come from `catalogs:`
   (test fixtures marked with `testFixture: true`).
5. **Purge legacy references** — `.vscode/launch.json`,
   `docs/full_stack_aws.md`, `docs/aws_azure_external_catalogs.md`,
   `scripts/trino/README.md`, `docs/DOCUMENTATION.md`, and the remaining
   `Deploy*Main` how-to sections in this README.

**Breaking changes**

- Every `mvn … -Dexec.mainClass=health.emrys.afon.demo.Deploy*Main …` command
  stops working. The only deploy entry point becomes `DeployFromConfigMain`
  plus an `afon-deployment.yaml`; documentation generation moves from
  `Document*Main` to `DocumentFromConfigMain`.
- Code importing `SupportedCloudsEnum` breaks — including the
  `afon-acceptance-tests` fixtures (`AcceptanceTestConfig`'s `AWS_TRINO` /
  `*_FORBIDDEN` catalogs) and the Spark/Trino config-key derivation in
  `afon-spark` / `afon-trino`. These must be re-keyed on config-declared
  catalog names; `ConfigDrivenAccessSpec` becomes the way the access suites
  are selected.
- The synthesised Terraform for Trino changes when (3) lands: the first
  `tofu apply` afterwards **will modify the live Trino Helm release**, and
  there is no parity gate any more — review the plan against each live
  environment before applying.
- Cloud `id`s in the YAML stop being constrained to `aws`/`azure`/`gcp`
  (multiple clouds per provider become possible), which changes CDKTF stack
  IDs for anything not already using the defaults — keep `id`s stable for
  existing deployments or OpenTofu will recreate stacks.

### Deploy the full AWS stack

The full AWS stack includes Polaris, Spark (via Spark Connect), and Jupyter (behind Guacamole with OAuth2).

```shell
cd afon-infra
mvn compile -Dexec.mainClass=health.emrys.afon.demo.DeployFullStackAwsMain exec:exec -Pdemo
```

Steps executed:

1. `clean_aws` — destroy any existing AWS stack
2. `aws_spark` — deploy Polaris + Spark to AWS via CDKTF + OpenTofu
3. `waitFor_emryspolaris` — wait for DNS to propagate
4. `all_catalogs_in_emryspolaris` — configure all catalogs in Polaris
5. `aws_spark_talks_to_aws` — smoke test: Spark writes and reads via Polaris

### Deploy Polaris to GCP only

```shell
cd afon-infra
mvn compile -Dexec.mainClass=health.emrys.afon.demo.DeployGcpPolarisMain exec:exec -Pdemo
```

Steps: `clean_gcp` → `gcp_polaris` → `waitFor_emryspolarisgcp` → `all_catalogs_in_emryspolarisgcp`

### Deploy Polaris to Azure only

```shell
cd afon-infra
mvn compile -Dexec.mainClass=health.emrys.afon.demo.DeployAzurePolarisMain exec:exec -Pdemo
```

Steps: `clean_azure` → `az_polaris` → `waitFor_emryspolarisazure` → `all_catalogs_in_emryspolarisazure`

### Deploy cross-cloud external catalogs (AWS + Azure)

This deploys both AWS and Azure Polaris instances and configures AWS Polaris to federate to Azure Polaris for cross-cloud querying.

```shell
cd afon-infra
mvn compile -Dexec.mainClass=health.emrys.afon.demo.DeployExternalCatalogsMain exec:exec -Pdemo
```

Steps: `clean_aws` → `aws_spark` → `waitFor_emryspolaris` → `all_catalogs_in_emryspolaris` → `clean_azure` → `az_polaris` → `waitFor_emryspolarisazure` → `emryspolarisazure_purge_azure` → `foreign_catalog_in_emryspolarisazure` → `external_azure_in_emryspolarisazure` → `aws_spark_talks_to_azure_external`

### How to set up DNS and domain names

Polaris requires HTTPS for encrypted communication, which requires domain names recognised by a Certificate Authority.

**For Azure:**

```shell
az network dns zone create --resource-group $AZURE_RESOURCE_GROUP --name $YOUR_DOMAIN
```

Note the nameservers in the output, then update your domain registrar to use them.

**For GCP:**

Create a DNS zone through the GCP console, then:

```shell
gcloud dns managed-zones describe $YOUR_ZONE_NAME --project=$YOUR_PROJECT
```

Note the nameservers and update your registrar.

**For AWS:**

```shell
aws route53 list-hosted-zones-by-name --dns-name yourdomain.click
```

Update the `AWS_XXX_DNS_ZONE` values in `afon.properties`. Request a certificate via [AWS Certificate Manager](https://console.aws.amazon.com/acm/certificates/list) and press "Create Record in Route 53" to prove ownership.

**Domain delegation:**

Go to your domain registrar's DNS section, edit the name servers, and add the nameservers from your cloud provider. Propagation takes about 10 minutes.

### Connect Snowflake to cross-cloud data via Polaris

Afon can configure Snowflake to read Iceberg tables from any cloud via Polaris as a catalog integration.

**Read GCP data via AWS Polaris:**

```shell
cd afon-infra
mvn compile -Dexec.mainClass=health.emrys.afon.demo.DeploySnowflakeReadingGcpViaAwsMain exec:exec -Pdemo
```

**Read Azure data via AWS Polaris:**

```shell
mvn compile -Dexec.mainClass=health.emrys.afon.demo.DeploySnowflakeReadingAzureViaAwsMain exec:exec -Pdemo
```

**Read AWS data via AWS Polaris:**

```shell
mvn compile -Dexec.mainClass=health.emrys.afon.demo.DeploySnowflakeReadingAwsViaAwsMain exec:exec -Pdemo
```

These deploy the full AWS stack, write test data to the target cloud, then create a Snowflake catalog integration and iceberg table that reads through Polaris.

### Write data from Snowflake to Azure blob storage

See `afon-snowflake/README.md` for the full guide. In summary:

1. Create a storage integration in Snowflake pointing at Azure
2. Create an external volume with Azure storage details
3. Click the consent URL output by `DESCRIBE EXTERNAL VOLUME` to grant Snowflake access
4. Assign the Storage Blob Data Contributor role to the Snowflake app in Azure
5. Create an Iceberg table and insert data

The deployment is automated via:

```shell
mvn compile -Dexec.mainClass=health.emrys.afon.demo.DeploySnowflakeWritingAzureViaAwsMain exec:exec -Pdemo
```

### Set up monitoring

Install monitoring components on AWS:

```shell
cd afon-infra
mvn compile -Dexec.mainClass=health.emrys.afon.cdk.PrometheusClusterDeploy exec:java
cd cdktf.out/stacks/aws
tofu apply --auto-approve
```

This installs Prometheus, Loki, Grafana, and Hubble.

**Access Grafana:**

```shell
docker run -d --name grafana --network=host grafana/grafana
kubectl port-forward svc/kube-prometheus-stack-prometheus 9090:9090
```

Open `http://localhost:3000`, log in with `admin`/`admin`, and add a Prometheus data source at `http://localhost:9090`.

**Access Hubble (network flow visualisation):**

```shell
kubectl port-forward svc/hubble-ui -n kube-system 12000:80
```

Open `http://localhost:12000`.

**Access Loki (logs):**

```shell
kubectl port-forward svc/loki 3100:3100
```

Add a Loki data source in Grafana at `http://localhost:3100` with an HTTP header `X-Scope-OrgID` set to your `CLUSTER_NAME`.

### Tear down your environment

**Destroy via OpenTofu:**

```shell
cd cdktf.out/stacks/aws
tofu destroy --auto-approve
```

**Nuke scripts (when Terraform state is lost or things are stuck):**

```shell
# AWS
cd afon-infra
mvn compile -Dexec.mainClass=health.emrys.afon.cdk.AwsNuke exec:java

# Azure
mvn compile -Dexec.mainClass=health.emrys.afon.cdk.AzureNuke exec:java
```

These output shell scripts that destroy everything. You may need to run them multiple times for VPCs to disappear.

**Cloud-specific destroy scripts:**

```shell
./scripts/aws/destroy.sh
./scripts/azure/destroy.sh
./scripts/gcp/destroy.sh
```

**Azure DNS cleanup (if K8s service is gone but DNS record remains):**

```shell
az network dns record-set a delete \
  --resource-group afon_resource_group \
  --zone-name emryspolarisazure.click \
  --name "@" \
  --yes
```

### Resume a failed deployment from a specific step

If a deployment fails partway through, you can resume from a specific step:

```shell
mvn compile -Pdemo -Dexec.mainClass="health.emrys.afon.demo.DeployExternalCatalogsMain" \
  -Dexec.args="az_polaris" exec:exec
```

Use `exec:exec` (not `exec:java`) when resuming with `-Dexec.args`.

### Diagnose Polaris issues

**Check table status via curl:**

```shell
TOKEN=$(curl -X POST "https://emryspolaris.click/api/catalog/v1/oauth/tokens" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=root&client_secret=***REMOVED***" \
  | jq -r '.access_token')

curl -X GET "https://emryspolaris.click/api/catalog/v1/aws/namespaces/acceptancetests/tables/YOUR_TABLE" \
  -H "Authorization: Bearer ${TOKEN}" | jq
```

**Check Kubernetes ingress:**

```shell
kubectl get ingress
```

**Check DNS propagation:**

```shell
dig A emryspolaris.click +short
```

**Check Spark Connect logs:**

```shell
kubectl logs spark-connector-0-driver
```

---

## Explanations — How it works

### Architecture overview

Afon's architecture has four layers:

```
┌─────────────────────────────────────────────────────────┐
│  Query Engines                                           │
│  (Spark / Snowflake / Trino)                             │
└──────────────────────┬──────────────────────────────────┘
                       │ REST API (Iceberg REST Catalog)
┌──────────────────────▼──────────────────────────────────┐
│  Apache Polaris                                          │
│  (runs in Kubernetes, one per cloud)                     │
│  - Catalog management (internal + federated/external)    │
│  - Credential vending (short-lived tokens)               │
│  - Metadata stored in external Postgres                  │
└──────────────────────┬──────────────────────────────────┘
                       │ Vended credentials
┌──────────────────────▼──────────────────────────────────┐
│  Cloud Storage                                           │
│  (AWS S3 / GCP GCS / Azure Blob Storage)                 │
│  - Iceberg table data files (Parquet)                    │
│  - Iceberg metadata files (manifests, snapshots)         │
└─────────────────────────────────────────────────────────┘
```

The Java code generates CDKTF (Cloud Development Kit for Terraform) JSON files. OpenTofu then provisions the actual infrastructure — Kubernetes clusters, deployments, services, and ingress controllers. The Java code does not directly create cloud resources.

After the Java/CDKTF step, you can inspect or modify the generated Terraform in `cdktf.out/stacks/YOUR_DEPLOYMENT/` and run `tofu apply` or `tofu destroy` directly.

### Cross-cloud querying

Cross-cloud querying works through Polaris catalog federation. The key configuration is:

```
POLARIS_FEATURES_ENABLE_CATALOG_FEDERATION=true
POLARIS_FEATURES_SUPPORTED_CATALOG_CONNECTION_TYPES="ICEBERG_REST,HIVE"
```

When enabled, a Polaris instance in one cloud (e.g. AWS) can be configured with an **external catalog** that points to a Polaris instance in another cloud (e.g. Azure). When Spark queries the external catalog, the AWS Polaris proxies the request to the Azure Polaris, which vends Azure credentials. Spark then reads directly from Azure Blob Storage — no data passes through the Polaris servers.

The `SupportedCloudsEnum` defines the catalog types:

- `AWS` — internal catalog, data in S3
- `GCP` — internal catalog, data in GCS
- `AZURE` — internal catalog, data in Azure Blob
- `AZURE_EXTERNAL` — external/federated catalog pointing to another Polaris
- `GCP_EXTERNAL` — same for GCP
- `AWS_FORBIDDEN` / `GCP_FORBIDDEN` / `AZURE_FORBIDDEN` — catalogs pointing at buckets the user should NOT have access to (used for security testing)

### Properties system

Afon uses a two-file properties system:

- **`afon.properties`** — non-sensitive configuration, safe to check into version control (though the current repo includes real values for the Emrys demo environment)
- **`env.properties`** — sensitive credentials, should never be committed

Both are loaded by `AfonPropertiesLoader`, which tries the parent directory first (when running as a build) then the current directory (when running in an IDE). System properties can override any value.

All known properties are defined as `AfonProperty` constants in `AfonProperties.java`. Each property knows its own name and can extract its value from a `Properties` object.

The `SupportedCloudsEnum` enum defines the cloud types that Polaris can manage, used throughout the codebase to select the right storage configuration and credential vending logic.

### Deployment step system

Deployments are composed of reusable steps:

- **`Step`** — a named unit of work with a description and a runnable action
- **`StepOperations`** — factory methods that create `Step` instances for common operations (clean, deploy, wait, configure, test)
- **`StepActions`** — low-level operations that actually execute commands (run Maven, run OpenTofu, call Polaris APIs)
- **`StepRunner`** — executes a list of steps in order, with the ability to resume from a named step
- **`StepsProvider`** — interface implemented by `Deploy*Main` classes to provide their step list

Each `Deploy*Main` class composes steps from `StepOperations`. For example, `DeployExternalCatalogsMain` reuses all steps from `DeployFullStackAwsMain` and then adds Azure-specific steps.

The corresponding `Document*Main` classes generate markdown documentation describing each step — this keeps documentation in lock-step with the actual deployment code.

### Polaris client

`AfonPolarisClient` is the HTTP client for talking to Polaris REST APIs. It handles:

- **Authentication** — OAuth2 client credentials flow to get an access token (with retry logic for intermittent 503s)
- **Catalog management** — POST to create catalogs, PUT to update, DELETE to remove
- **Namespace and table operations** — CRUD via REST endpoints
- **JSON calls** — POST/PUT with JSON payloads for catalog configuration

`PolarisConfigurer` builds on top of the client to provide higher-level operations: creating catalogs with admin grants, configuring principal roles, and creating users.

`CloudConfigForPolaris` is the abstract base for per-cloud configuration. Each cloud has a concrete subclass (e.g. `AwsCloudConfigForPolaris`, `GoogleCloudConfigForPolaris`, `AzureCloudConfigForPolaris`) that knows how to generate the JSON payload for creating a catalog in that cloud, including the correct storage configuration info and bucket URI.

### Spark integration

Spark connects to Polaris via the Iceberg REST catalog protocol. `SparkWarehouseConfig` builds the Spark configuration with:

- The Polaris endpoint URI
- OAuth2 credentials (`client_id` / `client_secret`)
- Warehouse names for each cloud catalog

`SparkSessionFactory` creates a `SparkSession` configured to talk to a Polaris instance. It can discover the Polaris endpoint by interrogating the Kubernetes cluster (`PolarisClusterCredentials`) or use an explicit endpoint.

In the deployed environment, Spark runs as a Spark Connect server in Kubernetes. Jupyter notebooks connect to it via the Spark Connect protocol (`sc://spark-connector-0-driver-svc:15002`).

### Security model

- **Polaris authentication** — OAuth2 client credentials flow. The `POLARIS_ID` and `POLARIS_SECRET` properties are used to obtain access tokens.
- **Credential vending** — Polaris does not expose long-lived cloud credentials to query engines. Instead, it vends short-lived credentials scoped to the specific table being accessed. This is the `VENDED_CREDENTIALS` access delegation mode used in Snowflake catalog integrations.
- **Forbidden buckets** — Each cloud has a "forbidden" bucket that the IAM roles do NOT grant access to. Acceptance tests verify that Polaris correctly denies access to these buckets.
- **Jupyter access** — Jupyter is served via Apache Guacamole (pixel streaming via X11 VNC) behind an OAuth2 proxy that delegates to Google. This requires `OAUTH2_CLIENT_ID`, `OAUTH2_CLIENT_SECRET`, and `OAUTH2_COOKIE_SECRET`.
- **Network policies** — Kubernetes network policies restrict pod-to-pod communication. The `NetworkConfiguration` class sets up policies between Jupyter, Spark, and Polaris pods.
- **Pomerium** — An alternative auth proxy configuration is available in `afon-pomerium/`.

---

## Examples — What can I do with it

### PySpark cross-cloud join

Generated by `SparkContextConfigurationPrinter`, this writes data to three clouds and joins it:

```python
from pyspark.sql import SparkSession

spark = SparkSession.builder.appName("test") \
    .remote("sc://spark-connector-0-driver-svc:15002") \
    .getOrCreate()

df_test = spark.createDataFrame([["test", 1]], ['name', 'num'])

# Write to AWS
aws_namespace = "aws.acceptancetests"
spark.sql(f"CREATE NAMESPACE IF NOT EXISTS {aws_namespace}")
df_test.writeTo(f"{aws_namespace}.smoketesttable").create()

# Write to GCP
gcp_namespace = "gcp.acceptancetests"
spark.sql(f"CREATE NAMESPACE IF NOT EXISTS {gcp_namespace}")
df_test.writeTo(f"{gcp_namespace}.smoketesttable").create()

# Write to Azure
az_namespace = "azure.acceptancetests"
spark.sql(f"CREATE NAMESPACE IF NOT EXISTS {az_namespace}")
df_test.writeTo(f"{az_namespace}.smoketesttable").append()

# Cross-cloud join
df_gcp = spark.read.table(f"{gcp_namespace}.smoketesttable")
df_azure = spark.read.table(f"{az_namespace}.smoketesttable")
df_aws = spark.read.table(f"{aws_namespace}.smoketesttable")

df_joined = (
    df_gcp.join(df_azure, on="name", how="inner")
         .join(df_aws, on="name", how="inner")
)
df_joined.show()
```

### Write and read an Iceberg table via Spark

**Write:**

```shell
cd afon-infra
mvn compile -Dexec.mainClass=health.emrys.afon.infra.checks.SparkTestWriter \
  -Dexec.args="azure_external root s3cr3t emryspolaris.click namespaceph" exec:java
```

This creates a namespace, creates an Iceberg table, and writes three rows ("apple", "banana", "cherry").

**Read:**

```shell
mvn compile -Dexec.mainClass=health.emrys.afon.infra.checks.SparkTestReader \
  -Dexec.args="azure_external root s3cr3t emryspolaris.click namespaceph" exec:java
```

### Create sample patient data

The `afon-samples` module contains a sample data generator:

```shell
cd afon-samples
mvn compile -Dexec.mainClass=health.emrys.afon.samples.PatientDataMain exec:java
```

This creates `patients` (in AWS) and `occupancy` (in GCP) tables with 10,000 rows each, demonstrating cross-cloud data placement.

### Snowflake reads GCP table via Polaris

The SQL generated by `DeploySnowflakeReadingGcpViaAwsMain`:

```sql
CREATE OR REPLACE CATALOG INTEGRATION iceberg_gcp_id
  CATALOG_SOURCE = ICEBERG_REST
  TABLE_FORMAT = ICEBERG
  REST_CONFIG = (
    CATALOG_URI = 'https://emryspolaris.click/api/catalog/'
    CATALOG_NAME = 'gcp'
    ACCESS_DELEGATION_MODE = VENDED_CREDENTIALS
  )
  REST_AUTHENTICATION = (
    TYPE = OAUTH
    OAUTH_CLIENT_ID = 'root'
    OAUTH_CLIENT_SECRET = 's3cr3t'
    OAUTH_ALLOWED_SCOPES = ('PRINCIPAL_ROLE:ALL')
  )
  ENABLED = TRUE;

USE DATABASE SNOWFLAKE_LEARNING_DB;

CREATE OR REPLACE ICEBERG TABLE my_gcp_table_id
  CATALOG = 'iceberg_gcp_id'
  CATALOG_TABLE_NAME = 'test_table'
  CATALOG_NAMESPACE = 'namespaceph'
  REPLACE_INVALID_CHARACTERS = TRUE;

SELECT COUNT(*) FROM my_gcp_table_id;
```

### Snowflake writes Iceberg table to Azure

```sql
CREATE OR REPLACE STORAGE INTEGRATION azure_int
  TYPE = EXTERNAL_STAGE
  STORAGE_PROVIDER = AZURE
  ENABLED = TRUE
  STORAGE_ALLOWED_LOCATIONS = ('azure://afonstorageaccount.blob.core.windows.net/afonstorage')
  AZURE_TENANT_ID='YOUR_TENANT_ID';

CREATE OR REPLACE EXTERNAL VOLUME my_external_volume
  STORAGE_LOCATIONS = (
    (
      NAME = 'azure_loc'
      STORAGE_PROVIDER = 'AZURE'
      STORAGE_BASE_URL = 'azure://afonstorageaccount.blob.core.windows.net/afonstorage/acceptancetests/'
      AZURE_TENANT_ID='YOUR_TENANT_ID'
    )
  );

USE DATABASE SNOWFLAKE_LEARNING_DB;

CREATE OR REPLACE ICEBERG TABLE my_azure_iceberg_table (
    value STRING
)
  EXTERNAL_VOLUME = 'MY_EXTERNAL_VOLUME'
  CATALOG = 'SNOWFLAKE'
  BASE_LOCATION = 'my_project_folder/';

INSERT INTO my_azure_iceberg_table (value)
VALUES ('Alice'), ('Bob');
```

After writing, you can register the table in AWS Polaris so Spark can also read it.

### Acceptance test flow

The `AbstractAccessSpec` demonstrates the full lifecycle:

1. **Create catalog** — POST to `/api/management/v1/catalogs` with cloud-specific JSON
2. **Verify catalog** — GET to confirm Polaris acknowledges it
3. **Grant catalog admin** — PUT to assign admin roles
4. **Create namespace** — `CREATE NAMESPACE IF NOT EXISTS aws.acceptancetests`
5. **Write data** — `spark.createDataFrame(...).writeTo(tableNameFQN).create()`
6. **Read data** — `spark.read.table(tableNameFQN)` and verify row count
7. **Register table** — GET to confirm Polaris knows about the table
8. **Cross-cloud join** — `SELECT * FROM aws_table AS x INNER JOIN gcp_table AS y ON x.id = y.id`

---

## Templates — How do I use it in production

### Production properties template

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

### GCP
GCP_BUCKET_NAME=your_org_afon
GCP_BUCKET_NAME_FORBIDDEN=your_org_afon_forbidden
GCP_EXTERNAL_BUCKET_NAME=your_org_afon_external
GCP_SERVICE_ACCOUNT=your-sa@your-project.iam.gserviceaccount.com
GCP_DOMAIN_NAME=your-polaris-gcp.yourdomain.com

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

### Snowflake
SNOWFLAKE_JDBC_URL=jdbc:snowflake://YOUR_ACCOUNT.snowflakecomputing.com/
```

**`env.properties`** (sensitive — never commit):

```properties
# AWS
AWS_ACCESS_KEY_ID=YOUR_ACCESS_KEY
AWS_SECRET_ACCESS_KEY=YOUR_SECRET_KEY

# GCP
GOOGLE_APPLICATION_CREDENTIALS=CONTENTS_OF_APPLICATION_DEFAULT_CREDENTIALS_JSON_WITH_NEWLINES_REMOVED

# Azure
AZURE_CLIENT_ID=YOUR_CLIENT_ID
AZURE_TENANT_ID=YOUR_TENANT_ID
AZURE_CLIENT_SECRET=***REMOVED***
DATABRICKS_TOKEN=YOUR_DATABRICKS_TOKEN

# OAuth2 (for Jupyter/Guacamole)
OAUTH2_CLIENT_ID=YOUR_GOOGLE_OAUTH_CLIENT_ID
OAUTH2_CLIENT_SECRET=***REMOVED***
OAUTH2_COOKIE_SECRET=GENERATED_WITH_OPENSSL_RAND_HEX_16

# External Postgres for Polaris metadata
POLARIS_DB_PASSWORD=YOUR_STRONG_DB_PASSWORD
POLARIS_DB_HOST=YOUR_POSTGRES_HOST

# Snowflake
SNOWFLAKE_PRIVATE_KEY=YOUR_SNOWFLAKE_PRIVATE_KEY
```

### Production deployment checklist

- [ ] **Domain names purchased** — one per cloud (e.g. `polaris-aws.yourdomain.com`, `polaris-azure.yourdomain.com`, `polaris-gcp.yourdomain.com`)
- [ ] **DNS zones created** in each target cloud and nameservers delegated at your registrar
- [ ] **SSL certificates** requested and validated (ACM for AWS, Let's Encrypt for others)
- [ ] **External Postgres database** provisioned for Polaris metadata storage
- [ ] **OAuth2 provider** configured (Google or other OIDC provider) for Jupyter access
- [ ] **Cloud IAM roles** created via `scripts/{aws,azure,gcp}/create.sh`
- [ ] **Storage buckets** created (primary, external, and forbidden for each cloud)
- [ ] **`afon.properties`** filled in with all non-sensitive values
- [ ] **`env.properties`** filled in with all credentials
- [ ] **`mvn clean install`** succeeds
- [ ] **Kubernetes cluster** creation permissions confirmed
- [ ] **`KUBE_CONFIG_PATH`** environment variable set to your kubeconfig path
- [ ] **`unalias aws`** if using a Dockerized AWS CLI

### Production deployment commands

```shell
# Full AWS stack (Polaris + Spark + Jupyter)
cd afon-infra
mvn compile -Dexec.mainClass=health.emrys.afon.demo.DeployFullStackAwsMain exec:exec -Pdemo

# Azure Polaris (standalone)
mvn compile -Dexec.mainClass=health.emrys.afon.demo.DeployAzurePolarisMain exec:exec -Pdemo

# GCP Polaris (standalone)
mvn compile -Dexec.mainClass=health.emrys.afon.demo.DeployGcpPolarisMain exec:exec -Pdemo

# Cross-cloud external catalogs (AWS + Azure federation)
mvn compile -Dexec.mainClass=health.emrys.afon.demo.DeployExternalCatalogsMain exec:exec -Pdemo

# Snowflake reading cross-cloud data
mvn compile -Dexec.mainClass=health.emrys.afon.demo.DeploySnowflakeReadingGcpViaAwsMain exec:exec -Pdemo
mvn compile -Dexec.mainClass=health.emrys.afon.demo.DeploySnowflakeReadingAzureViaAwsMain exec:exec -Pdemo

# Snowflake writing to Azure
mvn compile -Dexec.mainClass=health.emrys.afon.demo.DeploySnowflakeWritingAzureViaAwsMain exec:exec -Pdemo
```

### Production monitoring setup

1. **Install monitoring stack:**

```shell
cd afon-infra
mvn compile -Dexec.mainClass=health.emrys.afon.cdk.PrometheusClusterDeploy exec:java
cd cdktf.out/stacks/aws
tofu apply --auto-approve
```

2. **Run Grafana locally:**

```shell
docker run -d --name grafana --network=host \
  -v $YOUR_CONFIG_DIR/grafana:/var/lib/grafana \
  grafana/grafana
```

3. **Tunnel to Prometheus:**

```shell
kubectl port-forward svc/kube-prometheus-stack-prometheus 9090:9090
```

4. **Tunnel to Loki:**

```shell
kubectl port-forward svc/loki 3100:3100
```

5. **Tunnel to Hubble:**

```shell
kubectl port-forward svc/hubble-ui -n kube-system 12000:80
```

**Tools available:**

- **Prometheus** — metrics collection (runs in-cluster)
- **Loki** — log aggregation (runs in-cluster, persists logs to S3)
- **Grafana** — dashboards (run locally, connect to Prometheus and Loki via port-forward)
- **Hubble** — network flow visualisation (runs in-cluster, CLI available locally)

**Check blob storage usage:**

```shell
# AWS
aws s3 ls s3://your-bucket/acceptancetests/ --recursive --summarize | grep "Total Size"

# GCP
gcloud storage du -s gs://your-bucket/acceptancetests/**

# Azure
az storage blob list --account-name yourstorageaccount --container-name yourstorage \
  --prefix acceptancetests/ --query "[].properties.contentLength" \
  --output tsv | awk '{sum+=$1} END {printf "%.2f kb\n", sum/1024}'
```

### Production teardown and recovery

**Normal teardown:**

```shell
cd cdktf.out/stacks/aws
tofu destroy --auto-approve
```

**When Terraform state is corrupted or resources are stuck:**

```shell
# Generate nuke scripts
cd afon-infra
mvn compile -Dexec.mainClass=health.emrys.afon.cdk.AwsNuke exec:java
# Run the output script (may need multiple passes for VPCs to disappear)

mvn compile -Dexec.mainClass=health.emrys.afon.cdk.AzureNuke exec:java
```

**When Azure Terraform import is needed:**

```shell
tofu import azurestack_resource_group.XXX \
  /subscriptions/YOUR_SUB/resourceGroups/your_resource_group_aks
```

If the import fails with `Invalid provider configuration`, temporarily comment out the offending provider lines in the generated JSON and retry.

**When Azure resource group is stuck:**

```shell
az group delete --name afon_resource_group_aks --yes --no-wait
```

**When AWS VPCs won't delete:**

The `AwsNuke` output script patches Kubernetes finalizers, deletes load balancers, internet gateways, VPC endpoints, NAT gateways, network interfaces, subnets, route tables, security groups, and network ACLs in sequence. Run it twice if needed.

### GitOps considerations

Currently, the generated Terraform can contain confidential details (secrets, credentials). Before adopting a GitOps workflow where Terraform state is checked into Git:

1. **Separate secrets** — move all sensitive values into Kubernetes secrets or a secrets manager (Vault, AWS Secrets Manager, etc.) rather than embedding them in CDKTF output
2. **Use remote state** — store Terraform state in a remote backend (S3 + DynamoDB for AWS, Azure Storage for Azure, GCS for GCP) with encryption
3. **CI/CD pipeline** — run `tofu plan` on PRs and `tofu apply` on merge to main
4. **State locking** — ensure concurrent runs are prevented via state locking

This is noted as a future improvement in the project.
