# Scope

The scope of the docs in TRE is to detail the configuration required at TRE level to talk to the SDEs,
and the configuration required at SDE level.

# Azure docs

End-to-end procedure to set up the Azure env and deploy the stacks of a TRE and its SDEs:

| Doc | Stack | Contents |
|-----|-------|----------|
| [azure_config.md](azure_config.md) | any | The config and setup steps common to both sides: installing the Afon CLI and local tools, the one-time Entra ID app created by an admin (`create-admin.sh`) and the values it provides, creating and filling the config, login, DNS zones, TLS certs in the Key Vault, `azure-create`, checks and teardown. |
| [azure_tre.md](azure_tre.md) | `azure-full` | The TRE side: KeyCloak, Polaris, Spark, Jupyter and Gitea on AKS. Step table, stack-specific config, deploy, verify, SDE whitelisting, teardown. |
| [azure_sde.md](azure_sde.md) | `azure-polaris` | The SDE side: a Polaris catalog on AKS, relying on the TRE's KeyCloak. Same layout as the TRE doc, with its own config, deploy, verify, whitelisting and teardown. |

Start with `azure_config.md`, then follow the doc of the side you deploy.
