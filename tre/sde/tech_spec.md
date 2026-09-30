```
EMRYS HEALTH
HDRS Afon – Open Source Data Fabric – Feasibility 
SDE specification for technical tasks
Technical specification for participating Secure Data Environments
September 2026
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

| # | Task                                                          | Outcome                                                 |
|---|---------------------------------------------------------------|---------------------------------------------------------|
| 1 | [Install Afon CLI locally](#install-the-afon-cli-locally)     | Afon CLI installed in `$AFON_HOME/cli`                  |
| 2 | [Setup local env](#setup-local-env)                           | A linux or macOS machine with tools to build and deploy |
| 3 | [Provision app registration](#provision-app-registration)     | A clean cloud subscription with appropriate permissions |
| 4 | [Register Polaris domain name](#register-polaris-domain-name) | Domain name such as `<env>`polaris.click is registered  |
| 5 | [Create DNS zone](#create-dns-zone)                           | DNS nameservers are configured                          |
| 6 | [Create config](#create-config)                               | Config files created in `$AFON_HOME/config`             |
| 7 | [Create cloud env](#create-cloud-env)                         | Action `<cloud>`-create is run successfully             |
| 8 | [Deploy Polaris stack](#deploy-polaris-stack)                 | Polaris stack deployed                                  |

## Install the Afon CLI locally

```md
As a cloud engineer
I want to install the Afon CLI locally
So that I can deploy Polaris
```

**Acceptance criteria**

```md
Given a local system 
When we install the Afon CLI distribution
Then the Afon CLI is ready to use
```

**How to do it**

Download from [Afon Distribution](https://drive.google.com/drive/folders/1b0YkwVXC5ERvDavpeGe9L398cgnz_Bnt) the latest `afon-<version>-cli.zip` release and unzip it to a dir of choice.  The recommended default is `$HOME/.afon/cli`.   Set env variable `AFON_CLI_DIR` pointing to chosen dir and add it to your system path.

Using `bash`, in `~/.bash_profile`:
```shell
export AFON_CLI_DIR=$HOME/.afon/cli

PATH=$AFON_CLI_DIR:$PATH
export PATH
```

To verify the CLI is properly installed, execute to show the list of available commands:
```shell
afon-cli.sh
```
CLI docs are found in `$AFON_CLI_DIR/docs`.


## Setup local env

```md
As a cloud engineer
I want to set up the local env 
So that I build and deploy Afon on my cloud infrastructure
```

**Depends on:** Access to Linux or macOS

**Acceptance criteria**

```md
Given a local system (linux or macos)
When we run the following setup-local action for cloud <cloud>
Then the Afon build env is set up locally for cloud <cloud>
```

**How to do it**

```shell
afon-cli.sh setup-local -c <cloud>
```

The following packages are installed:
- git
- jdk
- maven
- docker
- opentofu
- kubernetes-cli
- awscli eksctl
- azure-cli kubelogin
- google-cloud-cli

Of the cloud cli packages, only the ones relative to the `<cloud>` specified are installed.
If no `<cloud>` is specified all are installed.

## Provision app registration

```md
As a cloud engineer
I want to provision a cloud registration and grant it appropriate permissions
So that Polaris env can be created
```

**Depends on:** A clean cloud subscription with permissions to create resource groups, storage accounts and app registrations. 

**How to do it**

```shell
az account management-group create --name AfonFabricGroup
az account management-group subscription add --name AfonFabricGroup --subscription "AfonFabric"
```
This can also be achieved via the Azure portal.

## Register Polaris domain name

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

AWS Route53 is our recommended approach for domain purchases. A domain can also be provided by the Emrys team eg `<sde>polaris<cloud>.click`.   

**NOTE:** Conventionally, we assume that if `<cloud>` is not specified in the domain name, it refers to `aws`.


## Create DNS zone

```md
As a cloud engineer 
I want to create a DNS zone for the Polaris domain
So that cloud-hosted traffic resolves correctly and can be served over HTTPS
```

**Acceptance criteria**

```md
Given a Polaris domain name is available
When DNS records are configured
Then `dig A <domain> +short` resolves to the expected IP address
```

**How to do it**

Follow the [DNS configuration](https://github.com/emrys-health/afon-core/blob/main/docs/dns_config.md) documentation.

## Create config

```md
As a cloud engineer
I want to generate the properties for our environment <env> and cloud
So that CLI commands know which domain, roles, storage and credentials belong to the env's chosen cloud.
```

**Acceptance criteria**

```md
Given my organisation's chosen cloud, account details, region, bucket names and chosen domain
When configuration properties are set up, both the common ones and the cloud specific ones,
Then all the configuration properties are replaced with real, env-specific values
```

**How to do it**

Choose the name of your env, eg it could be `dev` or `stg` in your organisation:
```shell
afon-cli.sh config-init -e <env> -c <cloud>
```
This command creates a config dir `$AFON_HOME/config-<env>` and symlinks it to `$AFON_HOME/config`.

Do the same for `cdktf` command to create a ckdtf dir `$AFON_HOME/cdktf-<env>` and symlink `$AFON_HOME/cdktf`.
```shell
afon-cli.sh cdktf-init -e <env> -c <cloud>
```
The `cdktf` dir will hold all your deployment Terraform state.

You can also import/export config files as a zip:
```shell
afon-cli.sh config-{import|export} <path-to-config-zip> -e <env>
```
See [configuration](https://github.com/emrys-health/afon-core/blob/main/docs/configuration.md) for more details.

After the config files are completed (or imported) you can validate them for a given cloud:
```shell
afon-cli.sh config-validate -c <cloud>
```

You can also check the configured user, named by `USER_ID`, which clients authenticate as:
```shell
afon-cli.sh config-user
```

## Create cloud env

```md
As a cloud engineer
I want to build create a cloud env
So that I can build and deploy Polaris
```

**Acceptance criteria**

```md
Given a local system
When we run the following exec action for the cloud <cloud>
Then the Afon env is created for cloud <cloud>
```

**How to do it**

```shell
afon-cli.sh <cloud>-create -e <env>
```

See [cli cloud](https://github.com/emrys-health/afon-core/blob/main/docs/cli/cloud.md) for more details.

## Deploy Polaris stack

```md
As a cloud engineer
I want to run the Afon data provider deployment against our cloud environment
So that Polaris is are running in the Kubernetes cluster with the catalog configured, ready to receive synthetic data
```

**Acceptance criteria**

```md
Given previous tasks are completed
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
afon-cli.sh deploy-<cloud>-polaris-stack
```

## Resourcing: technical

Indicative time and the role we'd typically expect it to sit with, against each task above — based on Emrys's resourcing plan for Phase One (indicatively ~2 days total technical time per SDE) and confirmed individually with your technical lead at kickoff. We’ll work with you to support available technical leads with the build.

Ideally, technical leads would become confident to deploy the data fabric in their own environment. 

For those SDE that are very time constrained the minimum expected tasks on this list to complete are 1-5. 

