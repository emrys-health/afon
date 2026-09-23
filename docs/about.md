# About Afon

Afon, Emrys Health's vendor-agnostic data fabric, connects secure data environments so that approved researchers can work across data held by more than one organisation.

Data remains where it is held. Afon connects it so it can be studied.

The code in this repository is intended to be released under the Apache 2.0 licence.

## Why it exists

Many research questions need data from more than one secure data environment (SDE). Each SDE runs its own stack, identity model, and approvals. A study that spans organisations usually means moving data, rebuilding pipelines, or not running the study at all.

Afon provides a shared layer that runs inside each participating organisation. Approved queries can work across environments without patient data leaving the organisation that holds it.

## How it works

Each organisation runs its own Afon deployment, on its own infrastructure, operated by its own team. Deployments connect through shared standards rather than a central platform.

Afon is built from open components:

| Component      | Role                                                                     |
|----------------|--------------------------------------------------------------------------|
| Apache Iceberg | Open table format for data held in each environment                      |
| Apache Polaris | Catalog and governance, including scoped, short-lived credential vending |
| Trino          | Federated query engine for approved queries across environments          |
| Keycloak       | Identity, authentication and role-based access                           |
| Cilium         | Network policy and isolation between workloads                           |

Afon supports eyes-on work, where analysis happens interactively inside a secure environment, and eyes-off federated analysis in the same fabric. Real studies often need both.

## What Afon adds

The components above are mature and widely used. On their own, they do not give a health system a working fabric. Each organisation would still have to work out how to configure them for sensitive data, how to connect them to what it already runs, how to prove the arrangement is safe, and how to contract for it. Afon is that missing layer.

It provides:

- A single command-line blueprint stands up the whole fabric, configured for secure data environments. The same package has been deployed on Azure and AWS, run by different hosting partners and in-house teams. It is designed for GCP and has not yet been deployed there.
- A system that connects to what local areas already run. Afon sits over existing platforms rather than replacing them. Environments built on Snowflake, Azure TRE and AWS have connected as they are. No participating organisation has had to buy or change software to take part.
- Governance built into the data path with access is enforced at the catalog. Polaris vends scoped, short-lived credentials for each approved query, so researchers never hold standing credentials to another organisation's data. Every access is attributable to a person, a project and an approval.
- Audit that works across organisation level, with each site keeping its own audit trail. Afon aggregates audit events across participating sites so that a multi-organisation study can be reviewed as one study, not reconstructed from several logs.
- A documented security model mapped to SATRE and the Five Safes, with the deployment evidence assurance teams need. Where evidence is still being gathered, we say so rather than claim ahead of it.
- A credible exit at every point, and data stays at source in open table formats. The catalog, the code and the data all remain with the organisation. If Afon were withdrawn tomorrow, every participating organisation would keep everything it has.
- It works because it is built with open components, and each one has a large community behind it, is documented publicly, and can be operated by engineers an public sector organisation can already hire. Nothing in the fabric depends on a single vendor's roadmap.

## Safeguards

Afon is designed to align with SATRE and the Five Safes.

- Access is limited to approved researchers working on approved projects.
- Queries, credential issues, and output release are logged for audit.
- The organisation that holds the data controls output release.
- There is no usage of telemetry. Nothing is reported back to Emrys Health or anyone else.

Open code is not open data. The licence covers the software only. It gives no one access to any data.

## What Afon is not

- **Not a national platform.** Afon does not replace the Federated Data Platform, which serves NHS operational needs. Afon addresses research connectivity between secure data environments.
- **Not a network to join.** Every deployment belongs to the organisation that runs it. There is no membership, no central application window and no per-query charge.
- **Not a replacement for existing infrastructure.** Afon runs alongside what an organisation already has, and does not require anything to be switched off.

## Getting started

Start with the [README](https://github.com/emrys-health/afon-core/blob/main/README.md). It links to the quickstart and to reference configurations for common NHS stack patterns.

## Contributing

We welcome issues, questions and contributions. See [CONTRIBUTING.md](https://github.com/emrys-health/afon-core/blob/main/CONTRIBUTING.md) for how to take part, and [SECURITY.md](https://github.com/emrys-health/afon-core/blob/main/SECURITY.md) for how to report a vulnerability privately.

## Maintenance and support

Emrys Health maintains Afon and publishes a roadmap and release notes in this repository.

The software is free to use under its licence. Emrys Health provides implementation, integration with local stacks, and ongoing run and support services, starting with an initial assessment of an organisation's environment. See [SUPPORT.md](https://github.com/emrys-health/afon-core/blob/main/SUPPORT.md).

## Licence

Afon is licensed under the [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).

Under section 6 of that licence, it does not grant permission to use the Afon or Emrys Health names, logos, or trademarks.
