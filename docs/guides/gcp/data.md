# GCP Data Provider: end-to-end setup of `gcp-polaris`

Summary of the procedure to deploy the `gcp-polaris` stack for a Data Provider: a Polaris catalog (Iceberg, synthetic data)
reachable over HTTPS on GKE.  The common config and CLI steps are in [config](config.md).

`gcp-polaris` deploys no KeyCloak of its own: it relies on the TRE's, served at `OIDC_DOMAIN_NAME`
(from [gcp-full](tre.md), or a standalone `gcp-keycloak`), and checks it answers before deploying.

## Steps

| # | Step                                                             | Where |
|---|------------------------------------------------------------------|-------|
| 1 | Install the CLI and local tools                                  | [config](config.md): [§2](config.md#2-install-the-afon-cli), [§3](config.md#3-install-the-local-tools) |
| 2 | Register the Polaris domain, eg `<env>polarisgcp.click`          | below |
| 3 | Create and fill the config; switch env; login                    | [§4](config.md#4-create-and-fill-in-the-config), [§5](config.md#5-login) |
| 4 | Create the env: `afon-cli.sh gcp-create gcp-polaris`             | [§8](config.md#8-create-the-env) |
| 5 | Create the DNS zone and point the registrar at the Google nameservers; issue the TLS cert | [§6](config.md#6-dns-zones), [§7](config.md#7-tls-certificate-in-secret-manager) |
| 6 | Deploy: `afon-cli.sh manage-stack setup gcp-polaris`             | below |
| 7 | Whitelist the TRE's IP at the Data Provider                      | below |

## Stack-specific config

`afon-gcp.properties`:
```shell
GCP_POLARIS_DOMAIN_NAME=${ENV_NAME}polarisgcp.click
GCP_POLARIS_ALLOWED_CIDRS=<optional: CIDRs admitted to Polaris, eg the TRE's IP>
```
`afon.properties`: `OIDC_DOMAIN_NAME` of the TRE's KeyCloak (or `GCP_OIDC_DOMAIN_NAME` in `afon-gcp.properties`).  `OIDC_ISSUER_URL`
needs no change, as it follows the domain name.

If the DNS zone of `GCP_POLARIS_DOMAIN_NAME` is not in your project, the stack can't serve it: `afon-cli.sh dns-create-zones gcp <stack>` makes it, after `gcp-create`.

## Deploy and verify

```shell
afon-cli.sh manage-stack setup gcp-polaris
```
Steps: `delete_stacks_conflicting_with_gcp_polaris` → `check_keycloak_answers` → `deploy_gcp_polaris_stack` →
`wait_on_polaris_and_keycloak_for_cloud_gcp` → `create_all_catalogs_for_cloud_gcp`.

```shell
afon-cli.sh gcp-update-kube-config catalog
kubectl get pods                    # Polaris Running/Ready, ingress has an address
afon-cli.sh gcp-polaris             # is Polaris serving?
```
`https://$GCP_POLARIS_DOMAIN_NAME` must load with a valid TLS certificate.

## Network whitelisting

To limit token exfiltration, restrict who reaches Polaris with `GCP_POLARIS_ALLOWED_CIDRS` (the cluster's pod range, node subnet and pinned
`GCP_EGRESS_IP` are added automatically) and whitelist the TRE's IP, see [#162](https://github.com/emrys-health/afon/issues/162).

## Teardown

```shell
afon-cli.sh manage-stack delete gcp-polaris
afon-cli.sh gcp-destroy
```
