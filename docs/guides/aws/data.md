# AWS Data Provider: end-to-end setup of `aws-polaris`

Summary of the procedure to deploy the `aws-polaris` stack for a Data Provider: a Polaris catalog (Iceberg, synthetic data)
reachable over HTTPS on EKS.  The common config and CLI steps are in [config](config.md).

`aws-polaris` deploys no KeyCloak of its own: it relies on the TRE's, served at `OIDC_DOMAIN_NAME`
(from [aws-full](tre.md), or a standalone `aws-keycloak`), and checks it answers before deploying.

## Steps

| # | Step                                                             | Where |
|---|------------------------------------------------------------------|-------|
| 1 | Install the CLI and local tools                                  | [config](config.md): [§2](config.md#2-install-the-afon-cli), [§3](config.md#3-install-the-local-tools) |
| 2 | Register the Polaris domain, eg `aws-polaris.<env>.click`        | below |
| 3 | Create and fill the config; switch env; user credentials                 | [§4](config.md#4-create-and-fill-in-the-config), [§5](config.md#5-user-credentials) |
| 4 | Create the env: `afon-cli.sh aws-create aws-polaris`             | [§6](config.md#6-create-the-env) |
| 5 | Create the DNS zone and delegate the domain to Route 53; issue the ACM cert | [§7](config.md#7-dns-zones), [§8](config.md#8-tls-certificate-in-acm) |
| 6 | Deploy: `afon-cli.sh manage-stack setup aws-polaris`             | below |
| 7 | Whitelist the TRE's IP at the Data Provider                      | below |

## Stack-specific config

`afon-aws.properties`:
```shell
AWS_POLARIS_DOMAIN_NAME=aws-polaris.<env>.click
AWS_POLARIS_ALLOWED_CIDRS=<optional: CIDRs admitted to Polaris, eg the TRE's IP>
AWS_EGRESS_IP=<optional: pinned Elastic IP>
```
`afon.properties`: `OIDC_DOMAIN_NAME` of the TRE's KeyCloak (or `AWS_OIDC_DOMAIN_NAME` in `afon-aws.properties`).  `OIDC_ISSUER_URL`
needs no change, as it follows the domain name.

If the Route 53 zone of `AWS_POLARIS_DOMAIN_NAME` is not in your account, the stack can't serve it: `afon-cli.sh dns-create-zones aws <stack>` makes it, after `aws-create`.

## Deploy and verify

```shell
afon-cli.sh manage-stack setup aws-polaris
```
Steps: `delete_stacks_conflicting_with_aws_polaris` → `check_keycloak_answers` → `deploy_aws_polaris_stack` →
`wait_on_polaris_and_keycloak_for_cloud_aws` → `create_all_catalogs_for_cloud_aws`.

```shell
afon-cli.sh aws-update-kube-config catalog
kubectl get pods                    # Polaris Running/Ready, ingress has an address
afon-cli.sh aws-polaris             # is Polaris serving?
```
`https://$AWS_POLARIS_DOMAIN_NAME` must load with a valid TLS certificate.

## Network whitelisting

To limit token exfiltration, restrict who reaches Polaris with `AWS_POLARIS_ALLOWED_CIDRS`, applied as the inbound CIDRs of its ALB (the cluster's egress IP is
added automatically), and whitelist the TRE's IP, see [#162](https://github.com/emrys-health/afon/issues/162) and
[restricting who can reach Polaris](../../cluster.md#restricting-who-can-reach-polaris-aws).

## Teardown

```shell
afon-cli.sh manage-stack delete aws-polaris
afon-cli.sh aws-destroy
```
