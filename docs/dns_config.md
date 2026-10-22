# DNS Configuration

## Context

Polaris needs all communication encrypted. 

To encrypt http traffic, we must use https, which in turn means a domain name that is recognised by a Certificate Authority.

We use [Let's Encrypt](https://letsencrypt.org) to create certificates.

You need domain names that point to each cloud's Kubernetes ingress.
For Kubernetes to point a domain name at a particular pod, you need to prove you own it.

## Summary

1. Buy domain names (e.g. from AWS Route 53)
2. Create DNS zones in each target cloud
3. Update the domain registrar's nameservers to point at the cloud's nameservers
4. SSL certificates (e.g. via AWS Certificate Manager or Let's Encrypt)

## Instructions

We assume that you've bought your domain name in AWS and want other cloud providers to manage it.
If you've bought your domain name through other domain registrars (e.g. GoDaddy) the process is similar.

### AWS

For AWS, create a Route 53 hosted zone **named after each domain name** (`OIDC_DOMAIN_NAME`, `AWS_POLARIS_DOMAIN_NAME` and
`AWS_JUPYTER_DOMAIN_NAME`), and have the registrar, wherever the domain was bought, delegate to its nameservers.
You can check that the zone is there by running:
```shell
aws route53 list-hosted-zones-by-name --dns-name $AWS_POLARIS_DOMAIN_NAME --max-items 1
```

This is sufficient for DNS but if you want to use https, you'll need to assign a certificate.
To request one for each domain name in ACM, validated by a record in its hosted zone, and wait until it is issued, run:
```shell
afon-cli.sh aws-issue-cert
```
(or in the [AWS Certificate Manager](https://eu-west-2.console.aws.amazon.com/acm/certificates/list?region=eu-west-2) console `Request` a certificate for the
domain, and press the `Create Record in Route 53` button to prove you own it.  Note that it might take a few minutes to propagate.)

`aws-issue-cert`: a domain that already has an issued certificate is skipped, so it is safe to repeat; add `--force` to request a new one.

There are no properties for the hosted zones or the certificates: when a stack is deployed, it looks up
the hosted zone named after each domain name, and the latest `ISSUED` ACM certificate for exactly that domain name (a wildcard certificate is not matched).
The deploy fails when planning, before anything is created, if either is missing.

**NOTE**: unlike Azure and GCP, AWS does not store the certificate in a secrets manager.  There, cert-manager terminates TLS in the cluster
and the cached certificate and key are restored into a Kubernetes secret, to avoid the weekly limit of Let's Encrypt.  On AWS the ALB terminates TLS with the
ACM certificate, which is free, renews itself and is not secret, being only referred to by its ARN.  Looking up the
ARN and the zone ID from AWS, rather than keeping copies of them in the config, means they cannot drift from what is in ACM and Route 53,
nor be copied wrongly from one env to another.  The hosted zones must be in Route 53, whichever registrar the domains are registered with.

### Azure

Create a DNS zone for the combination of domain name and resource group: 
```shell
az network dns zone create --name $AZURE_POLARIS_DOMAIN_NAME --resource-group $AZURE_RESOURCE_GROUP  
```
This will show a list of nameservers, for example:
```json
{
  "nameServers": [
    "ns1-02.azure-dns.com.",
    "ns2-02.azure-dns.net.",
    "ns3-02.azure-dns.org.",
    "ns4-02.azure-dns.info."
  ]
}
```
Make a note of these.

If a Kubernetes service is gone but DNS record remains, run the Azure DNS cleanup command:
```shell
az network dns record-set a delete \
  --resource-group $AZURE_RESOURCE_GROUP \
  --zone-name $AZURE_POLARIS_DOMAIN_NAME \
  --name "@" \
  --yes
```

### GCP

The DNS zone is created by `scripts/gcp/create.sh`, named after the domain name with its dots replaced by dashes,
eg `emryspolarisgcp2-click` for `emryspolarisgcp2.click`.  Note you might need admin rights to do this.

Once it's created, you can run:
```shell
$ gcloud dns managed-zones describe ${GCP_POLARIS_DOMAIN_NAME//./-} --project=$GCP_PROJECT_ID
```
Just like for Azure, this will show a list of name servers:
```
nameServers:
- ns-cloud-c1.googledomains.com.
- ns-cloud-c2.googledomains.com.
- ns-cloud-c3.googledomains.com.
- ns-cloud-c4.googledomains.com.
```
Again, make a note.

## Checking the name servers

To show the name servers of the DNS zone of each domain name of a cloud, ie its Polaris and Jupyter domain names and
`OIDC_DOMAIN_NAME`, and whether the registrar delegates the domain to them, as a public resolver sees it, run:
```shell
afon-cli.sh aws-dns-zones
afon-cli.sh azure-dns-zones
afon-cli.sh gcp-dns-zones
```
A domain that is not delegated to the zone of the cloud being deployed makes the deploy wait for its DNS record forever, eg when
`OIDC_DOMAIN_NAME` is still delegated to another cloud's zone. The resolver can be changed with the env var `DNS_RESOLVER`
(default `8.8.8.8`); it may still give the old name servers for a while after the registrar has changed them.

## Domain name registration

Go to the DNS section of your domain registrar.

For AWS, you can use [AWS Route 53](https://us-east-1.console.aws.amazon.com/route53/domains/home).
Click the domain you've bought and in the top right corner, you'll see a section called `Name servers`.
Click the `Actions` button and choose `Edit name servers`.

![image](images/aws_nameservers.png)

Now add the list of nameservers your cloud provider gave you. 
They should propagate within about 10 minutes.

## Check DNS propagation

```shell
dig A <domain> +short
```

## Consequences

Kubernetes running in the cloud that owns the DNS servers can now ask them to map a domain name to one of its pods.

## TLS cert issue and store

Let's Encrypt limits the number of certificates per week.   

The cert should then be issued and stored in a secrets manager - which is necessarily cloud-specific.

### Azure 

For Azure, the secrets manager is Azure Key Vault.

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

Optionally, Azure has its own CA which supports two issuers:
```shell
afon-cli.sh azure-issue-cert {digicert|globalsign}
```
This is not a free service and the issuer needs to be registered.  
Set as properties `AZURE_CA_ID/AZURE_CA_PASSWORD` in `config/secret-azure.properties` the issuer credentials.

**NOTE**:  one advantage of using Azure CA is that Key Vault handles renewal on its own schedule going forward.

### GCP

For GCP, the secrets manager is Google Secret Manager, scoped to the project `GCP_PROJECT_ID`.

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
