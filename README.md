# Afon Documentation

Afon is an infrastructure project that deploys [Apache Polaris](https://polaris.apache.org/) across cloud providers (AWS, Azure and GCP) to enable secure cross-cloud querying of Iceberg tables via Spark and Snowflake.

---
## Quickstart — How to set up

### Env setup

See [setup](./docs/setup.md).

### Build from source

See [build](./docs/build.md)

### Configure properties and users

See [configuration.md](./docs/configuration.md).

### Cloud infrastructure

See [cloud.md](./docs/cloud.md).

### DNS Configuration

See [dns_config.md](./docs/dns_config.md).

### Deploy the stacks

See [stacks.md](./docs/stacks.md).

### Deploy the catalogs

See [catalogs.md](./docs/catalogs.md).

### Access Polaris and Jupyter

Once deployed, log in via your domain name:

```
https://emryspolaris.click
```

You can also access Jupyter at:
```
https://emrysjupyter.click/guacamole
```

---

## Explanations — How it works

## Architecture overview

See [architecture](./architecture.md).

## Examples

See [examples](./examples.md).

---
### Production deployment checklist

- [ ] **Domain names purchased** — one per cloud (e.g. `polaris-aws.yourdomain.com`, `polaris-azure.yourdomain.com`, `polaris-gcp.yourdomain.com`)
- [ ] **DNS zones created** in each target cloud and nameservers delegated at your registrar
- [ ] **SSL certificates** requested and validated (ACM for AWS, Let's Encrypt for others)
- [ ] **External Polaris database** provisioned for Polaris metadata storage
- [ ] **OAuth2 provider** configured (Google or other OIDC provider) for Jupyter access
- [ ] **Cloud IAM roles** created via `scripts/{aws,azure,gcp}/create.sh`
- [ ] **Storage buckets** created (primary, external, and forbidden for each cloud)
- [ ] **Configuration** filled in with all non-sensitive values
- [ ] **Kubernetes cluster** creation permissions confirmed
- [ ] **`KUBE_CONFIG_PATH`** environment variable set to your kubeconfig path
- [ ] **`unalias aws`** if using a Dockerized AWS CLI

### Production deployment commands

```shell
# Full AWS stack (Polaris + Spark + Jupyter)
./afon.sh -a exec --exec-class ops.DeployFullStackAwsMain 

# Azure Polaris (standalone)
./afon.sh -a exec --exec-class ops.DeployAzurePolarisMain

# GCP Polaris (standalone)
./afon.sh -a exec --exec-class ops.DeployGcpPolarisMain 

# Cross-cloud external catalogs (AWS + Azure federation)
./afon.sh -a exec --exec-class ops.DeployExternalCatalogsMain 

# Snowflake reading cross-cloud data
./afon.sh -a exec --exec-class ops.DeploySnowflakeReadingGcpViaAwsMain 
./afon.sh -a exec --exec-class ops.DeploySnowflakeReadingAzureViaAwsMain 

# Snowflake writing to Azure
./afon.sh -a exec --exec-class ops.DeploySnowflakeWritingAzureViaAwsMain 
```



