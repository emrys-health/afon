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

### Polaris catalog and user management

`PolarisRestClient` is the HTTP client for talking to Polaris REST APIs. It handles:

- **Authentication** — OAuth2 client credentials flow to get an access token. I/O and server (5xx) errors are retried up to 3 times, 5s apart, to ride out intermittent 503s. Any other failure (e.g. 401 on bad credentials), or a response with no access token, fails immediately with the HTTP code and response body in the exception. Only the client ID is logged on retrieval, never the client secret or the token.
- **Catalog/User management** — POST to create catalogs/users, PUT to update, DELETE to remove
- **Namespace and table operations** — CRUD via REST endpoints

`CatalogConfigurer` builds on top of the `PolarisRestClient` to provide higher-level operations and the REST API URIs for creating catalogs with admin grants, configuring principal roles, and creating users.

The `CatalogConfigurer` asks before it deletes or creates a catalog, catalog role, principal or principal role, with
`RestClient.exists` (a 200 is present and a 404 absent, neither logged as a warning, and any other code is raised, never
read as absent), and skips the call when there is nothing to do, returning a successful response so that its callers
and the failure checks are unaffected. So rerunning the configuration leaves what exists as it is, and a warning is
a real failure. Polaris answers the GET of a catalog role that is absent with a 500, so the roles of the catalog list
are checked instead. A principal is created only if absent, which keeps its credentials; `recreatePrincipal` is for
a test fixture that needs new ones.

`PolarisCatalogManager` and `PolarisUserManager` build on top of the `CatalogConfigurer` to provide catalog and user specific configuration. 
`PolarisUserManager` gives each user their own principal role (`<username>Principal`), which is granted the catalog role
(`emrysrole4`) of only the catalogs `users-acl.csv` authorises the user for, so that no user can use a catalog authorised for another.

`PolarisCatalog` is the abstract base for per-cloud catalog configuration. Each cloud has a concrete subclass (e.g. `AwsPolarisCatalog`, `GcpPolarisCatalog`, `AzurePolarisCatalog`) that knows how to generate the JSON payload for creating a catalog in that cloud, including the correct storage configuration info and bucket URI.

### Spark integration

Spark connects to Polaris via the Iceberg REST catalog protocol. `SparkWarehouseConfig` builds the Spark configuration with:

- The Polaris endpoint URI and the KeyCloak token endpoint (`oauth2-server-uri`)
- The authentication: either OAuth2 client credentials (`client_id` / `client_secret`), or the user's OIDC token
- Warehouse names for each cloud catalog

`SparkClient` creates a local, in-process `SparkSession` configured to talk to a Polaris instance with client credentials.

In the deployed environment, Spark runs as a Spark Connect server in Kubernetes. Jupyter notebooks connect to it via the Spark Connect protocol (`sc://spark-connector-0-driver-svc:15002`).
The Spark Connect server is shared by all users, so it registers no catalogs and holds no credentials.
Each session declares its own catalogs, authenticating with the user's OIDC token, which JupyterHub injects into the user's pod as the `OIDC_TOKEN` environment variable:

```python
OIDC_TOKEN = os.environ["OIDC_TOKEN"]
spark = (SparkSession.builder.remote("sc://spark-connector-0-driver-svc:15002")
    .config("spark.sql.catalog.aws", "org.apache.iceberg.spark.SparkCatalog")
    .config("spark.sql.catalog.aws.type", "rest")
    .config("spark.sql.catalog.aws.uri", "https://<polaris-host>/api/catalog")
    .config("spark.sql.catalog.aws.warehouse", "aws")
    .config("spark.sql.catalog.aws.header.X-Iceberg-Access-Delegation", "vended-credentials")
    .config("spark.sql.catalog.aws.oauth2-server-uri", "https://<keycloak-host>/realms/polaris/protocol/openid-connect/token")
    .config("spark.sql.catalog.aws.scope", "openid")
    .config("spark.sql.catalog.aws.token", OIDC_TOKEN)
    .getOrCreate())
```

`SparkConnectClient` does the same from Java, and `GeneratePySparkContextMain` / `GeneratePySparkKeycloakMain` generate this code for all the configured catalogs.

## Properties management

Runtime properties are loaded via the `AfonPropertyProvider`.   

All known properties are defined as `AfonProperty` enum, in which each property knows its own name and can extract its value from a `Properties` object.  

The `FilePropertyProvider` loads the properties from the properties files in the config dir.
The config dir is defined relative to the `afon.home` system property. If this is not set, it will default to the top-level dir of the afon-core project.  System properties can override any property value.

The `DefaultPropertyProvider` decorates the `FilePropertyProvider` allows the specification of a number of classes which can
contain property defaults as constants, ie public static `String` fields where the name and value of the field map to the name and value of the `AfonProperty`.  The `DefaultPropertyProvider` will use the defaults defined in the constants classes if the property is not found by the `FilePropertyProvider`.   The loading of the constants classes is done at runtime.

The `AfonCloud` enum defines the cloud types that Polaris can manage, used throughout the codebase to select the right storage configuration and credential vending logic.

### Executable step system

Executables are composed of reusable steps:

- **`Step`** — a named unit of work with a description and a runnable action
- **`StepOperations`** — factory methods that create `Step` instances for common operations (clean, deploy, wait, configure, test)
- **`StepActions`** — low-level operations that actually execute commands (run Maven, run OpenTofu, call Polaris APIs)
- **`StepRunner`** — executes a list of steps in order, with the ability to resume from a named step
- **`StepsProvider`** — interface providing a step list
- **`StackPlanFactory`** — the steps of each action (`delete`, `deploy`, `setup`) on each stack, keyed by stack id (`<cloud>-<stack-type>`, eg `aws-keycloak`), derived from the components of the stack (see [Steps derived from the components](#steps-derived-from-the-components)) and run by the `manage-stack` command

What spans stacks or clouds, eg Snowflake reading a catalog, or AWS federating to the Azure Polaris, is a scenario declared by the
component plan (`ComponentPlan.scenarioSteps`) and run on a set up stack by `manage-stack run <stack-id> <scenario>`.  The other ops
commands compose their steps from `StepOperations` directly.

The documents in `ops.docs` generate markdown documentation describing each step — this keeps documentation
in lock-step with the actual deployment code.  There is one generic document class per kind: `StackDocument` (an action on a stack), `ScenarioDocument` and `FlowDocument`.  `DocumentFinder` makes one of each from `StackPlanFactory` and `StackFlows`, so  a new stack, scenario or flow is documented with nothing written per document, and the docs list the steps that `manage-stack` runs.  
They are bundled in the jar, to show with `manage-stack <action> <stack-id> -h`.

### Steps derived from the components

`manage-stack` takes its steps from `StackPlanFactory` (package `ops.plan`), which derives them from the `StackDefinition` of the
stack, so that every stack of `StackDefinitions` can be managed with nothing written per stack.

The stack is deployed **as a whole**, by one step (`StepOperations.deployStack`) applying the plan of all its components.
A component's `ComponentPlan` does **not** deploy it, but decorates its deployment: it says what the component brings to the
commands on the stack, besides being deployed with it:

- `domains`: the domain names it holds, which only one running stack can serve, so stacks holding a common one conflict;
- `certificates`: the certificates it needs (AWS), checked before the stack is deployed;
- `waitsFor`: what must answer once the stack is deployed, the union over all components being waited for;
- `setupSteps`: what it configures when the stack is set up, in the order of the components, without duplicates;
- `scenarioSteps`: the named scenarios it can run on a stack that is set up, by `manage-stack run` (see below), listed in the order of the components;
- `label`: its name in messages.

The defaults bring nothing, which is what the infrastructure-only components are (`InfrastructurePlan`).  Some behaviour comes
from absence: a stack with no `KeycloakComponent` uses the KeyCloak of another stack, so it checks that it answers.
Every kind of component must be declared in `ComponentPlanRegistry`, even if it brings nothing, which a test enforces.

For example, `setup` of `aws-full` runs: delete the stacks conflicting with it, check the certificates (KeyCloak, Polaris,
Jupyter), deploy the stack, wait for Polaris and KeyCloak, report the deployment complete, configure the OIDC users, create the
catalogs, configure the Polaris users, run the Spark write check, and report the setup complete.

### Stack composition

**Stack id and stack name.** A *stack id* (`StackId`) is `<cloud>-<stack-type>`, eg `aws-full`, and always identifies a stack that
`stacks.yaml` defines. A *stack name* is a `String` naming a stack dir (`stacks/<name>`) or tofu state
(`terraform.<name>.tfstate`) found in the cdktf dir, which `manage-stack` looks at when it deletes stacks or checks for
conflicts. It is usually the text of a stack id, but may also be the name of a cloud, eg `aws`, for the *legacy stack* that all the
stacks of the cloud shared before they had their own state, or of a stack that is no longer defined, eg by the `stacks.yaml` in
use. Neither can be managed as a stack, so each is treated as possibly any stack: it conflicts with every stack of the cloud,
and its resources are unknown. Hence a name is parsed into a stack id only once it is known to be neither.

A stack is a `StackId` and the ordered list of components
deployed to that cloud, as defined in `StackDefinitions`. `ComposedStack` builds it: it sets up the cloud's
platform, then deploys each component in turn. Adding a stack, eg a subset of the components, or on another cloud, is
adding an entry to `stacks.yaml`, which `StackDefinitions` loads at runtime.

| Concept | What it is |
|---|---|
| `StackComponent` | A part of a stack, eg `PolarisComponent`, `JupyterComponent`, `SparkComponent`, `TrinoComponent`, written once for every cloud (package `components`) |
| `StackContext` | What a component is deployed with: the Terraform stack, the `StackId`, the platform, the config, and the components deployed before it, which it finds with `find(Class)` or `require(Class)` |
| `CloudPlatform` | What a component needs of its cloud, implemented by the AWS, Azure and GCP providers. The cloud's cluster, and what is done differently per cloud, eg `exposeHttps(HttpsRoute)`: an ALB with a Route 53 record on AWS, the nginx ingress with cert-manager and a DNS record on Azure and GCP |
| `StackDefinition` | The id of a stack and how to create its components from the config, checked by `componentsFor(properties)` |

The stacks are defined in `afon-cdk/src/main/resources/stacks.yaml`, bundled in the jar. A stack may have a `role` (`StackRole`:
`catalog`, `identity` or `tre`), which the flows and the ops checks look it up by (`StackDefinitions.withRole`), and not by id, so
the code names no stack id: a flow also finds the stack deploying the Snowflake component with `StackDefinitions.declaring`. A stack may `extends` another of its cloud (stacks of different clouds are independent), `pinnedEgressIp` says whether it holds `AWS_EGRESS_IP`, and a component is listed by name (see
`StackComponents.names()`), optionally `when` or `unless` a boolean property of the stack's cloud, eg
`unless: KEYCLOAK_EXTERNAL` for `<CLOUD>_KEYCLOAK_EXTERNAL`. To define the stacks differently, put a `stacks.yaml` in the config dir
(`$AFON_HOME/config`), which replaces the bundled one entirely rather than adding to it, so start from a copy of it; a file that
is not valid fails with a message naming the file and the problem.

The stacks, as composed by `StackDefinitions`:

| Stack | Components, in order |
|---|---|
| `aws-keycloak` | Keycloak |
| `aws-polaris` | Polaris |
| `aws-full` | Keycloak (unless `AWS_KEYCLOAK_EXTERNAL`), Polaris, Jupyter, NetworkPolicies, SparkOperator, Spark, CrdCleanup, Trino, Monitoring, AwsLogging, Git |
| `aws-snowflake` | as `aws-full`, then Snowflake, a query engine outside of the stack, so it deploys nothing |
| `azure-keycloak` | Keycloak |
| `azure-polaris` | Polaris |
| `azure-full` | The components of `aws-full`, listed on their own, but for AwsLogging (AWS only), with its own Keycloak unless `AZURE_KEYCLOAK_EXTERNAL` |
| `gcp-polaris` | Polaris |

**Synthesising a stack.** `ComposedStack.synthesise(StackId, AfonPropertyProvider)` is the only way into the CDKTF world, and it
needs nothing but a stack id, registered in `StackDefinitions`, and the config. It
1. looks the id up in `StackDefinitions`, failing with `No such stack: <id>` if it is not registered,
2. creates the CDKTF app, whose output dir is the Afon cdktf dir (`$AFON_CDKTF`),
3. builds the `ComposedStack` of the definition: it sets up the platform of the cloud, then deploys the components in order,
   after checking that they are composed correctly (`requires()` and `after()`), and
4. synthesises it, which writes the plan `stacks/<stack-id>/cdk.tf.json` and its assets, with the local tofu backend pointing at
   `terraform.<stack-id>.tfstate`, but never touches that state.

No code of Afon outside the tests creates a CDKTF app or a stack: there is no class or main per stack, so adding a stack to
`StackDefinitions` is all that it takes to synthesise and validate it. (`manage-stack` derives its steps from the components, so nothing
else needs registering.) The test `DeterministicSynthesisTest` builds the same `ComposedStack`
in a temp dir. The plan is a function of the stack id and the config alone, which is why it must be deterministic.
`ComposedStack.main(<stack-id>)` runs it by hand, from the config dir.

It is also how stacks are validated. `CloudStackValidator.validate(stackId, properties)` calls it, so validating a stack is
synthesising its plan, without any login to the cloud and without running `tofu`. That checks that the stack is registered, that the
config has every property its components need for that cloud, that the components are composed correctly, and that
CDKTF accepts the result. The validator is used by:
- `afon-cli.sh validate-stack <stack-id>`, which lists the registered stacks when run without one;
- `StepOperations`, with the config it was created with, so that a step is validated against the config it runs with. It
  synthesises the plan before starting a stack (`tofu` applies the plan it has just written), and before deleting a deployed
  stack, so that `tofu destroy` runs on a current plan and fails early, before anything is destroyed, if the stack no longer
  validates, eg because a property is missing.

The flow, from the commands to the plan:

```
  manage-stack {setup|deploy|delete} <stack-id>               validate-stack <stack-id>
              │                                                          │
              ▼                                                          │
  ┌──────────────────────────────────────────┐                           │
  │ StepOperations                           │                           │
  │ holds the AfonPropertyProvider of a run  │                           │
  │ - before starting a stack                │                           │
  │ - before deleting a deployed stack       │                           │
  └────────────────────┬─────────────────────┘                           │
                       │ synthesiseStack(stackId, properties)            │
                       ▼                                                 │
  ┌──────────────────────────────────────────┐                           │
  │ StepActions.synthesiseStack              │                           │
  │ (`afon-cli.sh validate-stack <stack-id>`)│                           │
  └────────────────────┬─────────────────────┘                           │
                       │ validate(stackId, properties)                   │ validate(stackId, properties)
                       ▼                                                 ▼
  ┌────────────────────────────────────────────────────────────────────────────┐
  │ CloudStackValidator.validate(stackId, properties)                          │
  └────────────────────────────────────┬───────────────────────────────────────┘
                                       │ synthesise(stackId, properties)
                                       ▼
  ┌────────────────────────────────────────────────────────────────────────────┐
  │ ComposedStack.synthesise(stackId, properties)   the only entry to CDKTF    │
  │  1. StackDefinitions.get(stackId)               fails if not registered    │
  │  2. new ComposedStack(app, definition, properties)                         │
  │       - the cloud's platform (AWS / Azure / GCP cloud provider)            │
  │       - the components, in order, checked: requires() and after()          │
  │  3. app.synth()                                                            │
  └────────────────────────────────────┬───────────────────────────────────────┘
                                       ▼
                 $AFON_CDKTF/stacks/<stack-id>/cdk.tf.json
                 (the tofu state terraform.<stack-id>.tfstate is not touched)
                                       │ only when starting a stack
                                       ▼
                 StepActions.startStack: tofu init, tofu apply
```

**Config per cloud.** A component asks for the logical name of a property and gets the one of its cloud, by the
convention `<CLOUD>_<NAME>`, eg `getCloudValue(cloud, "POLARIS_DOMAIN_NAME")` reads `AWS_POLARIS_DOMAIN_NAME` on AWS and
`AZURE_POLARIS_DOMAIN_NAME` on Azure. The properties are still declared in `AfonProperty`. A property the cloud does
not have, eg the Route 53 zone on Azure, is an error, or the default when one is given.

**What a component needs of the others** is declared, not implied by the order: `requires()` lists the components that
must be in the stack before it (`SparkComponent` requires `SparkOperatorComponent`), and `after()` those that, if the
stack has them, must come before it (`PolarisComponent` is after `KeycloakComponent`, as Polaris does OIDC discovery
against it). A stack composed wrongly fails when it is built, naming the problem, eg
`Stack aws-test needs SparkOperatorComponent before SparkComponent`. `StackCompositionTest` checks every stack.

**Deterministic plans.** What goes into a plan must be the same on each synthesis, or `tofu` sees a changed string and replaces
what holds it, eg a `kubectl` manifest, on every deploy. The iteration order of an immutable `Map.of` differs from one JVM run
to the next, so JSON embedded in the plan, eg a manifest or Helm values, is written by `CdkUtils.toCanonicalJson`, which sorts the keys of
maps, and the jars are built with the entries in order of their names and no timestamps. `DeterministicSynthesisTest` synthesises
every stack in three separate JVMs and requires the same plan from each.

**Adding a component.**
1. Implement `StackComponent` in `cdk.components`, taking its config from `context.properties()` by cloud, and asking
   `context.platform()` for what is cloud-specific, eg `exposeHttps`.
2. If a cloud needs something new of its platform, add a method to `CloudPlatform` with a default that fails clearly on
   the clouds that don't implement it, as `logStorage()` does for the monitoring.
3. Declare `requires()` and `after()`.
4. Add it to the component names in `StackDefinitions` and to the stacks in `stacks.yaml`.

**Guarding the plans.** `validate-stack [--runs <n>]` synthesises the stack in `<n>` JVMs (defaulting to `3`), one after the other, and fails if the plans differ, giving the paths that differ but not their values, which may contain secrets. A plan must not depend on anything that differs from one JVM to the next, eg the iteration order of an immutable `Map.of`, as `tofu` replaces whatever holds a string that differs between runs, on every deploy. The `aws-stack-linux.yml` workflow runs it before it deploys. The ids of the Terraform resources are in the state of the deployed stacks, so renaming one makes `tofu` destroy and recreate it: check the plans before and after a refactoring, with snapshots of the synthesised plans that are not kept in git:

```shell
./afon.sh -a validate-stack aws-keycloak      # synthesises the stack into the stacks dir, repeat for the stacks to check
./afon.sh -a snapshot-stacks /tmp/plans-before
# ... change the code ...
./afon.sh -a validate-stack aws-keycloak
./afon.sh -a snapshot-stacks /tmp/plans-after
./afon.sh -a compare-stacks /tmp/plans-before /tmp/plans-after
```

`snapshot-stacks <dir> [<stack-id>...]` copies to `<dir>/<stack-id>/cdk.tf.json` the plan of each stack that was synthesised, or of those given, and nothing else. It needs no source tree or git, so it runs the same with `afon-cli.sh`, on a deployed machine, and `compare-stacks` takes any two such dirs, or the stacks dirs themselves. It lists the resources that were added or removed  (a renamed one is both, and `tofu` would destroy and recreate it), then the values that changed, with the long ones reduced to their hash, and exits 1 if the plans differ.

### Terraform-based deployment

The afon-cdk module generates CDKTF (Cloud Development Kit for Terraform) files.

OpenTofu then provisions the actual infrastructure — Kubernetes clusters, deployments, services, and ingress controllers.
The Java code does not directly create cloud resources.

After the CDKTF step, you can inspect the generated Terraform in `$AFON_CDKTF/stacks/<stack-id>/`, eg `aws-keycloak`, and run `tofu apply` or `tofu destroy` directly.

