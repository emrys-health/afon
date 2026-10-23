# Azure Data Provider: end-to-end setup of `azure-polaris`

Summary of the procedure to deploy the `azure-polaris` stack for a Data Provider: a Polaris catalog (Iceberg, synthetic data)
reachable over HTTPS on AKS.  The common config and CLI steps are in [config](config.md).

`azure-polaris` deploys no KeyCloak of its own: it relies on the TRE's, served at `OIDC_DOMAIN_NAME`
(from [azure-full](tre.md), or a standalone `azure-keycloak`), and checks it answers before deploying.

## Steps

| # | Step                                                             | Where |
|---|------------------------------------------------------------------|-------|
| 1 | Install the CLI and local tools                                  | [config](config.md): [§2](config.md#2-install-the-afon-cli), [§3](config.md#3-install-the-local-tools) |
| 2 | Admin creates the Entra ID app (`create-admin.sh`)               | [§4](config.md#4-admin-part-the-entra-id-app) |
| 3 | Register the Polaris domain, eg `<env>polarisazure.click`        | below |
| 4 | Create and fill the config; switch env; login                    | [§5](config.md#5-create-and-fill-in-the-config), [§6](config.md#6-login) |
| 5 | Create the env: `afon-cli.sh azure-create azure-polaris`         | [§9](config.md#9-create-the-env-and-check-the-storage) |
| 6 | Point the registrar at the Azure nameservers; issue the TLS cert | [§7](config.md#7-dns-zones), [§8](config.md#8-tls-certificate-in-the-key-vault) |
| 7 | Deploy: `afon-cli.sh manage-stack setup azure-polaris`           | below |
| 8 | Whitelist the TRE's IP at the Data Provider                      | below |

## Stack-specific config

`afon-azure.properties`:
```shell
AZURE_POLARIS_DOMAIN_NAME=${ENV_NAME}polarisazure.click
AZURE_POLARIS_LOG_BUCKET=<optional: container for Polaris logs>
AZURE_POLARIS_ALLOWED_CIDRS=<optional: CIDRs admitted to Polaris, eg the TRE's IP>
```
`afon.properties`: `OIDC_DOMAIN_NAME` and `OIDC_ISSUER_URL` of the TRE's KeyCloak.

If the DNS zone of `AZURE_POLARIS_DOMAIN_NAME` is not in your resource group, the stack can't serve it: `azure-create` makes it.

## Deploy and verify

```shell
afon-cli.sh manage-stack setup azure-polaris
```
Steps: `delete_stacks_conflicting_with_azure_polaris` → `check_keycloak_answers` → `deploy_azure_polaris_stack` →
`wait_on_polaris_and_keycloak_for_cloud_azure` → `create_all_catalogs_for_cloud_azure`.

```shell
afon-cli.sh azure-update-kube-config catalog
kubectl get pods                    # Polaris Running/Ready, ingress has an address
afon-cli.sh azure-polaris           # is Polaris serving?
```
`https://$AZURE_POLARIS_DOMAIN_NAME` must load with a valid TLS certificate.

## Network whitelisting

To limit token exfiltration, restrict who reaches Polaris with `AZURE_POLARIS_ALLOWED_CIDRS` (the cluster's pod CIDR is
added automatically) and whitelist the TRE's IP, see [#162](https://github.com/emrys-health/afon-core/issues/162).

## Teardown

```shell
afon-cli.sh manage-stack delete azure-polaris
afon-cli.sh azure-destroy       # then a local admin runs destroy-admin.sh
```
