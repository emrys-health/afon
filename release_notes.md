# Release Notes

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
