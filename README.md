# Afon Documentation

Afon is an infrastructure project that deploys [Apache Polaris](https://polaris.apache.org/) across cloud providers (AWS, Azure and GCP) to enable secure cross-cloud querying of Iceberg tables via Spark and Snowflake.

---
## How to set up

### Env setup

See [setup](./docs/setup.md).

### Build from source

See [build](./docs/build.md)

### Configure properties and users

See [configuration](./docs/configuration.md).

### Cloud infrastructure

See [cloud](./docs/cloud.md).

### DNS Configuration

See [dns_config](./docs/dns_config.md).

### Deploy the stacks

See [stacks](./docs/stacks.md).

### Deploy the catalogs

See [catalogs](./docs/catalogs.md).

### Access Polaris and Jupyter

For development purposes, we've registered domains `emryspolaris.click` and `emrysjupyter.click`.

Once stack is deployed, we can access Polaris and Jupyter via the URLs:
- `https://emryspolaris.click`
- `https://emrysjupyter.click/guacamole`

---
## How it works

## Architecture overview

See [architecture](./docs/architecture.md).

## Examples

See [examples](./docs/examples.md).

---
### Deployment checklist

- [ ] **Domain names** purchased via your domain registrar, one per cloud (e.g. `polaris-<cloud>.yourdomain.com`)
- [ ] **DNS zones** created in each target cloud and nameservers configured at your domain registrar
- [ ] **SSL certificates** requested and validated (ACM for AWS, Let's Encrypt for others)
- [ ] **External Polaris database** provisioned for Polaris metadata storage
- [ ] **OAuth2 provider** configured (Google or other OIDC provider) for Jupyter access
- [ ] **Cloud IAM roles** created via `scripts/<cloud>/create.sh`
- [ ] **Storage buckets** created (primary, external, and forbidden for each cloud)
- [ ] **Configuration** properties and users values populated in `config` dir
- [ ] **Kubernetes cluster** creation permissions confirmed
- [ ] **`KUBE_CONFIG_PATH`** environment variable set to your kubeconfig path
- [ ] **`unalias aws`** if using a Dockerized AWS CLI



