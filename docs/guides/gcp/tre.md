# GCP TRE: end-to-end setup of `gcp-full`

Summary of the procedure to deploy the `gcp-full` stack for a TRE: KeyCloak, Polaris, Spark, Trino, Jupyter and Gitea, with
monitoring, on GKE, from which the TRE queries the data providers' Polaris catalogs.  It mirrors the [data provider](data.md) doc;
the common config and CLI steps are in [config](config.md), the stack details in [stacks](../../stacks.md).

`gcp-full` has its own KeyCloak at `OIDC_DOMAIN_NAME`, which the data providers' `gcp-polaris` stacks use.

## Steps

| # | Step                                                              | Where |
|---|-------------------------------------------------------------------|-------|
| 1 | Install the CLI and local tools                                   | [config](config.md): [§2](config.md#2-install-the-afon-cli), [§3](config.md#3-install-the-local-tools) |
| 2 | Register three domains: OIDC, Polaris and Jupyter                 | below |
| 3 | Create and fill the config; switch env; login                     | [§4](config.md#4-create-and-fill-in-the-config), [§5](config.md#5-login) |
| 4 | Create the env: `afon-cli.sh gcp-create gcp-full`; set the HMAC key it shows | [§8](config.md#8-create-the-env) |
| 5 | Create the DNS zones and point the registrar at the Google nameservers; issue the TLS certs | [§6](config.md#6-dns-zones), [§7](config.md#7-tls-certificate-in-secret-manager) |
| 6 | Deploy: `afon-cli.sh manage-stack setup gcp-full`                 | below |
| 7 | Give the Data Providers the TRE's IP to whitelist                 | below |

## Stack-specific config

`afon-gcp.properties`:
```shell
GCP_OIDC_DOMAIN_NAME=                    # optional: OIDC_DOMAIN_NAME when empty
GCP_POLARIS_DOMAIN_NAME=${ENV_NAME}polarisgcp.click
GCP_JUPYTER_DOMAIN_NAME=${ENV_NAME}jupytergcp.click
GCP_GITEA_BUCKET=<required: GCS bucket for Gitea, made by gcp-create>
GCP_POLARIS_LOG_BUCKET=<optional>
GCP_GKE_NODE_POOL=machineType:e2-standard-4,nodeCount:2
GCP_EGRESS_IP=<optional: the static IP made by gcp-egress-ip create, which the data providers whitelist>
```
`afon.properties`:
```shell
OIDC_DOMAIN_NAME=<oidc domain>      # its DNS zone must be in the project too
```
`whitelist.yaml`: the `gcp` section of the template already lists the OIDC, Polaris and Jupyter domain names and `storage.googleapis.com`,
so keep it (the entries of the clouds you do not deploy to are left out as their properties are not set).
`secret.properties`: `KEYCLOAK_BOOTSTRAP_ADMIN_*`, `KEYCLOAK_DB_*` and `USER_ID`.
`secret-gcp.properties`: `GOOGLE_APPLICATION_CREDENTIALS` is required (Loki keeps the monitoring logs in the `gcp` catalog bucket with it), and so are the
HMAC key of Gitea and, if `GCP_POLARIS_LOG_BUCKET` is set, of the log shipper.

**Why these matter:**
- `gcp-full` is unlikely to fit the default nodes (`e2-medium`, one per zone): pods such as the Spark Connect driver would stay Pending (`Insufficient cpu`),
  so use larger nodes.  The estimator only knows Azure sizes, so for an approximation give what a node has to give to pods, eg
  `ALLOC_CPU=3600 ALLOC_MEM=12000 afon-cli.sh kube-estimator gcp-full <nodes> <machine type>` (its system pods are those measured on AKS, not GKE).
- The network policies limit what notebooks and Spark can connect to `whitelist.yaml`; missing names give
  `UnknownHostException: <polaris domain>: Temporary failure in name resolution` or a connection that hangs.  See [network policies](#network-policies).
- Gitea's repositories are on a Persistent Disk that `gcp-create` made, so the stack fails to plan without it, and deleting the stack keeps it.

To use the KeyCloak of a separate `gcp-keycloak` stack instead, set `GCP_KEYCLOAK_EXTERNAL=true`: then only the Polaris and Jupyter zones are needed
and `gcp-create gcp-keycloak` makes the env of the OIDC one.

## Deploy and verify

```shell
afon-cli.sh validate-stack gcp-full
afon-cli.sh manage-stack setup gcp-full
```
```shell
afon-cli.sh gcp-cluster tre                # is the GKE cluster up?
afon-cli.sh gcp-update-kube-config tre
kubectl get pods
afon-cli.sh gcp-keycloak          # is KeyCloak serving?
afon-cli.sh gcp-polaris           # is Polaris serving?
```
`https://$OIDC_DOMAIN_NAME`, `https://$GCP_POLARIS_DOMAIN_NAME` and `https://$GCP_JUPYTER_DOMAIN_NAME` must load with valid TLS certificates.

## Data Providers whitelisting

The TRE's egress IP is what the Data Providers add to their whitelist (`GCP_POLARIS_ALLOWED_CIDRS`).  Pin it with `GCP_EGRESS_IP` so that it survives a rebuild.

## Teardown

```shell
afon-cli.sh manage-stack delete gcp-full
afon-cli.sh gcp-destroy         # also deletes Gitea's disk and bucket, and the HMAC key
```

## Network policies

GKE has no `CiliumNetworkPolicy`, which the stacks of AWS and Azure use, so on GCP the egress of the notebooks and of Spark is limited with what GKE offers.
The cluster has Dataplane V2 and FQDN network policies (`enable_fqdn_network_policy`, set by the stack), and:

- an ordinary `NetworkPolicy` for what is in the cluster: DNS, the JupyterHub hub, Gitea and the Spark master for the notebooks, and DNS, the Spark
  master and, for the driver only, the ingress controller on 443 for Spark;
  DNS is allowed to both `kube-dns` and `node-local-dns`: GKE answers lookups with the NodeLocal DNSCache pod of the node, which a policy
  allowing only `kube-dns` drops, so that notebooks could not even resolve the hub (`Temporary failure in name resolution`);
- an `FQDNNetworkPolicy` for each domain of `whitelist.yaml` for the Spark master and driver (`kubectl get fqdnnetworkpolicy`, label `afon.emrys.health/network-policy`),
  one for each as GKE resolves at most 50 addresses for a policy.

What GCP does not give, compared with AWS and Azure:

- **DNS lookups are not filtered.**  A name that is not whitelisted cannot be connected to, but it can be looked up, so data can still leave in the labels of a query
  to a nameserver of the attacker.  Cilium's L7 DNS rules close this on AWS and Azure.
- Enforcement is by the addresses a name resolves to: a name that shares an address with a whitelisted one, as with a CDN or `storage.googleapis.com`
  (which other Google APIs share), is reachable too.
- `*` stands for a label, so `*.example.com` covers `a.example.com` but not `a.b.example.com`.  A name that is a CNAME is allowed
  through the names the pod looks up, so whitelist each.
- Only kube-dns or Cloud DNS is supported, not a custom CoreDNS, and the results of a nameserver other than the cluster's are not enforced.

On a cluster created before this was enabled, apply the stack, then restart the dataplane to enforce it: `kubectl rollout restart ds -n kube-system anetd`.
