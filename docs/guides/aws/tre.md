# AWS TRE: end-to-end setup of `aws-full`

Summary of the procedure to deploy the `aws-full` stack for a TRE: KeyCloak, Polaris, Spark, Trino, Jupyter and Gitea, with
monitoring and logging, on EKS, from which the TRE queries the data providers' Polaris catalogs.  It mirrors the [data provider](data.md) doc;
the common config and CLI steps are in [config](config.md), the stack details in [stacks](../../stacks.md).

`aws-full` has its own KeyCloak at `OIDC_DOMAIN_NAME`, which the data providers' `aws-polaris` stacks use.

## Steps

| # | Step                                                              | Where |
|---|-------------------------------------------------------------------|-------|
| 1 | Install the CLI and local tools                                   | [config](config.md): [§2](config.md#2-install-the-afon-cli), [§3](config.md#3-install-the-local-tools) |
| 2 | Register three domains: OIDC, Polaris and Jupyter (or subdomains of one) | below |
| 3 | Create and fill the config; switch env; user credentials                  | [§4](config.md#4-create-and-fill-in-the-config), [§5](config.md#5-user-credentials) |
| 4 | Create the env: `afon-cli.sh aws-create aws-full`                 | [§6](config.md#6-create-the-env) |
| 5 | Create the DNS zones and delegate the domains to Route 53; issue the ACM certs | [§7](config.md#7-dns-zones), [§8](config.md#8-tls-certificate-in-acm) |
| 6 | Deploy: `afon-cli.sh manage-stack setup aws-full`                 | below |
| 7 | Give the Data Providers the TRE's IP to whitelist                 | below |

## Stack-specific config

`afon-aws.properties`:
```shell
AWS_OIDC_DOMAIN_NAME=                    # optional: OIDC_DOMAIN_NAME when empty
AWS_POLARIS_DOMAIN_NAME=aws-polaris.<env>.click
AWS_JUPYTER_DOMAIN_NAME=aws-jupyter.<env>.click
AWS_GITEA_BUCKET=<required: S3 bucket for Gitea, made by aws-create>
AWS_POLARIS_LOG_BUCKET=<optional: S3 bucket Polaris' logs are shipped to>
AWS_EGRESS_IP=<optional: the Elastic IP made by aws-egress-ip create, which the data providers whitelist>
```
`afon.properties`:
```shell
OIDC_DOMAIN_NAME=<oidc domain>      # its Route 53 zone must be in the account too
```
`whitelist.yaml`: the `aws` section of the template already lists the OIDC, Polaris and Jupyter domain names and the S3 endpoint of each catalog bucket,
so keep it (the entries of the clouds you do not deploy to are left out as their properties are not set).
`secret.properties`: `KEYCLOAK_BOOTSTRAP_ADMIN_*`, `KEYCLOAK_DB_*` and `USER_ID`.
`secret-aws.properties`: `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` are required (Gitea and the log shipper reach their buckets with them).

**Why these matter:**
- The network policies limit what notebooks and Spark can connect to `whitelist.yaml`; missing names give
  `UnknownHostException: <polaris domain>: Temporary failure in name resolution` or a connection that hangs.  See [network policies](#network-policies).
- Gitea's repositories are on an EBS volume, created when the stack is deployed and kept when it is deleted, so that a new cluster finds them again;
  snapshots of it are taken while the cluster runs.  See [volumes that outlive the cluster](../../cluster.md#volumes-that-outlive-the-cluster).
- Everything the cluster sends leaves from one public IP.  Pin it with `AWS_EGRESS_IP` so that it survives a rebuild (see [egress IP](../../cluster.md#egress-ip-aws)).

To use the KeyCloak of a separate `aws-keycloak` stack instead, set `AWS_KEYCLOAK_EXTERNAL=true`: then only the Polaris and Jupyter zones are needed
and `aws-create aws-keycloak` makes the env of the OIDC one.

## Deploy and verify

```shell
afon-cli.sh validate-stack aws-full
afon-cli.sh manage-stack setup aws-full
```
```shell
afon-cli.sh aws-cluster tre                # is the EKS cluster up?
afon-cli.sh aws-update-kube-config tre
kubectl get pods
afon-cli.sh aws-keycloak          # is KeyCloak serving?
afon-cli.sh aws-polaris           # is Polaris serving?
```
`https://$OIDC_DOMAIN_NAME`, `https://$AWS_POLARIS_DOMAIN_NAME` and `https://$AWS_JUPYTER_DOMAIN_NAME` must load with valid TLS certificates.

## Data Providers whitelisting

The TRE's egress IP is what the Data Providers add to their whitelist (`AWS_POLARIS_ALLOWED_CIDRS`).  Pin it with `AWS_EGRESS_IP`, allocated with
`afon-cli.sh aws-egress-ip create`, so that it survives a rebuild.

## Teardown

```shell
afon-cli.sh manage-stack delete aws-full
afon-cli.sh aws-destroy
```
Gitea's EBS volume is kept; to delete it for good, `aws ec2 delete-volume --volume-id <id>` (see [cluster](../../cluster.md)).

## Network policies

Unlike GKE, EKS runs Cilium, so the egress of the notebooks and of Spark is limited with `CiliumNetworkPolicy`s generated from `whitelist.yaml`,
including L7 DNS rules: a name that is not whitelisted can be neither connected to nor looked up, which closes exfiltration through the labels of a DNS query.
