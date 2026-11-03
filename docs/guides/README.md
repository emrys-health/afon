# Scope

The scope of the guides to provide step-by-step documentation to set up and deploy a TRE or Data Provider for the diffent clouds. 

# AWS docs

End-to-end procedure to set up the AWS env and deploy the stacks of a TRE and its data providers:

| Doc                     | Stack | Contents |
|-------------------------|-------|----------|
| [config](./aws/config.md) | any | The config and setup steps common to both sides: installing the Afon CLI and local tools, creating and filling the config, user credentials (login, IAM user and access key), `aws-create`, Route 53 zones, ACM certs, checks and teardown. |
| [tre](./aws/tre.md)   | `aws-full` | The TRE side: KeyCloak, Polaris, Spark, Trino, Jupyter, Gitea and monitoring on EKS. Step table, stack-specific config, deploy, verify, Data Provider whitelisting, teardown. |
| [data](./aws/data.md) | `aws-polaris` | The Data Provider side: a Polaris catalog on EKS, relying on the TRE's KeyCloak. Same layout as the TRE doc. |

Start with [config](./aws/config.md), then follow the doc of the side you deploy.

# Azure docs

End-to-end procedure to set up the Azure env and deploy the stacks of a TRE and its data providers:

| Doc                     | Stack | Contents                                                                                                                                                                                                                                                                                                    |
|-------------------------|-------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [config](./azure/config.md) | any | The config and setup steps common to both sides: installing the Afon CLI and local tools, the one-time Entra ID app created by an admin (`create-admin.sh`) and the values it provides, creating and filling the config, login, DNS zones, TLS certs in the Key Vault, `azure-create`, checks and teardown. |
| [tre](./azure/tre.md)   | `azure-full` | The TRE side: KeyCloak, Polaris, Spark, Jupyter and Gitea on AKS. Step table, stack-specific config, deploy, verify, Data Provider whitelisting, teardown.                                                                                                                                                  |
| [data](./azure/data.md) | `azure-polaris` | The Data Provider side: a Polaris catalog on AKS, relying on the TRE's KeyCloak. Same layout as the TRE doc, with its own config, deploy, verify, whitelisting and teardown.                                                                                                                                |

Start with [config](./azure/config.md), then follow the doc of the side you deploy.

# GCP docs

End-to-end procedure to set up the GCP env and deploy the stacks of a TRE and its data providers:

| Doc                     | Stack | Contents |
|-------------------------|-------|----------|
| [config](./gcp/config.md) | any | The config and setup steps common to both sides: installing the Afon CLI and local tools, creating and filling the config, login, `gcp-create`, DNS zones, TLS certs in Secret Manager, checks and teardown. |
| [tre](./gcp/tre.md)   | `gcp-full` | The TRE side: KeyCloak, Polaris, Spark, Trino, Jupyter, Gitea and monitoring on GKE. Step table, stack-specific config, deploy, verify, Data Provider whitelisting, teardown. |
| [data](./gcp/data.md) | `gcp-polaris` | The Data Provider side: a Polaris catalog on GKE, relying on the TRE's KeyCloak. Same layout as the TRE doc. |

Start with [config](./gcp/config.md), then follow the doc of the side you deploy.
