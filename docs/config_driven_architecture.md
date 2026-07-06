# Config-Driven Multi-Cloud Architecture — Design Proposal

**Status:** Draft for team review
**Scope:** `afon-infra`, `afon-config`, new `afon-ui` module
**Goal:** Support an arbitrary number of clouds hosting data, a selectable query
engine (Spark, Trino, Snowflake), a selectable workbench (Jupyter, VS Code,
RStudio), all driven by a human-readable configuration file that can be
generated from a user interface.

---

## 1. Motivation

Afon today deploys Polaris across AWS, Azure and GCP and lets Spark and
Snowflake query Iceberg tables across clouds. The deployment topology, however,
is expressed in *code*, not *data*:

- Each topology is a `Deploy*Main` class (`DeployFullStackAwsMain`,
  `DeployAzurePolarisMain`, `DeployExternalCatalogsMain`, ...).
- Each cloud is a hand-written inheritance chain
  (`AwsWholeStack extends AwsKubernetesPolaris extends AwsKubernetesStack`).
- The set of clouds is the closed enum `SupportedCloudsEnum`.

Adding a fourth cloud, a second AWS account, or making Trino selectable
requires new Java classes and a recompile. The goal of this design is to invert
that: a declarative configuration file describes the deployment, and the Java
code interprets it.

## 2. Review of the current codebase

### 2.1 What already works in our favour

| Asset | Where | Why it helps |
|---|---|---|
| `CloudProvider` interface | `afon-infra/.../cdk/CloudProvider.java` | Already abstracts `configureK8s()`, `getDependables()` — the seed of a plugin API |
| `EmrysCloud` interface | `afon-infra/.../cdk/EmrysCloud.java` | Per-cloud identity + Polaris domain name |
| `PolarisConfig` | `afon-infra/.../cdk/PolarisConfig.java` | Cloud-agnostic Polaris deployment; takes a `CloudProvider` |
| `SparkWorkloads` | `afon-infra/.../cdk/SparkWorkloads.java` | Cloud-agnostic Spark operator CRs, parameterised by Polaris domain |
| `TrinoWorkloads` | `afon-infra/.../cdk/TrinoWorkloads.java` | Cloud-agnostic Trino Helm release, one Iceberg REST catalog per cloud |
| `Step` / `StepOperations` / `StepRunner` | `afon-infra/.../demo/` | Composable, resumable execution engine — ideal target for a config-driven planner |
| `CloudConfigForPolaris` subclasses | `afon-polaris` | Per-cloud catalog JSON generation already polymorphic |

### 2.2 Where the abstraction breaks down

1. **Closed cloud enum.** `SupportedCloudsEnum` (in
   `afon-config/.../AfonProperties.java`) conflates four concepts:
   - cloud provider (`AWS`, `GCP`, `AZURE`)
   - catalog role (`AZURE_EXTERNAL`, `GCP_EXTERNAL`)
   - test fixtures (`*_FORBIDDEN`)
   - engine variants (`AWS_TRINO`)

   Because CDKTF stack IDs are enum values, at most one deployment per cloud
   provider can exist. "Arbitrary number of clouds" is structurally blocked.

2. **Flat, prefixed properties.** `AWS_S3_BUCKET_NAME`, `GCP_BUCKET_NAME`,
   `AZURE_BUCKET` are singletons per provider. Two S3 buckets in different
   accounts/regions cannot be expressed.

3. **Hardcoded composition.** `AwsWholeStack` unconditionally deploys Jupyter;
   `SparkClusterDeployment` unconditionally deploys Spark *and* Trino (AWS
   only); Azure/GCP never get Trino. Engine and workbench selection does not
   exist — it is inheritance.

4. **Topology lives in `main` classes.** Every new combination of
   clouds/engines/tests needs a new `Deploy*Main` + `Document*Main` pair.

5. **`Config.yaml` at the repo root is an empty stub** — evidence this
   direction was already anticipated but never built.

## 3. Target architecture

```
┌──────────────────────────────────────────────────────────────┐
│  afon-ui  (React SPA, no cloud credentials)                  │
│  cloud / engine / workbench pickers → validation → YAML      │
└───────────────────────────┬──────────────────────────────────┘
                            │ afon-deployment.yaml (checked in / reviewed)
┌───────────────────────────▼──────────────────────────────────┐
│  DeploymentPlanner (afon-infra)                              │
│  parse → validate → resolve plugins → emit Step list         │
└───────────────────────────┬──────────────────────────────────┘
                            │ List<Step>
┌───────────────────────────▼──────────────────────────────────┐
│  StepRunner (existing) → CDKTF synth → OpenTofu apply        │
└──────────────────────────────────────────────────────────────┘
```

Three new plugin interfaces sit between the planner and the existing CDKTF
constructs:

- `CloudProviderPlugin` — provision cluster, DNS, storage; N instances allowed
- `QueryEngineDeployer` — Spark / Trino / Snowflake, 0..n per deployment
- `WorkbenchDeployer` — Jupyter / VS Code / RStudio, 0..1 per deployment

## 4. The configuration file

### 4.1 Format choice

**YAML** with a published JSON Schema.

- Human readable and diff-able in code review (a hard requirement).
- JSON Schema gives us the same validation in the UI (client-side) and in the
  Java planner (via `networknt/json-schema-validator`), so the two can never
  drift.
- Secrets are **not** in this file. It references the existing
  `env.properties` mechanism by key name only.

### 4.2 Schema (v1)

```yaml
# afon-deployment.yaml
apiVersion: afon/v1
deployment:
  name: emrys-prod
  clusterName: spark-cluster-wb2

clouds:                              # 1..n — a LIST, not an enum
  - id: aws-primary                  # free-form, unique; becomes the stack ID
    provider: aws                    # aws | azure | gcp (registry, extensible)
    region: eu-west-2
    storage:
      bucket: emrys-afon-bucket
      roleArn: arn:aws:iam::123456789:role/S3ReadWriteRole
    polaris:
      domain: emryspolaris.click
      certArn: arn:aws:acm:...       # provider-specific keys allowed here
      dnsZone: Z0524976JC9Y8Y0BLXV2
  - id: azure-uk
    provider: azure
    region: uksouth
    storage:
      storageAccount: afonstorageaccount
      container: afonstorage
    polaris:
      domain: emryspolarisazure.click
      resourceGroup: afon_resource_group
  - id: gcp-eu
    provider: gcp
    region: europe-west2
    storage:
      bucket: emrys_afon_core
      serviceAccount: afon@afon-core.iam.gserviceaccount.com
    polaris:
      domain: emryspolarisgcp.click

catalogs:                            # decoupled from clouds
  - name: aws
    cloud: aws-primary
    type: internal
  - name: azure_external
    cloud: azure-uk
    type: federated                  # replaces AZURE_EXTERNAL enum value
    via: aws-primary                 # which Polaris proxies the requests
  - name: aws_forbidden
    cloud: aws-primary
    type: internal
    bucketOverride: emrys-afon-forbidden   # test fixtures become plain config
    testFixture: true

queryEngines:                        # 0..n, each targets one cloud's cluster
  - type: spark
    target: aws-primary
    workers: 2
  - type: trino
    target: aws-primary
    workers: 2
  - type: snowflake                  # SaaS — no target cluster
    jdbcUrlRef: SNOWFLAKE_JDBC_URL   # *Ref keys resolve via env.properties
    readsCatalogs: [gcp, azure_external]

workbench:                           # 0..1
  type: jupyter                      # jupyter | vscode | rstudio
  target: aws-primary
  domain: emrysjupyter.click
  auth:
    provider: google-oidc
    emailDomain: emrys.health
    clientIdRef: OAUTH2_CLIENT_ID    # secret indirection, never inline

secretsFile: env.properties          # unchanged mechanism
```

### 4.3 Validation rules (enforced in UI and planner)

- `clouds[].id` unique; every `catalogs[].cloud`, `queryEngines[].target`,
  `workbench.target` must reference a declared cloud id.
- `type: federated` requires `via` referencing a *different* cloud.
- `snowflake` engine requires `jdbcUrlRef`; forbids `target`.
- `workbench` requires at least one `spark` engine if `type: jupyter` and the
  notebook templates use Spark Connect (warn, not error).
- Any `*Ref` key must name a property present in `env.properties.example`.

## 5. Java abstraction layer

New package: `health.emrys.afon.platform` in `afon-infra`.

### 5.1 `CloudProviderPlugin`

```java
public interface CloudProviderPlugin {
    String providerName();                        // "aws", "azure", "gcp"

    /** Provision cluster + networking for one CloudSpec instance. */
    ProvisionedCloud provision(Construct scope, CloudSpec spec);
}

public interface ProvisionedCloud {
    String cloudId();                             // spec.id, e.g. "aws-primary"
    List<ITerraformDependable> dependables();
    KubernetesProvider k8sProvider();
    HelmProvider helmProvider();
    void configureDns(IngressV1 ingress, String domain);
    StorageConfig storageConfig();                // feeds CloudConfigForPolaris
}
```

Key changes vs today:

- Keyed by the free-form `cloudId` string, **not** `SupportedCloudsEnum`, so
  CDKTF stack IDs become `spec.id` and N deployments per provider work.
- `AwsConstruct`, `AzureConstruct`, `GcpConstruct` are refactored to be the
  first three `ProvisionedCloud` implementations. Their internals barely
  change; only property lookups move from static `AfonProperties` constants to
  the `CloudSpec` record.
- Plugins register in a `CloudProviderRegistry` (a simple
  `Map<String, CloudProviderPlugin>`; `ServiceLoader` if we later want plugins
  outside the repo).

### 5.2 `QueryEngineDeployer`

```java
public interface QueryEngineDeployer {
    String engineType();                          // "spark", "trino", "snowflake"
    void deploy(Construct scope, ProvisionedCloud target,
                EngineSpec spec, DeploymentContext ctx);
}
```

- `SparkEngineDeployer` — wraps existing `SparkConfig` (operator) +
  `SparkWorkloads` (CRs). No behaviour change.
- `TrinoEngineDeployer` — wraps `TrinoWorkloads`. Becomes available on *any*
  cloud, not just AWS; the catalog list is derived from `catalogs:` in the
  config instead of iterating the enum.
- `SnowflakeEngineDeployer` — Snowflake is SaaS, so "deploy" means executing
  the catalog-integration SQL that `DeploySnowflakeReading*Main` classes
  generate today. `target` is absent; it consumes `readsCatalogs`.

### 5.3 `WorkbenchDeployer`

```java
public interface WorkbenchDeployer {
    String workbenchType();                       // "jupyter", "vscode", "rstudio"
    void deploy(Construct scope, ProvisionedCloud target,
                WorkbenchSpec spec, DeploymentContext ctx);
}
```

All three follow the pattern `JupyterConfig` already implements:
**Deployment + ClusterIP Service + nginx ingress + TLS + oauth2-proxy**.

| Workbench | Image | Port | Notes |
|---|---|---|---|
| Jupyter | existing Guacamole/VNC stack (`JupyterConfig`, `GuacamoleConfig`) | 8080 | unchanged; extract from `AwsWholeStack`/`AzureWholeStack` constructors into `JupyterWorkbench` |
| VS Code | `codercom/code-server` | 8080 | native web UI — simpler than Guacamole; auth via oauth2-proxy in front |
| RStudio | `rocker/rstudio` | 8787 | disable internal auth (`DISABLE_AUTH=true`) and rely on oauth2-proxy, same as the others |

The oauth2-proxy sidecar/deployment logic in `JupyterMfaConfig` is generic and
should be extracted to a shared `AuthProxyConfig` used by all three.

Workbench-to-engine connectivity reuses `NetworkConfiguration.networkPolicies`
— parameterise the pod labels instead of hardcoding Jupyter's.

### 5.4 `DeploymentPlanner` and `DeployFromConfigMain`

```java
public final class DeploymentPlanner {
    public DeploymentPlan plan(Path configFile) {
        AfonDeploymentConfig cfg = ConfigParser.parse(configFile);  // + schema validation
        // resolve plugins, order steps:
        // per cloud:  clean? -> provision -> polaris -> waitForDns -> catalogs
        // per engine: deploy -> smokeTest
        // workbench:  deploy
        return new DeploymentPlan(steps);
    }
}
```

- Emits the existing `Step` type so `StepRunner` (resume-from-step, logging,
  documentation generation) is reused unchanged.
- `DeployFromConfigMain` is the single new entry point:

  ```shell
  mvn compile -Dexec.mainClass=health.emrys.afon.platform.DeployFromConfigMain \
      -Dexec.args="afon-deployment.yaml" exec:exec -Pdemo
  ```

- The `Document*Main` pattern is preserved: `DocumentFromConfigMain` renders
  the plan as markdown, keeping docs in lock-step with the plan.

### 5.5 Properties migration

- `CloudSpec`/`EngineSpec`/`WorkbenchSpec` records (Jackson-deserialised)
  replace per-cloud `AfonProperty` constants for *topology* values.
- `env.properties` remains for secrets, referenced by `*Ref` keys.
- A `LegacyConfigExporter` generates an `afon-deployment.yaml` equivalent to
  the current `afon.properties`, so the demo environment migrates mechanically.
- `SupportedCloudsEnum` is retained during migration as an internal detail of
  the legacy path, then deleted in Phase 4.

## 6. Configuration UI (`afon-ui`)

### 6.1 Principles

- **Pure config generator.** No cloud credentials, no deploy button, no
  backend. Output is the YAML file; deployment happens via the reviewed file
  and the CLI. This keeps the security review surface at zero.
- Static SPA: React + TypeScript + Tailwind + shadcn/ui, built with Vite.
  Deployable as static files (S3/Netlify/`file://`).

### 6.2 Flow

1. **Clouds** — "Add cloud" repeatable card: provider dropdown (aws/azure/gcp),
   region, storage fields (form fields switch per provider), Polaris domain.
2. **Catalogs** — table auto-seeded with one internal catalog per cloud; user
   can add federated catalogs (cloud + via pickers) and test fixtures.
3. **Query engines** — multi-select cards: Spark (target + workers), Trino
   (target + workers), Snowflake (JDBC URL ref + readable catalogs).
4. **Workbench** — radio: Jupyter / VS Code / RStudio / none, target cloud,
   domain, OIDC settings.
5. **Review** — live YAML preview (read-only editor pane), validation errors
   inline, **Download afon-deployment.yaml**.

### 6.3 Validation parity

The JSON Schema from §4.1 is the single source: checked into
`afon-config/src/main/resources/afon-deployment.schema.json`, consumed by

- the UI via `ajv` (compile-time import), and
- the planner via `networknt/json-schema-validator`.

CI test asserts every example YAML in `docs/examples/` passes both validators.

## 7. Migration plan

Each phase leaves `main` deployable and the existing `Deploy*Main` classes
working until Phase 4.

| Phase | Deliverable | Risk |
|---|---|---|
| **1. Schema + parser** *(delivered)* | JSON Schema, `AfonDeploymentConfig` records, parser + validator, `LegacyConfigExporter` producing YAML from current `afon.properties`. No behaviour change. Lives in `afon-config` under `health.emrys.afon.config.deployment`; examples in `docs/examples/`. | Low |
| **2. Plugin extraction** *(delivered)* | `health.emrys.afon.platform`: `CloudProviderPlugin`/`ProvisionedCloud` implemented by the `*KubernetesPolaris` stacks (plugins: `AwsCloudPlugin`/`AzureCloudPlugin`/`GcpCloudPlugin`, resolved via `PluginRegistry`); `SparkEngineDeployer`/`TrinoEngineDeployer` and `JupyterWorkbenchDeployer` extracted from the `WholeStack` constructors, which now delegate to them. Snowflake's "deploy" is runtime SQL (a `Step`), so its deployer lands with the Phase 3 planner. Verified by synth-diffing all three stacks against pristine `HEAD` (semantically identical) and by the committed `PluginParityTest` (byte-identical plugin vs legacy synth). Bonus: fixed `Set.of`/`Map.of` iteration-order nondeterminism (KeyCloak env vars, Trino/Spark manifest serialisation) that was dirtying every `tofu` plan. | Medium — CDKTF resource IDs must stay stable or `tofu apply` will recreate resources. Mitigation: keep all construct IDs byte-identical; diff synthesised JSON in `cdktf.out/` before/after as a test. |
| **3. Planner + new workbenches + UI** *(planner delivered)* | Delivered: `DeploymentPlanner` (YAML → ordered `Step` list: per cloud clean → deploy → waitFor DNS → catalogs, then SaaS-engine runtime steps), `DeploymentPlan` (with markdown `describe()`), `DeployFromConfigMain`/`DocumentFromConfigMain`, `SnowflakeEngineDeployer` (runtime catalog-integration SQL via `QueryEngineDeployer#runtimeSteps`), and the §5.4 per-engine smoke test (`SparkEngineDeployer#runtimeSteps` reproduces the legacy `checkAwsSparkConnectorTalkingToAws` step against the target cloud's internal catalog). Stack hardening and the AWS spark-master LB + CRD cleanup moved from the `WholeStack`/`SparkClusterDeployment` constructors into `ProvisionedCloud#hardenStack`/`#exposeSparkMaster` so both paths share them (legacy synth re-verified byte-identical vs pre-refactor, modulo the Phase 2 determinism fix). Gate: `DeploymentPlannerTest` asserts the config-driven AWS synth is tree-identical to legacy `SparkClusterDeployment` and the demo plan matches the legacy step order. Remaining: `VsCodeWorkbench`, `RStudioWorkbench`, shared `AuthProxyConfig`, `afon-ui` module; acceptance specs for VS Code/RStudio behind oauth2-proxy; config-driven equivalents of the `afon-acceptance-tests` specs (§7.1). Deferred to Phase 4: Trino's catalog list stays properties-derived (§5.2) — deriving it from `catalogs:` changes the synthesised Helm values, which the parity gate forbids until legacy removal. | Medium |
| **4. Legacy removal** | Delete per-topology `Deploy*Main` classes, collapse `SupportedCloudsEnum`, README rewrite around the YAML workflow. | Low |

### 7.1 Testing strategy

- **Synth-diff tests** (Phase 2 gate): synthesise old vs new code paths and
  assert the generated Terraform JSON under `cdktf.out/stacks/*` is identical.
  This is the cheapest guard against accidental infra recreation.
- **Schema round-trip tests**: every `docs/examples/*.yaml` parses, validates,
  and re-serialises losslessly.
- **Existing acceptance tests** (`afon-acceptance-tests`) run unchanged in
  Phases 2–3; Phase 3 adds config-driven equivalents.
- **UI**: Playwright test builds a three-cloud config and asserts the
  downloaded YAML validates against the schema.

## 8. Open questions for review

1. **Multiple workbenches?** Schema currently allows 0..1. Making it a list is
   trivial now, awkward later — decide before Phase 1.
2. **Trino ingress.** Trino is currently ClusterIP-only. Should the config
   expose an optional public domain for it (like Polaris/Jupyter)?
3. **Snowflake "deployment" semantics.** Executing SQL against a customer
   Snowflake account from `StepRunner` is imperative, not Terraform. Acceptable
   as-is (matches today's behaviour), or should we move it to the Terraform
   Snowflake provider?
4. **Plugin discovery.** In-repo registry map vs `ServiceLoader`. Registry is
   proposed for v1; `ServiceLoader` only if third parties need to add clouds
   without forking.
5. **UI hosting.** Static-only proposed. If a hosted, shared instance is wanted
   later, saved configs would need a backend — out of scope here.
6. **Where does the K8s cluster for the workbench live when a user picks only
   Snowflake as engine?** Snowflake needs no cluster, but a workbench does —
   validation must force at least one provisioned cloud in that case.
