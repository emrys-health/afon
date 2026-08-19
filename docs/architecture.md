# Architecture overview

Afon's architecture has four layers:

```
┌─────────────────────────────────────────────────────────┐
│  Query Engines                                          │
│  (Spark / Snowflake / Trino)                            │
└──────────────────────┬──────────────────────────────────┘
                       │ REST API (Iceberg REST Catalog)
┌──────────────────────▼──────────────────────────────────┐
│  Apache Polaris                                         │
│  (runs in Kubernetes, one per cloud)                    │
│  - Catalog management (internal + federated/external)   │
│  - Credential vending (short-lived tokens)              │
│  - Metadata stored in external Postgres                 │
└──────────────────────┬──────────────────────────────────┘
                       │ Vended credentials
┌──────────────────────▼──────────────────────────────────┐
│  Cloud Storage                                          │
│  (AWS S3 / GCP GCS / Azure Blob Storage)              │
│  - Iceberg table data files (Parquet)                   │
│  - Iceberg metadata files (manifests, snapshots)        │
└─────────────────────────────────────────────────────────┘
```

## Security model

- **Polaris authentication** — OAuth2 client credentials flow. The `POLARIS_ID` and `POLARIS_SECRET` properties are used to obtain access tokens.
- **Credential vending** — Polaris does not expose long-lived cloud credentials to query engines. Instead, it vends short-lived credentials scoped to the specific table being accessed. This is the `VENDED_CREDENTIALS` access delegation mode used in Snowflake catalog integrations.
- **Forbidden buckets** — Each cloud has a "forbidden" bucket that the IAM roles do NOT grant access to. Acceptance tests verify that Polaris correctly denies access to these buckets.
- **Jupyter access** — Jupyter is served via Apache Guacamole (pixel streaming via X11 VNC) behind an OAuth2 proxy that delegates to Google. This requires `OAUTH2_CLIENT_ID`, `OAUTH2_CLIENT_SECRET`, and `OAUTH2_COOKIE_SECRET`.
- **Network policies** — Kubernetes network policies restrict pod-to-pod communication. The `NetworkConfiguration` class sets up policies between Jupyter, Spark, and Polaris pods.

### Polaris client

`PolarisRestClient` is the HTTP client for talking to Polaris REST APIs. It handles:

- **Authentication** — OAuth2 client credentials flow to get an access token (with retry logic for intermittent 503s)
- **Catalog management** — POST to create catalogs, PUT to update, DELETE to remove
- **Namespace and table operations** — CRUD via REST endpoints
- **JSON calls** — POST/PUT with JSON payloads for catalog configuration

`PolarisConfigurer` builds on top of the client to provide higher-level operations: creating catalogs with admin grants, configuring principal roles, and creating users.

`PolarisCatalog` is the abstract base for per-cloud catalog configuration. Each cloud has a concrete subclass (e.g. `AwsPolarisCatalog`, `GcpPolarisCatalog`, `AzurePolarisCatalog`) that knows how to generate the JSON payload for creating a catalog in that cloud, including the correct storage configuration info and bucket URI.

### Spark integration

Spark connects to Polaris via the Iceberg REST catalog protocol. `SparkWarehouseConfig` builds the Spark configuration with:

- The Polaris endpoint URI
- OAuth2 credentials (`client_id` / `client_secret`)
- Warehouse names for each cloud catalog

`SparkSessionFactory` creates a `SparkSession` configured to talk to a Polaris instance. It can discover the Polaris endpoint by interrogating the Kubernetes cluster (`PolarisClusterCredentials`) or use an explicit endpoint.

In the deployed environment, Spark runs as a Spark Connect server in Kubernetes. Jupyter notebooks connect to it via the Spark Connect protocol (`sc://spark-connector-0-driver-svc:15002`).

## Properties management

Runtime properties are loaded via the `FilePropertyProvider` from the config dir.   

The config dir is defined relative to the `afon.home` system property.  If this is not set, it will default to the top-level dir of the afon-core project. 

All known properties are defined as `AfonProperty` constants in `AfonProperties`. Each property knows its own name and can extract its value from a `Properties` object.

System properties can override any property value.

The `AfonCloud` enum defines the cloud types that Polaris can manage, used throughout the codebase to select the right storage configuration and credential vending logic.

### Executable step system

Executables are composed of reusable steps:

- **`Step`** — a named unit of work with a description and a runnable action
- **`StepOperations`** — factory methods that create `Step` instances for common operations (clean, deploy, wait, configure, test)
- **`StepActions`** — low-level operations that actually execute commands (run Maven, run OpenTofu, call Polaris APIs)
- **`StepRunner`** — executes a list of steps in order, with the ability to resume from a named step
- **`StepsProvider`** — interface implemented by `Deploy*Main` classes to provide their step list

Each `Deploy*Main` class composes steps from `StepOperations`. 

For example, `DeployExternalCatalogsMain` reuses all steps from `DeployAwsFullStackMain` and then adds Azure-specific steps.

The corresponding `Document*Main` classes generate markdown documentation describing each step — this keeps documentation in lock-step with the actual deployment code.

### Terraform-based deployment

The afon-cdk module generates CDKTF (Cloud Development Kit for Terraform) files.

OpenTofu then provisions the actual infrastructure — Kubernetes clusters, deployments, services, and ingress controllers.
The Java code does not directly create cloud resources.

After the CDKTF step, you can inspect the generated Terraform in `afon-ops/cdktf.out/stacks/<cloud>/` and run `tofu apply` or `tofu destroy` directly.

