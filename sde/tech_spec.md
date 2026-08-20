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

**4. DNS Configuration**

```md
As a cloud engineer 
I want to create a DNS zone for the Polaris domain
So that cloud-hosted traffic resolves correctly and can be served over HTTPS
```
**Depends on:** Task 1,3

**Acceptance criteria**

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

```shell
./afon.sh -a config-init -c <cloud>
```

You can also import/export config files as a zip:
```shell
./afon.sh -a config-{import|export} --config-zip <path-to-config-zip>
```

After the config files are completed (or imported) you can validate them:
```shell
./afon.sh -a config-validate -c <cloud>
```

**Acceptance criteria**

Follow the [configuration](../docs/configuration.md) documentation.

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

```shell
./afon.sh -a exec -t script --exec-script <cloud>/create.sh 
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

```shell
./afon.sh -a build -p cli -c <cloud>
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

```shell
./afon.sh -a cli --cli-cmd deploy-<cloud>-polaris-stack
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

## **Resourcing: technical**

Indicative time and the role we'd typically expect it to sit with, against each task above — based on Emrys's resourcing plan for Phase One (indicatively ~4 days total technical time per SDE) and confirmed individually with your technical lead at kickoff. There are a range of technical roles across SDEs and we are mindful of capacity over the summer. We’ll work with you to support available technical leads with the build.

Ideally, technical leads would become confident to deploy the data fabric in their own environment. 
*Total indicative technical ask: ~4 days (~30 hrs against a 7.5-hour day) per SDE over project duration — roughly 0.4 day/week, ≈0.08 WTE.*

<table>
  <tr>
   <td><strong>Task</strong>
   </td>
   <td><strong>Indicative time</strong>
   </td>
   <td><strong>Needed by</strong>
   </td>
  </tr>
  <tr>
   <td>1. Cloud app registration & role assignments
   </td>
   <td>~0.5 day (2–4 hrs)
   </td>
   <td>w/c 3 Aug
   </td>
  </tr>
  <tr>
   <td>2. Register a domain name
   </td>
   <td>~1–2 hrs, plus registrar lead time
   </td>
   <td>w/c 3 Aug
   </td>
  </tr>
  <tr>
   <td>3. Configuration
   </td>
   <td>~2 hrs
   </td>
   <td>w/c 3 Aug
   </td>
  </tr>
  <tr>
   <td>4. Create DNS zone & certificate
   </td>
   <td>~0.5 day, plus ~10 min propagation
   </td>
   <td>w/c 4 Aug — before 7 Aug Logging demo
   </td>
  </tr>
  <tr>
   <td>5. Build Afon CLI locally
   </td>
   <td>~0.5 day
   </td>
   <td>w/c 3 Aug
   </td>
  </tr>
  <tr>
   <td>6. Deploy the Afon stack
   </td>
   <td>~0.5 day
   </td>
   <td>w/c 3 Aug — before 7 Aug Logging demo
   </td>
  </tr>
  <tr>
   <td>7. Integration with Keycloak / OIDC
   </td>
   <td>~0.5 day
   </td>
   <td>w/c 1 Sep — before 4 Sep Contract→environment demo
   </td>
  </tr>
</table>


## **Non-technical asks**

We recognise that the non-technical build is complex and time-consuming and with both known and unknown factors. We want to work closely with SDEs to scope and blueprint this process, working through blockers and issues as they arise.

We’ll keep time commitments non-onerous over the course of the project but suggest the following touchpoints:


<table>
  <tr>
   <td><strong>Touchpoint</strong>
   </td>
   <td><strong>Cadence / deadline</strong>
   </td>
  </tr>
  <tr>
   <td>30-min check-in with each programme director / PM (flexible)
   </td>
   <td>Weekly
   </td>
  </tr>
  <tr>
   <td>An overview and kickoff per SDE to scope end-to-end, anticipate blockers, gauge capacity and commitment to the project 
   </td>
   <td>1 hour meeting, completed by 6th August
   </td>
  </tr>
  <tr>
   <td>A session in-person with technical leads to set up side by side (and then ad hoc, as needed).
   </td>
   <td>3 hours max, completed by Tuesday 4th August
   </td>
  </tr>
  <tr>
   <td>Helen Rowbottom, Programme Director for data fabric, to meet with all PMO leads across SDEs to end-to-end scope the IG, costing  and governance questions for how this would work if using real patient data
   </td>
   <td>Initial 45 minute meeting by August 7th, then ongoing as part of 30 min weekly catch up
   </td>
  </tr>
  <tr>
   <td>Iterative workshops 
   </td>
   <td>Date windows below 
   </td>
  </tr>
  <tr>
   <td>Slack for tech (+ GitHub issues) & a separate light-touch PMO Slack.
   </td>
   <td>Added upon confirmation of participation
   </td>
  </tr>
  <tr>
   <td>4 x Friday-PM demo drop-in for all SDEs
   </td>
   <td>3pm on 7 Aug, 21 Aug, 4 Sep, 18 Sep
   </td>
  </tr>
</table>


## **Tests**

We’d like to validate these questions, and others that we scope with you, to answer the question: what would need to be true to build this technical infrastructure and test feasibility for real patient data by using synthetic data, with the IG process for both?


<table>
  <tr>
   <td><strong>Test</strong>
   </td>
   <td><strong>Who for</strong>
   </td>
   <td><strong>What does it mean</strong>
   </td>
   <td><strong>How will we test it</strong>
   </td>
   <td><strong>What does success look like</strong>
   </td>
   <td><strong>What is the output</strong>
   </td>
  </tr>
  <tr>
   <td>Demonstrating that the software can be deployed on infrastructure that they own
   </td>
   <td>NHS IT team (Service manager)
   </td>
   <td>It is technically simple to deploy the data fabric node within existing local infrastructure (Trust, SDE, or ICB) without requiring specialist developers.
   </td>
   <td>Define reference architectures for 3 common NHS environments (e.g., Azure for ICBs, On-Prem for older Trusts, AWS for SDEs). Provide a guided deployment playbook and shadow a Band 5 IT manager as they install it. Map and solve network/firewall barriers. We need to map any technical issues and other barriers, and demonstrate / work through solutions.
   </td>
   <td>Successful deployment in under X hours; zero requirement for external DevOps support.
   </td>
   <td>An "NHS Deployment Playbook" detailing step-by-step installation for Trusts, ICBs, and SDEs, alongside an Evaluation Report of the deployment trials.
   </td>
  </tr>
  <tr>
   <td>That the infrastructure meets the security requirements to hold sensitive data
   </td>
   <td>NHS IT team (Security team)
   </td>
   <td>The team is able to put data into the environment, meeting their cyber and other security requirements.
   </td>
   <td>We need to map out the national cyber and other security requirements (NHS DSPT, DTAC etc). We need to test these are aligned with local requirements, both technically but also with insurance, contracts etc. We need to ensure the product meets these requirements, and guidance supporting it meets the tests outlined.
   </td>
   <td>Benchmark config against NHS DTAC and DSPT. Conduct pen testing of node interface and end to end data encryption (at rest and in transit)
   </td>
   <td>A table of requirements, risk assessment of how product meets (matrix showing DTAC/DSPT requirements and what is met), a verified risk assessment report and guidance for how to work with local organisations to meet them.
   </td>
  </tr>
  <tr>
   <td>Expected cost to implement based on existing capability compared to alternatives
   </td>
   <td>NHS Finance team (CFO)
   </td>
   <td>Demonstrate the costs of implementing a data fabric, and how funding would flow
   </td>
   <td>Find alternatives and their costs, and write these out in a clear way. Cost of the current fabric, including some kind of high level time-and-motion study mapping time / skills needed to connect to a new data source vs building a traditional ETL pipeline and time to deploy. Quantify costs and ROI savings.
   </td>
   <td>Clear evidence that implementation leverages existing Band 5/6 capabilities and costs significantly less than traditional ETL setups.
   </td>
   <td>A report showing costs and ROI, comparing fabric implementation costs against alternatives.
   </td>
  </tr>
  <tr>
   <td>Expected cloud costs to run and maintain a project, given a typical analytical workload, compared to alternatives
   </td>
   <td>NHS Finance team (Cloud FinOps)
   </td>
   <td>Provide clear, predictable models for cloud compute and storage costs under a typical analytical workload, highlighting the efficiency of federated queries over alternatives
   </td>
   <td>Run baseline analytical workloads (e.g., standard cohort discovery queries) across the federated network. Measure actual data egress, compute spikes, and storage footprints to compare against centralised data lake equivalents.
   </td>
   <td>Cloud storage and compute costs are definitively projected and show a clear financial advantage over data-duplication models.
   </td>
   <td>Model for cloud costs predictions and TCO, worksheet simulating these.
   </td>
  </tr>
  <tr>
   <td>They can log in to the system and query data but cannot exfiltrate data
   </td>
   <td>Researcher (Academic/ Clinical Analyst)
   </td>
   <td>Verify that researchers can easily authenticate, log in, and query data inside the environment while strictly preventing the unauthorized exfiltration of raw patient data.
   </td>
   <td>Establish a secure boundary environment. Attempt common exfiltration tests and see what happens.
   </td>
   <td>100% of attempted exfiltration vectors are blocked; raw data remains securely at the source or inside the secure zone.
   </td>
   <td>Penetration test log and a researcher access control table outlining enforced boundaries and permissions.
   </td>
  </tr>
  <tr>
   <td>They have the tools needed to execute a typical analytical workload
   </td>
   <td>Researcher (Data Scientists and Epidemiologist)
   </td>
   <td>Ensure the environment provides researchers with the standard analytical tools necessary to execute typical population health or clinical research workloads.
   </td>
   <td>Provide a pilot cohort of researchers with access to the environment. Have them execute a typical multi-source query and document any friction regarding package installations or compute limits.
   </td>
   <td>Researchers successfully complete the workload without requiring external workarounds or missing critical libraries.
   </td>
   <td>User Acceptance Testing outcome and a document detailing supported libraries and tools.
   </td>
  </tr>
  <tr>
   <td>A validated process for sharing pseudonymized patient data in this infrastructure
   </td>
   <td>IG team
   </td>
   <td>Validate that patient-identifiable data is robustly pseudonymized at the local boundary before it is queryable via the fabric, fully satisfying Caldicott principles.
   </td>
   <td>Run a sample raw dataset through the data fabric's transformation layer. Audit the output using automated re-identification attack simulations to guarantee direct identifiers are masked/removed.
   </td>
   <td>Zero patient-identifiable data leaks past the local boundary; successful mock sign-off from an IG Lead or Caldicott Guardian.
   </td>
   <td>Document outlining the masking process and other steps for IG compliance.
   </td>
  </tr>
  <tr>
   <td>A validated process for contracting between organisations, including data access agreements and intellectual property
   </td>
   <td>Commercial team
   </td>
   <td>Establish a standardized, legally validated process for contracting between participating organisations, defining clear parameters for Data Access Agreements and intellectual property. This needs to include third parties managing these environments.
   </td>
   <td>Review current contracts with suppliers in a NHS organisation and understand potential barriers and cost for implementation, especially where the environment is not in control. Convene a panel of NHS commercial and legal experts to draft standard templates for use of fabric.
   </td>
   <td>Consensus among the legal panel that the DAA templates are robust, fair, and adhere to NHS standard contracting principles. Clear approach and series of instructions to suppliers managing infrastructure, which enables deployment (affordable, managable)
   </td>
   <td>Standardized Data Access Agreement (DAA) Framework and a Commercial Playbook for multi-party and third-party collaborations.
   </td>
  </tr>
  <tr>
   <td>That the information recorded during the project is sufficient to assure stakeholders that data is not exfiltrated and that all processing of data is in accordance with their agreements
   </td>
   <td>HDRS team
   </td>
   <td>Prove that the system logs every single data interaction, transaction, and query with sufficient granularity to completely assure national regulators that no data exfiltration occurred.
   </td>
   <td>Mock security incident and extract system logs. Audit the logs to ensure they capture the who, what, when, and where of data processing.
   </td>
   <td>Logs are proven to be immutable, instantly retrievable, and pass a simulated regulatory audit without gaps in the data lifecycle.
   </td>
   <td>Immutable audit log and assurance framework report, supporting HDRS needs.
   </td>
  </tr>
  <tr>
   <td>What the process is for testing the data fabric using synthetic data
   </td>
   <td>SDE PMO teams
   </td>
   <td>Using synthetic data has an IG process. Gauge whether sites have an existing blueprint or if one needs developing.
   </td>
   <td>Amend existing blueprints for synthetic data or develop the process with an SDE
   </td>
   <td>A duplicable blueprint at SDE-level for testing synth data.
   </td>
   <td>A process for testing synthetic data for the purpose of the data fabric feasibility project
   </td>
  </tr>
</table>


**Resourcing: non-technical & governance**

Total indicative non-technical/governance ask: ~5.5–7 days per SDE excluding the synthetic-data test process, which is still to be confirmed, spread across up to 8–9 different roles. If representatives rotate across the workshops, no single individual need be asked for more than ~1.5 days; if one technical lead and one PMO/ops lead attend everything in their lane, each carries ~2.5–3.5 days, including the 4-hour DSIT architecture & security review below.


<table>
  <tr>
   <td><strong>Ask</strong>
   </td>
   <td><strong>Suggested SDE contact</strong>
   </td>
   <td><strong>Indicative time</strong>
   </td>
   <td><strong>Cadence / when</strong>
   </td>
  </tr>
  <tr>
   <td>Deployment test
   </td>
   <td>Operational/Technical — e.g. a cloud platform engineer or database manager
   </td>
   <td>~0.5–1 day
   </td>
   <td>One-off, w/c 21 Jul–4 Aug
   </td>
  </tr>
  <tr>
   <td>Security test
   </td>
   <td>Technical leads
   </td>
   <td>~0.5–1 day
   </td>
   <td>One-off, w/c 4–18 Aug
   </td>
  </tr>
  <tr>
   <td>Cost to implement test
   </td>
   <td>PMO/ops lead
   </td>
   <td>~0.5 day
   </td>
   <td>One-off
   </td>
  </tr>
  <tr>
   <td>Cost to run (cloud) test
   </td>
   <td>Technical leads
   </td>
   <td>~0.5 day
   </td>
   <td>One-off
   </td>
  </tr>
  <tr>
   <td>Analytical workload test
   </td>
   <td>Technical leads
   </td>
   <td>~0.5–1 day
   </td>
   <td>One-off
   </td>
  </tr>
  <tr>
   <td>Governance (IG) test
   </td>
   <td>PMO/ops lead
   </td>
   <td>~2 hrs
   </td>
   <td>Feeds the IG workshop 17 Aug
   </td>
  </tr>
  <tr>
   <td>Commercial test
   </td>
   <td>PMO/ops lead
   </td>
   <td>~2 hrs
   </td>
   <td>Feeds the Commercials workshop, 25 Aug
   </td>
  </tr>
  <tr>
   <td>Assurance (audit) test
   </td>
   <td>PMO/Technical lead
   </td>
   <td>~0.5 day
   </td>
   <td>Before the 15 Sep hand-over
   </td>
  </tr>
  <tr>
   <td>Synthetic-data test process
   </td>
   <td>PMO/ops lead, working from existing blueprints where they exist
   </td>
   <td>To be confirmed
   </td>
   <td>Ongoing
   </td>
  </tr>
  <tr>
   <td>Kickoff meeting
   </td>
   <td>Whole team introduction to the project and gauge participation
   </td>
   <td>~1 hour (one-off)
   </td>
   <td>By 6 Aug
   </td>
  </tr>
  <tr>
   <td>Weekly 30-min check-in
   </td>
   <td>PMO/ops lead
   </td>
   <td>5 hrs total (~0.01 WTE/week)
   </td>
   <td>Weekly, throughout
   </td>
  </tr>
  <tr>
   <td>Workshop: IG
   </td>
   <td>One PMO/ops and one technical representative
   </td>
   <td>1.5 hrs
   </td>
   <td>13 Aug 1500-1630
   </td>
  </tr>
  <tr>
   <td>Workshop: Security
   </td>
   <td>One PMO/ops and one technical representative
   </td>
   <td>4 hours
   </td>
   <td>17 Aug 1300-1700
   </td>
  </tr>
  <tr>
   <td>Workshop: Commercials
   </td>
   <td>One PMO/ops and one technical representative
   </td>
   <td>1.5 hrs
   </td>
   <td>25 Aug 1400-1530
   </td>
  </tr>
  <tr>
   <td>Workshop: UX
   </td>
   <td>One PMO/ops and one technical representative
   </td>
   <td>1.5 hrs
   </td>
   <td>w/c 1 Sep (date TBC)
   </td>
  </tr>
  <tr>
   <td>
   </td>
   <td>
   </td>
   <td>
   </td>
   <td>
   </td>
  </tr>
  <tr>
   <td><strong>Total (indicative)</strong>
   </td>
   <td>
   </td>
   <td><strong>~5.5–7 days (excl. synthetic-data test, TBC), spread across up to 8–9 roles</strong>
   </td>
   <td>
   </td>
  </tr>
</table>




**Key dates: SDE workshops and demos**

There will be **three 90-minute and one 4-hour** **workshops — IG, Security, Commercials, UX **– for participating SDEs to attend.

There will also be **4 x 45 minute demos** – Functionality and logging, Security, Contract to environment and the next version.

We suggest at minimum that **1 x technical and 1x operational/PMO lead attend all** (and representatives can rotate to accommodate AL etc). All team members from all SDEs are welcome.


<table>
  <tr>
   <td><strong>Workshop/demo</strong>
   </td>
   <td><strong>Date</strong>
   </td>
   <td><strong>Purpose</strong>
   </td>
   <td><strong>Suggested SDE attendee</strong>
   </td>
   <td><strong>Time ask</strong>
   </td>
  </tr>
  <tr>
   <td>Demo: Functionality and logging 
   </td>
   <td>Fri 7 Aug
<p>
1500-1545
   </td>
   <td>Cross-cloud deployment & querying — one notebook talks to data providers on different clouds (Polaris + Iceberg), minting its own temporary credentials, joining data that today cannot be linked. What the system can see, what we keep, and how it can be used.
   </td>
   <td>1 x technical lead
<p>
1 x PMO/ops lead
   </td>
   <td>45 mins
   </td>
  </tr>
  <tr>
   <td>Workshop: IG
   </td>
   <td>13 Aug 1500-1630
   </td>
   <td>Validate the pseudonymised data-sharing process end to end
   </td>
   <td>1 x technical lead
<p>
1 x PMO/ops lead
   </td>
   <td>1.5 hrs
   </td>
  </tr>
  <tr>
   <td>Workshop: Security
<p>
DSIT architecture & security review (Chris Nesbitt-Smith)
   </td>
   <td>17 Aug 1300-1700
   </td>
   <td>Test current data architecture and security/IG position against reality 
<p>
Walk through the security boundary and exfiltration tests ahead of the 21 Aug demo
   </td>
   <td>1 x technical lead
<p>
1 x PMO/ops lead
   </td>
   <td>4 hours
   </td>
  </tr>
  <tr>
   <td>Demo: Security
   </td>
   <td>21 Aug \
1500-1545
   </td>
   <td>A security-focused demo of the Afon data fabric for SDE (Secure Data Environment) leads, covering the security architecture of the fabric — specifically topics like access controls, logging/auditing, and the deployment security model
   </td>
   <td>1 x technical lead
<p>
1 x PMO/ops lead
   </td>
   <td>45 mins
   </td>
  </tr>
  <tr>
   <td>Workshop: Commercials
   </td>
   <td>25 Aug 1400-1530
   </td>
   <td>Contracting process, data-access agreements and IP between organisations
   </td>
   <td>1 x technical lead
<p>
1 x PMO/ops lead
   </td>
   <td>1.5 hrs
   </td>
  </tr>
  <tr>
   <td>Workshop: UX
   </td>
   <td>3 Sep
   </td>
   <td>Review the GUI mock-up feeding the 4 Sep Contract→environment demo
   </td>
   <td>1 x technical lead
<p>
1 x PMO/ops lead
   </td>
   <td>1.5 hrs
   </td>
  </tr>
  <tr>
   <td>Demo: Contract → environment demo  
   </td>
   <td>Fri 4 Sep
<p>
1500-1545
   </td>
   <td>A configuration file and front-end: a signed agreement deploys the infrastructure; we walk through spin-up.
   </td>
   <td>1 x technical lead
<p>
1 x PMO/ops lead
   </td>
   <td>45 mins
   </td>
  </tr>
  <tr>
   <td>Demo: The next version
   </td>
   <td>Fri 18 Sep 1500-1545
   </td>
   <td>To be confirmed — scoping what comes after feasibility
   </td>
   <td>1 x technical lead
<p>
1 x PMO/ops lead
   </td>
   <td>45 mins
   </td>
  </tr>
</table>


Please direct all questions and requests to Helen Rowbottom, Programme Director for Data Fabric, at [helen.rowbottom@emrys.health](mailto:helen.rowbottom@emrys.health). 
