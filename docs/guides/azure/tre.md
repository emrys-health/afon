# Azure TRE: end-to-end setup of `azure-full`

Summary of the procedure to deploy the `azure-full` stack for a TRE: KeyCloak, Polaris, Spark, Jupyter and Gitea
on AKS, from which the TRE queries the data providers' Polaris catalogs.  It mirrors the [data provider](data.md) doc;
the common config and CLI steps are in [config](config.md), the stack details in [stacks](../../stacks.md).

`azure-full` has its own KeyCloak at `OIDC_DOMAIN_NAME`, which the data providers' `azure-polaris` stacks use.

## Steps

| # | Step                                                              | Where |
|---|-------------------------------------------------------------------|-------|
| 1 | Install the CLI and local tools                                   | [config](config.md): [§2](config.md#2-install-the-afon-cli), [§3](config.md#3-install-the-local-tools) |
| 2 | Admin creates the Entra ID app (`create-admin.sh`)                | [§4](config.md#4-admin-part-the-entra-id-app) |
| 3 | Register three domains: OIDC, Polaris and Jupyter                 | below |
| 4 | Create and fill the config; switch env; login                     | [§5](config.md#5-create-and-fill-in-the-config), [§6](config.md#6-login) |
| 5 | Create the env: `afon-cli.sh azure-create azure-full`             | [§9](config.md#9-create-the-env-and-check-the-storage) |
| 6 | Point the registrar at the Azure nameservers; issue the TLS certs | [§7](config.md#7-dns-zones), [§8](config.md#8-tls-certificate-in-the-key-vault) |
| 7 | Deploy: `afon-cli.sh manage-stack setup azure-full`               | below |
| 8 | Give the Data Providers the TRE's IP to whitelist                 | below |

## Stack-specific config

`afon-azure.properties`:
```shell
AZURE_POLARIS_DOMAIN_NAME=${ENV_NAME}polarisazure.click
AZURE_JUPYTER_DOMAIN_NAME=${ENV_NAME}jupyterazure.click
AZURE_GITEA_BUCKET=<required: blob container for Gitea, made by azure-create>
AZURE_POLARIS_LOG_BUCKET=<optional>
AZURE_AKS_NODE_POOL=nodeCount:4
```
`afon.properties`:
```shell
OIDC_DOMAIN_NAME=<oidc domain>      # its DNS zone must be in AZURE_RESOURCE_GROUP too
```
`whitelist.yaml`: the `azure` section of the template already lists the OIDC, Polaris and Jupyter domain names and the storage
account, so keep it (the entries of the clouds you do not deploy to are left out as their properties are not set).
`secret.properties`: `KEYCLOAK_BOOTSTRAP_ADMIN_*`, `KEYCLOAK_DB_*` and `USER_ID`.
`secret-azure.properties`: `AZURE_CONNECTION_STRING` is required (Loki keeps the monitoring logs in the `azure` catalog container).

**Why these matter:**
- `azure-full` needs **4 nodes** of the default size, else pods such as the Spark Connect driver stay Pending (`Insufficient cpu`).
  Check with `afon-cli.sh kube-estimator azure-full 4`.
- The network policies limit what notebooks and Spark can resolve to `whitelist.yaml`; missing names give
  `UnknownHostException: <polaris domain>: Temporary failure in name resolution`.  They use the cluster's Advanced Container
  Networking Services, which the stack enables.

To use the KeyCloak of a separate `azure-keycloak` stack instead, set `AZURE_KEYCLOAK_EXTERNAL=true`: then only the Polaris and Jupyter zones are needed
and `azure-create false azure-keycloak` creates the OIDC one.

## Deploy and verify

```shell
afon-cli.sh validate-stack azure-full
afon-cli.sh manage-stack setup azure-full
```
```shell
afon-cli.sh azure-update-kube-config tre
kubectl get pods
afon-cli.sh azure-keycloak          # is KeyCloak serving?
afon-cli.sh azure-polaris           # is Polaris serving?
```
`https://$OIDC_DOMAIN_NAME`, `https://$AZURE_POLARIS_DOMAIN_NAME` and `https://$AZURE_JUPYTER_DOMAIN_NAME` must load with valid TLS certificates.

## Data Providers whitelisting

The TRE's egress IP is what the Data Providers add to their whitelist (`AZURE_POLARIS_ALLOWED_CIDRS`).

## Teardown

```shell
afon-cli.sh manage-stack delete azure-full
afon-cli.sh azure-destroy       # then a local admin runs destroy-admin.sh
```
