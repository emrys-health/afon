# DNS Configuration

## Context

The apps exposing a web interface need all communication encrypted. 

To encrypt http traffic, we must use https, which in turn means a domain name that is recognised by a Certificate Authority.

We use [Let's Encrypt](https://letsencrypt.org) to create certificates, although other cloud-specific services are also available. 

You need domain names that point to each cloud's Kubernetes ingress.
For Kubernetes to point a domain name at a particular pod, you need to prove you own it.

## Summary

1. Register a domain name (e.g. in AWS Route 53): one per env is enough, as the domain names of the clouds can be subdomains of it
2. Set the `*_DOMAIN_NAME` properties of each cloud
3. Create the DNS zone of each domain name in its cloud: `afon-cli.sh dns-create-zones <cloud> <stack>`
4. Delegate each domain name to its zone: for a subdomain, `afon-cli.sh dns-delegate <cloud> <stack>`; for a registered domain, set the zone's nameservers at the registrar, or, if it is registered with Route 53, with `afon-cli.sh dns-delegate <cloud> <stack> --route53`, which replaces its registered nameservers
5. Check the delegation: `afon-cli.sh <cloud>-dns-zones`
6. SSL certificates (e.g. via AWS Certificate Manager or Let's Encrypt)

## Domain registrar

You can use any registrar for the domain name registration. In the following, we assume you're using [AWS Route 53](https://us-east-1.console.aws.amazon.com/route53/domains/home).   
If you've registered your domain name through other registrars, the same concepts apply, 
although the scripts may not work end-to-end.

## Domain names and subdomains

Each configured domain name (`<CLOUD>_POLARIS_DOMAIN_NAME`, `<CLOUD>_JUPYTER_DOMAIN_NAME` and the OIDC one, `<CLOUD>_OIDC_DOMAIN_NAME` else
`OIDC_DOMAIN_NAME`) gets its own DNS zone in the cloud that serves it, **named after the domain name**.  The domain name need not be a 
registered domain: it can be a subdomain of one, so that a single registered domain per env is enough.  

With the registered domains `<env>.click`, one per env, and the `<cloud>-<tool>.<domain>` convention, which follows the stack ids:

| Property                    | Env                         |
|-----------------------------|-----------------------------|
| `AWS_OIDC_DOMAIN_NAME`      | `aws-oidc.<env>.click`      | 
| `AWS_POLARIS_DOMAIN_NAME`   | `aws-polaris.<env>.click`   | 
| `AWS_JUPYTER_DOMAIN_NAME`   | `aws-jupyter.<env>.click`   | 
| `AZURE_OIDC_DOMAIN_NAME`    | `azure-oidc.<env>.click`    | 
| `AZURE_POLARIS_DOMAIN_NAME` | `azure-polaris.<env>.click` | 
| `AZURE_JUPYTER_DOMAIN_NAME` | `azure-jupyter.<env>.click` | 
| `GCP_OIDC_DOMAIN_NAME`      | `gcp-oidc.<env>.click`      | 
| `GCP_POLARIS_DOMAIN_NAME`   | `gcp-polaris.<env>.click`   | 
| `GCP_JUPYTER_DOMAIN_NAME`   | `gcp-jupyter.<env>.click`   | 

For a **subdomain** (e.g. `aws-polaris.<env>.click`) there is nothing to change on the registrar: instead, in the DNS zone of the parent
(`<env>.click`), wherever it is hosted, add an `NS` record named after the subdomain with the nameservers of its zone as values.  In Route 53 that is `Create record` in the hosted zone of the parent, record name `aws-polaris`, type `NS`, one nameserver per line.

### DNS zones and delegation

For each stack - specified by its id or its role (`identity`, `catalog` or `tre`) - we have to create one or more DNS zones and delegate them:
```shell
afon-cli.sh dns-create-zones <aws|azure|gcp> <stack>                # creates the DNS zone of each domain name, unless it exists
afon-cli.sh dns-delegate <aws|azure|gcp> <stack> [--route53]        # shows, or with --route53 writes, the NS records for the parent zone
```
- `dns-create-zones` creates a Route 53 hosted zone on `aws`, an Azure DNS zone in `AZURE_RESOURCE_GROUP` on `azure`, and a Cloud DNS managed
  zone on `gcp`.   The command is idempotent: a zone that exists is left as it is, so it is safe to repeat.  It shows the nameservers of each zone.
- `dns-delegate` shows the `NS` records that delegate each domain name from its parent domain to the zone of the cloud to add to the zone that hosts the parent domain, wherever that is registered.  It is safe to repeat.
`--route53` is for a parent domain whose DNS zone is a public Route 53 hosted zone in the current AWS account, **and is the authoritative one**, ie the registrar's nameservers are those of that hosted zone.  It writes the records itself, replacing  those of the domain name already there: for each domain name it finds the closest parent domain that is such a hosted zone (`<env>.click` for `azure-polaris.<env>.click`) and updates the `NS` record there.  It needs the `aws` CLI and credentials even when the zones are in Azure or GCP. A domain name with no such parent, eg a registered domain used as it is, is delegated by setting its **registered nameservers**, if it is registered with Route 53 in the same AWS account (it needs the `route53domains:GetDomainDetail` and `route53domains:UpdateDomainNameservers` permissions).  This **replaces** the nameservers the domain has now, so its DNS is then served by the zone of the cloud and anything else in its current zone stops resolving: the script shows the current and the new nameservers before it does it, and changes nothing if they are the same already.  Registrars other than Route 53 are not supported: delegate at the registrar instead.

**NOTE:**  On `azure`, run `azure-create` first, as it creates the resource group.  `azure-create` and `gcp-create` do not create DNS zones.

What differs between the two forms of domain name is only **where the delegation is set**, as a zone is only reachable once its parent delegates to it:

- a registered domain (`emryspolaris.click`): the registrar's nameservers are set to those of the zone, by `dns-delegate --route53` if it is registered with Route 53, or by hand.
- a subdomain (`aws-polaris.<env>.click`): an `NS` record named `aws-polaris` is added, with the nameservers of the zone, to the DNS zone
  that hosts the parent domain (`<env>.click`), by `dns-delegate`, or by hand.

Nothing else depends on the registrar or on Route 53: the zone names, the certificates and the A records are the same whichever the parent is,
and `<cloud>-dns-zones` checks the delegation of a subdomain just as it does that of a registered domain.

**Note**:  a hosted zone is a separate billable item in each cloud (e.g. Route 53 charges per zone per month), while registering a domain is a
yearly charge.  A wildcard `A` record in the parent is **not** a substitute for the delegation: the
zone of the domain name must be the authoritative one for the ACM and Let's Encrypt validations to work.

## Instructions

The sections below detail, for each cloud, the creation of the DNS zone and of its certificate.  Each has the same three parts:

- **Zone creation:** the DNS zone of each domain name of a stack, in the cloud;
- **Zone delegation:** the delegation of each domain name to its zone, ie the nameservers it is served by. 
- **Certificate:** how the TLS certificate is obtained and where it is stored. 

**Note**: If AWS Route 53 is not used, ie the domain is registered elsewhere (e.g. GoDaddy) or the parent domain of a subdomain is hosted by another DNS provider, the `NS` records have to be updated manually, in the chosen domain registrar or in the DNS zone of the parent domain: `dns-delegate --route53` only writes them when Route 53 is the authoritative DNS of the parent, or, for a registered domain, its registrar.

### AWS

**Zone creation:** create a Route 53 hosted zone **for each domain name** in the given stack (`tre`, `catalog`, `identity`):

```shell
afon-cli.sh dns-create-zones aws <stack>
```

**Zone delegation:** show the `NS` records, or the nameservers, to set in the zone of the parent domain or at the registrar, or, if that is in Route 53 of the current AWS account, set them:

```shell
afon-cli.sh dns-delegate aws <stack>             # shows them
afon-cli.sh dns-delegate aws <stack> --route53   # sets them in Route 53
```

**Certificate:** issue a free ACM certificate, which renews itself and is validated by a record in the hosted zone.  
```shell
afon-cli.sh aws-issue-cert
```
Alternatively, in the [AWS Certificate Manager](https://eu-west-2.console.aws.amazon.com/acm/certificates/list?region=eu-west-2) console `Request` a certificate for the domain, and press the `Create Record in Route 53` button to prove you own it.  Note that it might take a few minutes to propagate. A domain that already has an issued certificate is skipped, so it is safe to repeat; add `--force` to request a new one.

When a stack is deployed, it looks up the hosted zone named after each domain name, and the latest `ISSUED` ACM certificate for exactly that domain name (a wildcard certificate is not matched). The deploy fails when planning, before anything is created, if either is missing.

**NOTE**: unlike Azure and GCP, AWS does not store the certificate in a secrets manager.  The hosted zones must be in Route 53, whichever registrar the domains are registered with.

### Azure

**Zone creation:** create an Azure DNS zone **for each domain name** in the given stack (`tre`, `catalog`, `identity`):

```shell
afon-cli.sh azure-create   # creates resource group
afon-cli.sh dns-create-zones azure <stack>
```

**Zone delegation:** show the `NS` records, or the nameservers, to set in the zone of the parent domain or at the registrar, or, if that is in Route 53 of the current AWS account, set them:

```shell
afon-cli.sh dns-delegate azure <stack>             # shows them
afon-cli.sh dns-delegate azure <stack> --route53   # sets them in Route 53
```

**Certificate:** issued by Let's Encrypt, or by Azure's own CA, and kept in Azure Key Vault.

In `config/afon-azure.properties` add property `AZURE_KEY_VAULT_NAME=<env>-afon-key-vault`.  
If property `AZURE_KEY_VAULT_NAME` is not found or blank, the storing of the cert is disabled.

**IMPORTANT**:  the Azure key vault names must be unique **globally**.

To create key vault, run:
```shell
afon-cli.sh azure-keyvault create
```
It will pick up the name of the key vault and all the other relevant properties from config and create the key vault.
The operation is idempotent and will show an existing key vault if already present.

To issue a cert via ACME (Let's Encrypt) and store it in the key vault:
```shell
afon-cli.sh azure-issue-cert acme
```
This is a one-off operation but can be repeated (idempotently) to renew the cert.

To see, without issuing anything, whether the certs are cached:
```shell
afon-cli.sh azure-issue-cert --check
```
It succeeds only if the cert of each configured domain name is in the key vault.  Without them each deploy asks Let's Encrypt for new certs, which
allows only 5 certs a week of the same names: a deploy that is refused waits 20 minutes for the cert and fails (`PKIX path building failed`).  The
smoke tests run it before they set up a stack, and stop if a cert is missing.  `gcp-issue-cert --check` does the same for Secret Manager (if `GCP_SECRETS_ENABLED=true`),
and `aws-issue-cert --check` for the ISSUED certs in ACM.

Optionally, Azure has its own CA which supports two issuers:
```shell
afon-cli.sh azure-issue-cert {digicert|globalsign}
```
This is not a free service and the issuer needs to be registered.  
Set as properties `AZURE_CA_ID/AZURE_CA_PASSWORD` in `config/secret-azure.properties` the issuer credentials.

**NOTE**:  one advantage of using Azure CA is that Key Vault handles renewal on its own schedule going forward.

### GCP

**Zone creation:** create a GCP DNS zone **for each domain name** in the given stack (`tre`, `catalog`, `identity`):

```shell
afon-cli.sh dns-create-zones gcp <stack>
```

**Zone delegation:** show the `NS` records, or the nameservers, to set in the zone of the parent domain or at the registrar, or, if that is in Route 53 of the current AWS account, set them:

```shell
afon-cli.sh dns-delegate gcp <stack>             # shows them
afon-cli.sh dns-delegate gcp <stack> --route53   # sets them in Route 53
```

**Certificate:** issued by Let's Encrypt and kept in Google Secret Manager, scoped to the project `GCP_PROJECT_ID`.

Unlike Azure, there is no vault to name or create: Secret Manager has no vault object, and its secrets live directly
in the project.  The two secrets are named after the domain name, `<domain-with-dashes>-tls-crt` and
`<domain-with-dashes>-tls-key`, and are created on first store.  So where Azure is configured with a vault name
(`AZURE_KEY_VAULT_NAME`), GCP is configured with a plain on/off switch.

In `config/afon-gcp.properties` set `GCP_SECRETS_ENABLED=true` (the template default).
If not `true`, the restoring of the cached cert on deploy is disabled.

To issue a cert via ACME (Let's Encrypt) and store it in Secret Manager:
```shell
afon-cli.sh gcp-issue-cert acme
```
The active gcloud account needs the DNS Administrator role (to answer the DNS-01 challenge) and
Secret Manager Admin.  This is a one-off operation but can be repeated (idempotently) to renew the cert.

To store an existing PEM cert and key, and to inspect or remove the secrets:
```shell
afon-cli.sh gcp-secrets store <cert.pem> <key.pem> [<domain-name>]
afon-cli.sh gcp-secrets {show|list|delete}
```
On deploy, the `GcpCloudProvider` restores the secrets into the `polaris-tls` Kubernetes secret before cert-manager
asks Let's Encrypt for a new one; if the secrets are missing it simply falls back to issuing a new cert.

## Checking the name servers

To show the name servers of the DNS zone of each domain name of a cloud, ie its Polaris and Jupyter domain names and
its OIDC domain name (`<CLOUD>_OIDC_DOMAIN_NAME`, else `OIDC_DOMAIN_NAME`), and whether the domain is delegated to them, 
as a public resolver sees it, run:
```shell
afon-cli.sh <cloud>-dns-zones
```
A domain that is not delegated to the zone of the cloud being deployed makes the deploy wait for its DNS record forever, eg when
`OIDC_DOMAIN_NAME` is still delegated to another cloud's zone. The resolver can be changed with the env var `DNS_RESOLVER`
(default `8.8.8.8`); it may still give the old name servers for a while after the delegation has changed.

## Check DNS propagation

```shell
dig A <domain> +short
```

Once resolved, Kubernetes running in the cloud that owns the DNS servers can ask them to map a domain name to one of its pods.

### The TRE whitelist

The notebooks, the Spark master and the Spark driver of a TRE can only resolve the domain names in `whitelist.yaml` of the config dir (see
[configuration](configuration.md)), so **a new domain name must be in it too**, or they cannot reach it.  The symptom is a Spark or notebook pod
failing with `UnknownHostException: <domain>: Temporary failure in name resolution`, while the domain resolves from anywhere else, eg from the
Polaris and KeyCloak pods, which are not restricted.

The command `config-updates` generates the `whitelist.yaml` from the deprecated `ALLOWED_DOMAINS_CSV` lists the domain names using
the config properties (but never overwrites an existing `whitelist.yaml`).  The best strategy is to start from the
[template](../templates/config/whitelist.yaml) instead, whose entries use the config properties, eg `${AZURE_POLARIS_DOMAIN_NAME}`, and to add the other
domains that your deployment needs, eg those of a bucket or of another service, to the section of the cloud or to `common`.

The policies are part of the stack, so redeploy it for a changed whitelist to take effect.
