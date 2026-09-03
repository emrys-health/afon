```
EMRYS HEALTH
HDRS Afon – Open Source Data Fabric – Feasibility 
SDE specification for technical tasks
Technical specification for participating Secure Data Environments
August 2026
```

This document outlines the onboarding of participating SDEs for the Afon data fabric technical feasibility project running from July - September 2026.

This includes:

* the technical tasks and who they typically sit with
* what the commitment is in time and resource

In terms of budgeting for capacity and cost, time is shown as an indicative person-time estimate (hours/days) against a standard 7.5-hour day; where an ask recurs weekly. All estimates flex to your team's capacity — please flag early if something doesn't fit.

Emrys will work flexibly with all SDEs individually to budget for time, availability and costs.

**Project purpose and vision**

The Health Data Research Service has commissioned Emrys to deliver a Data Fabric Technical Feasibility project alongside SDEs. This project aims to determine if vendor-agnostic, open-source infrastructure patterns can securely and efficiently connect health data for research on a project-by-project basis.

Currently, health data is spread across thousands of independent organisations and stored in incompatible formats, making access and linkage slow, complex, and costly. As a result, researchers see only partial patient journeys.

To address these challenges, the project will test a decentralised data fabric architecture. In this model, data remains local so NHS organisations retain full control, while researchers can securely query data through an integrated, vendor-agnostic environment.

During a 10-week learning phase beginning in July and ending on 25th September, the main objective is to conduct a controlled experiment with synthetic data to demonstrate the feasibility of distributed data integration across the HDRS ecosystem. The study will provide evidence on cybersecurity, information governance, cost-effectiveness, and operational performance. By using industry-standard open-source technologies, the project reduces reliance on custom code and lowers technical risk.

**Project hypothesis**

We are testing the feasibility of moving from agreement to multi-organisation research environments that are secure and auditable at the push of a button. We will create infrastructure as code, governance as code and security as code. The TRE is destroyed every day so that no data is persisted and no data leaves the secure research network.

**HDRS Data Fabric Feasibility - Technical Tasks for participating SDEs**

Technical task backlog — synthetic Iceberg catalog via Apache Polaris

**Phase One Scope**

Each partner Secure Data Environment will deploy an Apache Polaris catalog (backed by Iceberg tables containing only synthetic data), reachable over HTTPS in a cloud environment. 

This document breaks that work into discrete tasks for a given cloud. Each task is written as a user story with acceptance criteria to support an estimate of effort for the team

The commands within this document can be found within the Afon-core repository. We will need a github user name to add contributors to the project and users created for the Emrys team in the cloud to support with deployment. 


**NOTE:** We assume your chosen cloud is one of AWS, Azure or GCP.

In this document the following conventions apply to the name of the cloud/CLOUD variables:
- the 'cloud' values are in lower case: {aws,azure,gcp}
- the 'CLOUD' values are in upper case: {AWS,AZURE,GCP}

We are happy to support in any stage of this task list and have developed a CLI to support the 

**Tasks**

| # | Task                                                      | Outcome                                                 |
|---|-----------------------------------------------------------|---------------------------------------------------------|
| 1 | Setup local env                                           | A linux or macOS machine with tools to build and deploy | 
| 2 | Provision app registration & permissions for chosen cloud | A clean cloud subscription with appropriate permissions |
| 3 | Register a Polaris domain name for your env               | Domain name such as `<env>`polaris.click is registered   |
| 4 | Create DNS zone and certificate                           | DNS nameservers are configured                          |
| 5 | Generate configuration                                    | Config files created in $AFON_HOME/config               |
| 6 | Create cloud env                                          | Script `<cloud>`/create.sh is run successfully          | 
| 7 | Build Afon CLI locally                                    | Afon CLI built in $AFON_HOME/cli                        |
| 8 | Deploy Afon Polaris stack to chosen cloud                 | Polaris stack deployed                                  |

**1. Setup local env**

```md
As a cloud engineer
I want to setup the local env 
So that I build and deploy Afon on my cloud infrastrcture
```

**Depends on:** Access to Linux or macOS

**Acceptance criteria**

```md
Given a local system (linux or macos)
When we run the following setup-local action for cloud <cloud> via the afon.sh script
Then the Afon build env is setup locally for cloud <cloud>
```

**How to do it**

```shell
./afon.sh -a setup-local -c <cloud>
```
See the [setup local env](../docs/setup_local.md) for more details.

**2. Provision app registration & permissions for chosen cloud**

```md
As a cloud engineer
I want to provision a cloud registration and grant it appropriate permissions
So that Polaris env can be created
```

**Depends on:** A clean cloud subscription with permissions to create resource groups, storage accounts and app registrations. 

**How to do it**

``shell
az account management-group create --name AfonFabricGroup
az account management-group subscription add --name AfonFabricGroup --subscription "AfonFabric"
``
 This can also be achieved via the Azure portal.

**3. Register a Polaris domain name**

```md
As a cloud engineer
I want to register a domain name for the Polaris endpoint
So that data can be encrypted over HTTPS.
```

**Acceptance criteria**

```md
Given no suitable domain currently exists for your environment <env>
When the organisation purchases or designates a domain
Then a Polaris domain such as `<env>polaris.click` is available.
```

**How to do it**

AWS Route53 is our recommended approach for domain purchases. A domain can also be provided by the Emrys team eg. {SDE}polaris.click


**4. DNS Configuration**

```md
As a cloud engineer 
I want to create a DNS zone for the Polaris domain
So that cloud-hosted traffic resolves correctly and can be served over HTTPS
```
**Depends on:** Task 1,3

**Acceptance criteria**

```md
Given a Polaris domain name is available
When DNS records are configured
Then  ```shell
dig A <domain> +short
```
```resolves to the expected IP address

**How to do it**

Follow the [DNS configuration](../docs/dns_config.md) documentation.


**5. Configuration setup**

```md
As a cloud engineer
I want to generate the properties for our environment <env> and cloud
So that the deployment script knows which domain, roles, storage and credentials belong to the env's chosen cloud.
```

**Depends on:** Task 1-4

**Acceptance criteria**

```md
Given my organisation's chosen cloud, account details, region, bucket names and chosen domain
When configuration properties are set up, both the common ones and the cloud specific ones,
Then all the configuration properties are replaced with real, env-specific values
```

**How to do it**

```shell
./afon.sh -a config-init
```

You can also import/export config files as a zip:
```shell
./afon.sh -a config-{import|export} --config-zip <path-to-config-zip>
```

After the config files are completed (or imported) you can validate them:
```shell
./afon.sh -a config-validate -c <cloud>
```


**6. Create cloud env**

```md
As a cloud engineer
I want to build create a cloud env
So that I can build and deploy Polaris
```

**Depends on:** Task 1-5

**Acceptance criteria**

```md
Given a local system
When we run the following exec action for the cloud <cloud>
Then the Afon env is created for cloud <cloud>
```

**How to do it**

```shell
./afon.sh -a script --script-path <cloud>/create.sh 
```

See the [cloud infrastructure](../docs/cloud.md) for more details.

**7. Build the Afon CLI locally**

```md
As a cloud engineer
I want to build the Afon CLI locally
So that I can deploy Polaris
```

**Depends on:** Task 1-6

**Acceptance criteria**

```md
Given a local system 
When we run the following build action via the afon.sh script
Then the Afon CLI are built for the chosen cloud
```

**How to do it**

```shell
./afon.sh -a build -bp cli -c <cloud>
```

The CLI build artifacts are available in `~/.afon/cli`.

```shell
./.afon/cli/afon-cli.sh 
```

**8. Deploy the Afon Polaris stack**

```md
As a cloud engineer
I want to run the Afon data provider deployment against our cloud environment
So that Polaris is are running in the Kubernetes cluster with the catalog configured, ready to receive synthetic data
```

**Depends on:** Tasks 1-7

**Acceptance criteria**

```md
Given tasks 1-7 are completed
When the following CLI deploy command is executed
Then all the steps are completed successfully
```

```md
Given the Polaris stack is up
When the following commands are executed:  `kubectl get pods`
Then the Polaris pod shows Running/Ready and the ingress has an address assigned.
```
```md
Given the domain <CLOUD>_DOMAIN_NAME is registered 
When we deploy the Polaris server associated with the <CLOUD>_DOMAIN_NAME  
Then the URL https://<CLOUD_DOMAIN_NAME> loads with a valid TLS certificate and no browser certificate warnings.
```

**How to do it**

```shell
./afon.sh -a cli --cli-cmd deploy-<cloud>-polaris-stack
```

## **Resourcing: technical**

Indicative time and the role we'd typically expect it to sit with, against each task above — based on Emrys's resourcing plan for Phase One (indicatively ~2 days total technical time per SDE) and confirmed individually with your technical lead at kickoff. We’ll work with you to support available technical leads with the build.

Ideally, technical leads would become confident to deploy the data fabric in their own environment. 

For those SDE that are very time constrained the minimum expected tasks on this list to complete are 2, 5 and 6. 

