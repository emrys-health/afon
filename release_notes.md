# Release Notes

## 1.0-beta-8 

**Milestone:** [1.0-beta-8](https://github.com/emrys-health/afon-core/milestone/9)
**Due:** 2026-10-30

### Upgrade notes

**IMPORTANT:** one-off steps when upgrading from 1.0-beta-7:

- **AWS DNS zones and certificates looked up ([#314](https://github.com/emrys-health/afon-core/issues/314)):**
  - The properties in `AWS_*_{DNS_ZONES|CERT_ARN}` in `afon-aws.properties` are now ignored, so existing configs keep working; 
  - `afon-cli.sh config-updates` removes them.
- **Check that each domain name has a hosted zone of its own name and an issued certificate before you deploy:**
  - `afon-cli.sh aws-issue-cert` issues the certificates, skips domain that already has one (`--force` requests a new one).
- **Optional KeyCloak domain name per cloud ([#317](https://github.com/emrys-health/afon-core/issues/317)):**
  - `AWS_`, `AZURE_` and `GCP_OIDC_DOMAIN_NAME`, blank by default, give a cloud a KeyCloak of its own; blank, the cloud uses `OIDC_DOMAIN_NAME`, so existing configs behave as before.
  - `OIDC_ISSUER_URL` is now a template: set it to `https://[OIDC_DOMAIN_NAME]/realms/polaris` so that it follows the domain name of the cloud.  An issuer with another host fails the stack synthesis.
  - `check-oidc` now takes the cloud: `afon-cli.sh check-oidc <cloud>`.
  - If a domain has several issued certificates, the latest is used.

## 1.0-beta-7

**Milestone:** [1.0-beta-7](https://github.com/emrys-health/afon-core/milestone/8)
**Due:** 2026-10-16

### Upgrade notes

**IMPORTANT:** one-off steps when upgrading from 1.0-beta-6:

- **Gitea in `tre` stacks ([#34](https://github.com/emrys-health/afon-core/issues/34)):** these stacks now deploy
  Gitea (the `Git` component), which needs `GITEA_*` properties that your existing config does not have, so **add them before you
  deploy or set the stack up again**, and rerun `afon-cli.sh aws-create tre` (or `azure-create tre`) to create the
  bucket.  The [issue #34](https://github.com/emrys-health/afon-core/issues/34) lists the properties, their defaults and which are optional, and the password rules (including the 8-character minimum for the passwords in `users.csv`).  `config-init` never overwrites an existing config dir, so copy them from `templates/config` and check what is missing with `afon-cli.sh config-validate`.  A missing required property fails the deploy before anything is created, naming the property.
- **GCP stacks named by type:** `gcp-keycloak` is new, and the GKE cluster, VPC, subnet and router of a GCP stack are now named
  `<stack-type>-<CLUSTER_NAME>`, as on AWS and Azure, so that the stacks run side by side.  **A `gcp-polaris` deployed by the previous
  version has them named `<CLUSTER_NAME>`, and deploying with this one replaces them: delete it first, with the previous version.**
  `gcp-create` now takes the stack, eg `gcp-create catalog`, and `gcp-issue-cert` issues the certs of every configured domain name.
- **New feature([#267](https://github.com/emrys-health/afon-core/issues/267)):** the stacks, and the flows run by `manage-stack run`, are now defined in `stacks.yaml` and `stack-flows.yaml`, bundled in the jar.  To define them differently, put a
  file of the same name in the config dir (`$AFON_HOME/config`), which replaces the bundled one entirely;
  `afon-cli.sh validate-stack --export-stacks` and `afon-cli.sh manage-stack run --export-flows` write a copy to start from.  A
  stack may have a `role` (`catalog`, `identity` or `tre`), which the flows and the checks find it by, so a `stacks.yaml` of your
  own needs a `role:` on the stacks they should find.  Wherever a stack is given, its id or its role works, eg
  `manage-stack setup aws-tre` or `afon-cli.sh aws-create tre`.  See [Define your own stacks](docs/stacks.md#define-your-own-stacks) and [Define your own flows](docs/stacks.md#define-your-own-flows).
- **Stack types replaced by ids or roles ([#292](https://github.com/emrys-health/afon-core/issues/292)):** the scripts that took
  a stack **type** (`keycloak`, `polaris`, `full`) now take the **stack id** or its **role** (`identity`, `catalog` or `tre`), so
  **replace the old argument in your own scripts and notes**:

  | before                                                           | now                                                                                     |
  |------------------------------------------------------------------|-----------------------------------------------------------------------------------------|
  | `afon-cli.sh manage-stack [delete/deploy/setup] <cloud>-full`    | `afon-cli.sh manage-stack [delete/deploy/setup] <cloud>-tre` (or `<cloud>-full`)        |
  | `afon-cli.sh manage-stack [delete/deploy/setup] <cloud>-polaris` | `afon-cli.sh manage-stack [delete/deploy/setup] <cloud>-catalog` (or `<cloud>-polaris`) |
  | `afon-cli.sh validate-stack <cloud>-full`                        | `afon-cli.sh validate-stack  <cloud>-tre` (or `<cloud>-full`)                           |
  | `afon-cli.sh <cloud>-cluster full`                               | `afon-cli.sh <cloud>-cluster tre` (or `<cloud>-full`)                                   |
  | `afon-cli.sh <cloud>-update-kube-config full`                    | `afon-cli.sh <cloud>-update-kube-config tre` (or `<cloud>-full`)                        |

  where `<cloud>=aws|azure`.  The stack ids, plan dirs, tofu state and resource names are **unchanged**, so existing
  deployments carry on.  `aws-keycloak` and `aws-polaris` (the status commands, without a stack) now find the running stack
  among the stacks that deploy Keycloak or Polaris in `stacks.yaml`.
- **`aws-create` and `azure-create` work out what to create from `stacks.yaml` ([#267](https://github.com/emrys-health/afon-core/issues/267)):**
  they resolve the stack by its id or its role, and create what the stack needs as `stacks.yaml` defines it: the DNS zone of each
  domain that its components hold, and the Gitea bucket or container if it deploys Git
  ([#34](https://github.com/emrys-health/afon-core/issues/34)).  Before, they replicated they logic based on the stack id.  No change for the
  bundled stacks.  A stack of your own with `Git` needs `AWS_GITEA_BUCKET` or `AZURE_GITEA_BUCKET` set too, as `*-full` does. Eg:

  | command | stack id | role |
  |---|---|---|
  | `afon-cli.sh aws-create <stack>` | `aws-full` | `tre` |
  | `afon-cli.sh azure-create [true\|false] <stack>` | `azure-polaris` | `catalog` |

### Closed Issues (31)

- **configuration, api** — [#292](https://github.com/emrys-health/afon-core/issues/292) Resolve a stack by id or by role
- [#291](https://github.com/emrys-health/afon-core/issues/291) Create/login scripts: mandatory stack id, Gitea and Polaris-log buckets, login selects the account
- **security, devops, configuration** — [#283](https://github.com/emrys-health/afon-core/issues/283) Support whitelisting in Azure
- **devops** — [#278](https://github.com/emrys-health/afon-core/issues/278) Check plan determinism in command `validate-stack`
- **logging** — [#276](https://github.com/emrys-health/afon-core/issues/276) Azure Polaris logs to be persisted
- **devops, configuration** — [#273](https://github.com/emrys-health/afon-core/issues/273) Support `azure-full` and `azure-keycloak` stacks
- **devops** — [#272](https://github.com/emrys-health/afon-core/issues/272) Shorter readable names for composite setup steps
- [#271](https://github.com/emrys-health/afon-core/issues/271) Provide per-command help docs
- [#270](https://github.com/emrys-health/afon-core/issues/270) Replace the golden-plan test with on-demand snapshot-stacks / compare-stacks
- **configuration, api** — [#269](https://github.com/emrys-health/afon-core/issues/269) Stack flows: a builder pattern for stack commands
- **devops, configuration, api** — [#267](https://github.com/emrys-health/afon-core/issues/267) Define stacks and flows at runtime in YAML files
- **configuration, api** — [#266](https://github.com/emrys-health/afon-core/issues/266) Drop the legacy per-stack ops commands and `StackStepsFactory`
- **logging** — [#265](https://github.com/emrys-health/afon-core/issues/265) AWS Polaris logs to be persistant
- **security, devops** — [#264](https://github.com/emrys-health/afon-core/issues/264) Authenticate GCP with Application Default Credentials
- [#263](https://github.com/emrys-health/afon-core/issues/263) Rename local setup scripts to `local/` directory
- **security** — [#262](https://github.com/emrys-health/afon-core/issues/262) Annotate the `polaris-tls` cert upon restore so cert-manager keeps the cached cert (GCP and Azure)
- **security, configuration** — [#261](https://github.com/emrys-health/afon-core/issues/261) Support storing TLS certificates from CA in GCP
- [#255](https://github.com/emrys-health/afon-core/issues/255) Rogue character in Polaris env var
- [#252](https://github.com/emrys-health/afon-core/issues/252) Unquoted secrets - can't handle metacharacters
- [#250](https://github.com/emrys-health/afon-core/issues/250) ALB health checks had never passed on any of the three ingresses
- [#249](https://github.com/emrys-health/afon-core/issues/249) Non-deterministic Terraform.
- **devops** — [#247](https://github.com/emrys-health/afon-core/issues/247) Add GitHub notifications to Slack channel `afon-ci` in case of failures
- **devops** — [#246](https://github.com/emrys-health/afon-core/issues/246) Cleanup spurious error messages during Maven release
- **configuration** — [#244](https://github.com/emrys-health/afon-core/issues/244) Make GCP_PROJECT_ID configurable
- **documentation** — [#242](https://github.com/emrys-health/afon-core/issues/242) Add contributing guidelines for Afon as an open-source project
- **security, tech debt** — [#239](https://github.com/emrys-health/afon-core/issues/239) Cleanup Polaris catalog and principal role setup, to avoid expected 404/409 WARNs
- **configuration, api, tech debt** — [#181](https://github.com/emrys-health/afon-core/issues/181) Compose stacks from cloud-agnostic components
- **configuration** — [#138](https://github.com/emrys-health/afon-core/issues/138) Remove Azure OAuth2 properties as obsolete
- **devops, testing** — [#116](https://github.com/emrys-health/afon-core/issues/116) Create smoke test suite for regression testing against a deployed cluster
- [#50](https://github.com/emrys-health/afon-core/issues/50) Hosted docs
- [#34](https://github.com/emrys-health/afon-core/issues/34) Add Git component for researchers

## 1.0-beta-6

**Milestone:** [1.0-beta-6](https://github.com/emrys-health/afon-core/milestone/6)
**Due:** 2026-09-30

### Upgrade notes

**IMPORTANT:** one-off steps when upgrading from 1.0-beta-5:

- **User config ([#231](https://github.com/emrys-health/afon-core/issues/231)):** run once, in each config env:
  ```shell
  afon-cli.sh config-user
  ```
  It updates the user config, renaming `USER_POLARIS_ID` to `USER_ID`, and drops `USER_POLARIS_SECRET` and
  `USER_POLARIS_EMAIL`, which are now looked up from `users.csv`.  It can then be rerun any time to show the configured
  user.
- **Per-stack state ([#232](https://github.com/emrys-health/afon-core/issues/232)):** stacks are now deployed per stack
  id, each with its own plan and tofu state, e.g. `stacks/aws-keycloak` and `terraform.aws-keycloak.tfstate`, rather
  than per cloud.  A stack deployed with 1.0-beta-5 or earlier is the *legacy stack* of its cloud, so the first `deploy`
  or `setup` in each cloud, and each cdktf env, stops and asks to delete it: rerun with `--replace`, or run
  `manage-stack delete <stack-id>` first.  See
  [upgrading to per-stack state](docs/stacks.md#upgrading-to-per-stack-state-one-off).
- **AWS resource names ([#232](https://github.com/emrys-health/afon-core/issues/232)):** AWS resources are now named
  from `<stack-type>-<CLUSTER_NAME>`, e.g. `keycloak-emrys-cluster`, so the first deploy recreates them.  The AWS
  scripts acting on a stack now take its type, e.g. `afon-cli.sh aws-cluster keycloak`.
- **Conflicting stacks ([#238](https://github.com/emrys-health/afon-core/issues/238)):** `deploy` and `setup` no longer
  delete other running stacks they conflict with, unless given `--replace`, and `delete <stack-id>` deletes just that
  stack (`delete aws-all` deletes every AWS stack).  `aws-keycloak` and `aws-polaris` can run side by side, as can
  `aws-full` with `AWS_KEYCLOAK_EXTERNAL=true` (a new, optional property in `afon-aws.properties`).  Some step names
  changed, e.g. `delete_stacks_conflicting_with_<stack-id>` and `deploy_complete_for_<stack-id>`, so check them before
  resuming a run from a step.

### Closed Issues (31)

- [#240](https://github.com/emrys-health/afon-core/issues/240) Add config-vault command to store/retrieve config by env and tag
- [#238](https://github.com/emrys-health/afon-core/issues/238) Handle conflicts while deploying an AWS stack
- **enhancement, devops** — [#235](https://github.com/emrys-health/afon-core/issues/235) Add `manage-stack` command to manage all stack operations
- **devops, configuration** — [#232](https://github.com/emrys-health/afon-core/issues/232) Allow multiple stack types to coexist in the same AWS cloud
- **configuration** — [#231](https://github.com/emrys-health/afon-core/issues/231) Replace USER_POLARIS_SECRET/EMAIL with lookup via AfonUserProvider.  Rename USER_POLARIS_ID to USER_ID.
- **devops** — [#230](https://github.com/emrys-health/afon-core/issues/230) Add AWS Keycloak-only stack
- **enhancement, tech debt** — [#229](https://github.com/emrys-health/afon-core/issues/229) Add afon-rest module
- **devops** — [#228](https://github.com/emrys-health/afon-core/issues/228) Ensure flag options override the corresponding env options in afon.sh and afon-cli.sh
- **security, tech debt** — [#227](https://github.com/emrys-health/afon-core/issues/227) Ensure all OIDC tokens are retrieved via IdentityProvider
- **devops, configuration** — [#226](https://github.com/emrys-health/afon-core/issues/226) Allow catalog exclusions in the user ACL
- **security** — [#225](https://github.com/emrys-health/afon-core/issues/225) Give each Polaris user their own principal role
- **devops** — [#224](https://github.com/emrys-health/afon-core/issues/224) Ensure <cloud>-polaris commands are aligned
- **devops, tech debt** — [#223](https://github.com/emrys-health/afon-core/issues/223) Suppress runtime JVM warnings
- **documentation** — [#221](https://github.com/emrys-health/afon-core/issues/221) Update docs to be command-centric
- **enhancement, devops** — [#220](https://github.com/emrys-health/afon-core/issues/220) Add timings for tofu operations and make tofu output stream configurable.
- **enhancement, devops** — [#219](https://github.com/emrys-health/afon-core/issues/219) Update release notes with issues closed for milestone version upon release
- **enhancement** — [#217](https://github.com/emrys-health/afon-core/issues/217) Guacamole should use different db name for different clusters
- **enhancement, configuration** — [#214](https://github.com/emrys-health/afon-core/issues/214) Add Afon theme UI branding for Guacamole and Keycloak
- **devops, configuration** — [#212](https://github.com/emrys-health/afon-core/issues/212) Make github/secrets.sh env-aware
- **devops** — [#208](https://github.com/emrys-health/afon-core/issues/208) Improve cleanupCRDs strategies in AWS Stacks
- [#202](https://github.com/emrys-health/afon-core/issues/202) Create test data in the cloud for demos
- **logging, security, smoke test** — [#199](https://github.com/emrys-health/afon-core/issues/199) PolarisRestClient fails silently upon auth failure
- [#198](https://github.com/emrys-health/afon-core/issues/198) Fix circular dependency between OIDC setup, catalog creation, and Polaris user config on a new Keycloak db
- [#195](https://github.com/emrys-health/afon-core/issues/195) Write deployment metadata to an Iceberg table
- **devops, configuration** — [#192](https://github.com/emrys-health/afon-core/issues/192) Centralise all docker image names
- **security, smoke test** — [#185](https://github.com/emrys-health/afon-core/issues/185) SmokeTest: data exfiltration
- **security** — [#177](https://github.com/emrys-health/afon-core/issues/177) Remove guacadmin from Guacamole's DB
- **security** — [#162](https://github.com/emrys-health/afon-core/issues/162) Key exfiltration danger
- **configuration, tech debt** — [#159](https://github.com/emrys-health/afon-core/issues/159) Deployment to a given cloud stack should only require properties for that cloud provider
- **security, configuration** — [#86](https://github.com/emrys-health/afon-core/issues/86) Remove all the Spark Connect pre-configured warehouses
- **devops** — [#84](https://github.com/emrys-health/afon-core/issues/84) AWS hangs on tofu destroy

## 1.0-beta-5

**Milestone:** [1.0-beta-5](https://github.com/emrys-health/afon-core/milestone/5)
**Due:** 2026-09-23

### Closed Issues (6)

- **devops, tech debt** — [#211](https://github.com/emrys-health/afon-core/issues/211) Drop afon-samples module
- **enhancement** — [#210](https://github.com/emrys-health/afon-core/issues/210) Keycloak hostname doesn't resolve inside the cluster for ~10 minutes
- **devops** — [#209](https://github.com/emrys-health/afon-core/issues/209) Add command name shorthands for ops exec *Main classes in afon.sh
- **devops** — [#200](https://github.com/emrys-health/afon-core/issues/200) AWS tofu destroy is unable to uninstall Helm release kube-prometheus-stack
- **security, devops** — [#197](https://github.com/emrys-health/afon-core/issues/197) Check AWS certs are issued when deploying new stack
- **devops, testing, configuration** — [#194](https://github.com/emrys-health/afon-core/issues/194) Runtime configuration of Polaris catalogs available via AfonCatalogs

## 1.0-beta-4

**Milestone:** [1.0-beta-4](https://github.com/emrys-health/afon-core/milestone/4)
**Due:** 2026-09-21

### Closed Issues (5)

- **configuration** — [#206](https://github.com/emrys-health/afon-core/issues/206) Make GCP_USER_ACCOUNT configurable
- **devops** — [#205](https://github.com/emrys-health/afon-core/issues/205) Add action shorthands for all script executions in afon.sh
- **devops, configuration** — [#203](https://github.com/emrys-health/afon-core/issues/203) Support script execution in afon-cli
- **configuration** — [#196](https://github.com/emrys-health/afon-core/issues/196) Allow configuration of OIDC users independently of Jupyter domain
- **devops, configuration** — [#193](https://github.com/emrys-health/afon-core/issues/193) Replace Maven AWS bundle artifact with explicit artifact dependencies

## 1.0-beta-3

**Milestone:** [1.0-beta-3](https://github.com/emrys-health/afon-core/milestone/1)
**Due:** 2026-09-11

### Closed Issues (3)

- **devops** — [#190](https://github.com/emrys-health/afon-core/issues/190) Add release notes to binary distributions
- **devops, configuration** — [#189](https://github.com/emrys-health/afon-core/issues/189) No HelmProvider registered in AwsPolarisStack
- **devops, configuration** — [#188](https://github.com/emrys-health/afon-core/issues/188) Make Polaris namespace and table names configurable

## 1.0-beta-2

**Milestone:** [1.0-beta-2](https://github.com/emrys-health/afon-core/milestone/2)
**Due:** 2026-09-10

### Closed Issues (18)

- **devops,tech debt** — [#187](https://github.com/emrys-health/afon-core/issues/187) Refactor the ops entry points for Polaris catalogs and users to look up Polaris and Jupyter domain names from config
- **configuration,tech debt** — [#180](https://github.com/emrys-health/afon-core/issues/180) Remove module afon-cluster
- **configuration,tech debt** — [#179](https://github.com/emrys-health/afon-core/issues/179) Make AcceptanceTestConfig cloud-aware
- **configuration** — [#176](https://github.com/emrys-health/afon-core/issues/176) Allow config templates to provide meaningful default values
- **enhancement,configuration** — [#175](https://github.com/emrys-health/afon-core/issues/175) Add AfonProperty ENV_NAME
- **enhancement,configuration** — [#174](https://github.com/emrys-health/afon-core/issues/174) Add AfonProperty GCP_REGION.  Move Defaults constants to CdkHelper.
- **enhancement,configuration** — [#173](https://github.com/emrys-health/afon-core/issues/173) Support interpolation of config property values
- **security,tech debt** — [#171](https://github.com/emrys-health/afon-core/issues/171) Apply DNS white listing to both the Jupyter pods and Spark pods
- **enhancement,configuration** — [#170](https://github.com/emrys-health/afon-core/issues/170) Ensure Polaris db name unique for each Polaris domain name and the schema and root principal are created
- **enhancement,question,security,tech debt** — [#169](https://github.com/emrys-health/afon-core/issues/169) Upgrade to AKS Kubernetes Node VM Standard_D2s_v6
- **configuration,tech debt** — [#167](https://github.com/emrys-health/afon-core/issues/167) Add DefaultPropertyProvider to lookup default values if any are missing in the file properties
- **enhancement** — [#166](https://github.com/emrys-health/afon-core/issues/166) Add IdentityProvider interface with KeyCloak-based implementation
- **configuration,tech debt** — [#165](https://github.com/emrys-health/afon-core/issues/165) Make Polaris catalog names configurable at runtime
- **configuration,tech debt** — [#164](https://github.com/emrys-health/afon-core/issues/164) Streamline catalog configuration and management
- **configuration** — [#163](https://github.com/emrys-health/afon-core/issues/163) Allow AfonPropertyProvider to override or set properties
- **configuration** — [#144](https://github.com/emrys-health/afon-core/issues/144) Separate the Azure commands in the create/destroy env scripts that need to be run by local admin
- **configuration,tech debt** — [#132](https://github.com/emrys-health/afon-core/issues/132) Rename properties for consistency
- [#62](https://github.com/emrys-health/afon-core/issues/62) Support storing of TLS certificate from CA

## 1.0-beta-1

**Milestone:** [1.0-beta-1](https://github.com/emrys-health/afon-core/milestone/3)
**Due:** 2026-08-27

### Closed Issues (107)

- **devops** — [#161](https://github.com/emrys-health/afon-core/issues/161) Provide versioned Maven releases
- **devops** — [#160](https://github.com/emrys-health/afon-core/issues/160) Make cdktf.out and terraform.tfstate locations configurable
- [#158](https://github.com/emrys-health/afon-core/issues/158) Make cloud create/destroy scripts idempotent
- [#157](https://github.com/emrys-health/afon-core/issues/157) Make deployment resilient to network errors
- [#156](https://github.com/emrys-health/afon-core/issues/156) Security Demo story
- **security** — [#154](https://github.com/emrys-health/afon-core/issues/154) Support configuration of catalogs each user is authorised for
- **security** — [#151](https://github.com/emrys-health/afon-core/issues/151) Multi-factor authentication enabled for all users.
- **security** — [#150](https://github.com/emrys-health/afon-core/issues/150) Security: Add MFA to KeyCloak
- **security** — [#149](https://github.com/emrys-health/afon-core/issues/149) Access restricted to the data a user needs for their work.
- **devops** — [#148](https://github.com/emrys-health/afon-core/issues/148) Support Polaris stack deploy and delete for all 3 cloud providers
- **security** — [#147](https://github.com/emrys-health/afon-core/issues/147) Security: lock down access to other pods from the spawned Jupyter pod
- **documentation** — [#146](https://github.com/emrys-health/afon-core/issues/146) Provide tech spec for partner organisations
- **devops** — [#143](https://github.com/emrys-health/afon-core/issues/143) Provide Afon CLI standalone executable
- **devops** — [#142](https://github.com/emrys-health/afon-core/issues/142) Separate population of Polaris catalogs from deployment of full stack
- **devops** — [#141](https://github.com/emrys-health/afon-core/issues/141) Setup GitHub workflow to deploy AWS stack on Linux
- **logging** — [#140](https://github.com/emrys-health/afon-core/issues/140) Activate console logging based on a JVM property
- **enhancement** — [#139](https://github.com/emrys-health/afon-core/issues/139) Rename KeyCloak security classes
- **enhancement** — [#134](https://github.com/emrys-health/afon-core/issues/134) Extract AfonCloud enum from AfonProperties
- [#130](https://github.com/emrys-health/afon-core/issues/130) Extract AfonPropertyProvider interface and AfonProperty enum
- **enhancement** — [#128](https://github.com/emrys-health/afon-core/issues/128) Replace redacted secret with the <property-key> in generated docs
- **enhancement** — [#127](https://github.com/emrys-health/afon-core/issues/127) The StepRunner should log the individual step in which the run failed
- **configuration** — [#126](https://github.com/emrys-health/afon-core/issues/126) Rename secret properties config files
- **configuration** — [#125](https://github.com/emrys-health/afon-core/issues/125) Move all config properties out of source control
- **devops,configuration** — [#124](https://github.com/emrys-health/afon-core/issues/124) Support multiple envs for building, runtime and deployment
- **documentation** — [#120](https://github.com/emrys-health/afon-core/issues/120) Reorganise documentation from READMEs to named docs.
- **enhancement** — [#119](https://github.com/emrys-health/afon-core/issues/119) Refactor ClusterClient to declare a factory method buildClient() for its subclasses
- **security** — [#118](https://github.com/emrys-health/afon-core/issues/118) Connectivity from a spawned Jupyter pod has full internet access
- **bug,tech debt** — [#117](https://github.com/emrys-health/afon-core/issues/117) Bug: OnboardAllUsersMain configures a cloud for users at the expense of other clouds
- **configuration** — [#115](https://github.com/emrys-health/afon-core/issues/115) Support user configuration from csv file
- **bug** — [#114](https://github.com/emrys-health/afon-core/issues/114) Azure build: West Mids provides the Resource Group whereas our code creates it
- **documentation,monitoring** — [#113](https://github.com/emrys-health/afon-core/issues/113) Support cluster monitoring from local env
- **logging** — [#111](https://github.com/emrys-health/afon-core/issues/111) Configure and manage jvm logging strategies
- **documentation** — [#104](https://github.com/emrys-health/afon-core/issues/104) Re-generate the documentation on each build
- **enhancement** — [#103](https://github.com/emrys-health/afon-core/issues/103) Add afon-ops execution entry point
- **configuration** — [#102](https://github.com/emrys-health/afon-core/issues/102) Support cloud-specific configuration properties
- **testing** — [#101](https://github.com/emrys-health/afon-core/issues/101) Allow tag-based execution of acceptance tests
- **devops** — [#99](https://github.com/emrys-health/afon-core/issues/99) Improve Maven build
- **devops** — [#98](https://github.com/emrys-health/afon-core/issues/98) Add profiles to Maven build
- **api** — [#97](https://github.com/emrys-health/afon-core/issues/97) Provide API script afon.sh
- **devops** — [#96](https://github.com/emrys-health/afon-core/issues/96) Set up namespace on Docker Hub
- **devops** — [#95](https://github.com/emrys-health/afon-core/issues/95) Provision Linux VM on AWS EC2
- **devops** — [#94](https://github.com/emrys-health/afon-core/issues/94) Setup GitHub workflow for acceptance tests on Linux
- **devops** — [#92](https://github.com/emrys-health/afon-core/issues/92) Document (or automate) steps for setting up the environment for the 24/7 demo
- **configuration** — [#91](https://github.com/emrys-health/afon-core/issues/91) Configure use an AWS "service account" with fewer privileges
- **logging** — [#90](https://github.com/emrys-health/afon-core/issues/90) Remove logging of results
- **devops** — [#89](https://github.com/emrys-health/afon-core/issues/89) Create docker hub image registry
- **bug** — [#88](https://github.com/emrys-health/afon-core/issues/88) Forwarded to the wrong page after KeyCloak login
- **configuration** — [#85](https://github.com/emrys-health/afon-core/issues/85) User Onboarding
- **security** — [#82](https://github.com/emrys-health/afon-core/issues/82) Add in application config for Spark to prevent writes to arbitrary cloud locations
- **security** — [#81](https://github.com/emrys-health/afon-core/issues/81) Closing down access within pods
- [#79](https://github.com/emrys-health/afon-core/issues/79) Create new cloud environments for testing afon deployment
- [#78](https://github.com/emrys-health/afon-core/issues/78) Upgrade Terraform's Kubernetes Provider
- [#77](https://github.com/emrys-health/afon-core/issues/77) Configuration of polaris with keycloak
- [#75](https://github.com/emrys-health/afon-core/issues/75) Use KeyCloak in production, not Google
- [#74](https://github.com/emrys-health/afon-core/issues/74) Have as much as the environment as possible running locally
- [#73](https://github.com/emrys-health/afon-core/issues/73) Guacamole should display the desktop of more than one container
- [#72](https://github.com/emrys-health/afon-core/issues/72) Investigate multitenancy in Spark Connect
- [#70](https://github.com/emrys-health/afon-core/issues/70) Maintaining user provenance in federated querying
- [#69](https://github.com/emrys-health/afon-core/issues/69) Destroying Azure hours after it was created fails
- [#68](https://github.com/emrys-health/afon-core/issues/68) Running tofu destroy times out for AWS
- [#67](https://github.com/emrys-health/afon-core/issues/67) Allow resource usage stats in AWS
- **bug** — [#65](https://github.com/emrys-health/afon-core/issues/65) Bug: AWS deployment needs a manual step after deployment
- [#64](https://github.com/emrys-health/afon-core/issues/64) Have Polaris use RBAC
- [#60](https://github.com/emrys-health/afon-core/issues/60) Get full stack SRE on Azure
- [#59](https://github.com/emrys-health/afon-core/issues/59) Create Afon DockerHub image repo
- [#58](https://github.com/emrys-health/afon-core/issues/58) Add Apache Trino to the project
- [#57](https://github.com/emrys-health/afon-core/issues/57) Architectural Diagram of end to end solution
- [#55](https://github.com/emrys-health/afon-core/issues/55) Epic: Logging for access
- [#54](https://github.com/emrys-health/afon-core/issues/54) Multi-tenanted pixel streaming sessions
- [#52](https://github.com/emrys-health/afon-core/issues/52) Prevent copy paste
- [#48](https://github.com/emrys-health/afon-core/issues/48) HTTPS linkage to cloud for each cloud
- [#47](https://github.com/emrys-health/afon-core/issues/47) chore: Hosted Zone is Hard Coded; abstract out
- [#46](https://github.com/emrys-health/afon-core/issues/46) The AWS deployment
- [#44](https://github.com/emrys-health/afon-core/issues/44) chore: bump EKS provider
- [#39](https://github.com/emrys-health/afon-core/issues/39) Introduce components that monitor the cloud stack
- [#38](https://github.com/emrys-health/afon-core/issues/38) Remove Prometheus
- [#37](https://github.com/emrys-health/afon-core/issues/37) Quickstart docs
- [#36](https://github.com/emrys-health/afon-core/issues/36) Extracting strings to properties
- [#35](https://github.com/emrys-health/afon-core/issues/35) Persistent intermediate storage
- [#33](https://github.com/emrys-health/afon-core/issues/33) Demo for TDA
- [#31](https://github.com/emrys-health/afon-core/issues/31) Implement Multi Factor Authentication
- [#30](https://github.com/emrys-health/afon-core/issues/30) Use Cilium as the CNI
- [#28](https://github.com/emrys-health/afon-core/issues/28) Remote Browser Interface
- [#25](https://github.com/emrys-health/afon-core/issues/25) POC month one - Researcher user story
- [#24](https://github.com/emrys-health/afon-core/issues/24) Check whether Polaris can enforce permissions beyond what the cloud can deny
- [#23](https://github.com/emrys-health/afon-core/issues/23) Snowflake as the query engine
- [#22](https://github.com/emrys-health/afon-core/issues/22) Federated catalogs using polaris in multiple clouds
- [#21](https://github.com/emrys-health/afon-core/issues/21) Scope PR to Apache Polaris for GCP Federation - issue 3451
- [#19](https://github.com/emrys-health/afon-core/issues/19) External Catalog: GCP/BigLake
- [#18](https://github.com/emrys-health/afon-core/issues/18) External catalogs: AWS
- [#17](https://github.com/emrys-health/afon-core/issues/17) External Catalogs
- [#16](https://github.com/emrys-health/afon-core/issues/16) Three clouds and snowflake
- [#15](https://github.com/emrys-health/afon-core/issues/15) HTTPS polaris
- [#14](https://github.com/emrys-health/afon-core/issues/14) Snowflake Iceberg  tables
- [#13](https://github.com/emrys-health/afon-core/issues/13) Jupyter security: do not leak Polaris credentials
- [#12](https://github.com/emrys-health/afon-core/issues/12) Stop Jupyter from initiating connections to the internet other than specified clouds
- [#11](https://github.com/emrys-health/afon-core/issues/11) Hide Polaris credentials on the Jupyter server
- [#10](https://github.com/emrys-health/afon-core/issues/10) GCP credentials expire after 24 hours
- [#9](https://github.com/emrys-health/afon-core/issues/9) Will runs Phil's code
- [#8](https://github.com/emrys-health/afon-core/issues/8) Smoke test
- [#7](https://github.com/emrys-health/afon-core/issues/7) Shell scripts to build up a tear down
- [#6](https://github.com/emrys-health/afon-core/issues/6) Persist Polaris Catalog config to a durable resource
- [#5](https://github.com/emrys-health/afon-core/issues/5) Vended credentials time out when they should
- [#4](https://github.com/emrys-health/afon-core/issues/4) Vended credentials only providing access to data polaris provides access to
- [#3](https://github.com/emrys-health/afon-core/issues/3) Tighten security on Polaris
- [#2](https://github.com/emrys-health/afon-core/issues/2) Vended credentials for Azure/GCP service
- [#1](https://github.com/emrys-health/afon-core/issues/1) Vended credentials from cloud to local
