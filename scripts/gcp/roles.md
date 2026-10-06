# GCP roles

The IAM roles required on the GCP project configured as `GCP_PROJECT_ID` in `afon-gcp.properties`.

There are two kinds of user: the one that **deploys the stacks**, and the one that **sets up the account**, ie runs
the scripts in this dir.  They can be the same person, but need different roles.

## Stack deployer

The identity running `afon-cli.sh manage-stack ... gcp-polaris`, ie the active `gcloud` account, on the project
`GCP_PROJECT_ID`.  It must hold:

| Role                                      | Needed for                                                                                                                                                                                                  |
|-------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `roles/compute.networkAdmin`              | The VPC, subnet, Cloud Router and Cloud NAT                                                                                                                                                                 |
| `roles/container.admin`                   | Creating, updating and deleting the GKE cluster, and the Kubernetes objects Helm and Kubernetes create in it: namespaces, Jobs, Secrets, Deployments, Services and Ingress, including cert-manager and nginx-ingress |
| `roles/iam.serviceAccountUser`            | Acting as the cluster nodes' service account, by default the project's Compute Engine one, `<project-number>-compute@developer.gserviceaccount.com`, on the creation of the cluster                          |
| `roles/dns.admin`                         | Reading the Polaris DNS zone, and creating the Polaris DNS record set.  `roles/dns.reader` is not enough                                                                                         |
| `roles/serviceusage.serviceUsageConsumer` | Using the project's APIs                                                                                                                                                                                    |
| `roles/secretmanager.secretAccessor`      | Only if `GCP_SECRETS_ENABLED=true`: reading the cached TLS cert and key from Secret Manager, to restore them into the `polaris-tls` Kubernetes secret                                                      |

`roles/iam.serviceAccountUser` is granted on the service account, not the project, for a narrower scope:

```shell
gcloud iam service-accounts add-iam-policy-binding <project-number>-compute@developer.gserviceaccount.com \
  --member="user:<account>" --role="roles/iam.serviceAccountUser" --project=$GCP_PROJECT_ID
```

The other roles are granted on the project:

```shell
gcloud projects add-iam-policy-binding $GCP_PROJECT_ID --member="user:<account>" --role="roles/container.admin"
```

The `roles/editor` role covers all of these but `roles/container.admin` and `roles/iam.serviceAccountUser`, but is
broader than needed: use it for a development project only.

### Symptoms of a missing role

| Error                                                                                         | Missing                                      |
|-----------------------------------------------------------------------------------------------|----------------------------------------------|
| `The user does not have access to service account "...-compute@developer.gserviceaccount.com"` | `roles/iam.serviceAccountUser`               |
| `namespaces is forbidden ... requires one of ["container.namespaces.create"]`                 | `roles/container.admin`                      |
| `Error 403: Forbidden` reading `data.google_dns_managed_zone`                                 | `roles/dns.admin`, or no zone in the project |
| `PERMISSION_DENIED ... serviceusage.services.enable` from `secrets.sh store`                  | `roles/serviceusage.serviceUsageAdmin`, or have an admin enable `secretmanager.googleapis.com` |
| `PERMISSION_DENIED ... secretmanager.secrets.create`                                          | `roles/secretmanager.admin`                  |

### Project prerequisites

These are not roles, but are also required:

- The APIs are enabled: `compute.googleapis.com`, `container.googleapis.com`, `dns.googleapis.com`,
  `iam.googleapis.com`, and, if `GCP_SECRETS_ENABLED=true`, `secretmanager.googleapis.com`.
- The Polaris DNS zone exists in the project, as the stack assumes it, named after `GCP_POLARIS_DOMAIN_NAME` with its dots replaced by dashes, eg `emryspolarisgcp2-click`.  `scripts/gcp/create.sh` creates it.
- The default Compute Engine service account exists and is enabled.

## Account setup

The user running `create.sh`, `destroy.sh`, `login-as-service-account.sh` and `create_iceberg_endpoint.sh`, all of which
use `GCP_PROJECT_ID`, also needs to create service accounts, set IAM policies, and manage the buckets:

| Role                                    | Needed for                                                                                   |
|-----------------------------------------|----------------------------------------------------------------------------------------------|
| `roles/iam.serviceAccountAdmin`         | Creating and deleting the service accounts, and setting their IAM policies                   |
| `roles/resourcemanager.projectIamAdmin` | Granting and revoking project roles, eg `roles/serviceusage.serviceUsageConsumer`            |
| `roles/storage.admin`                   | Creating the buckets, and setting their IAM policies                                         |
| `roles/serviceusage.serviceUsageAdmin`  | Enabling the project's APIs, including `secretmanager.googleapis.com` if `GCP_SECRETS_ENABLED=true` |

## TLS cert management

The user running `issue-cert.sh` and `secrets.sh`, to issue the TLS cert and store it in Secret Manager.  Secret
Manager has no vault to create (unlike Azure Key Vault): the secrets live directly in the project, so these roles are
granted on the project.

| Role                                   | Needed for                                                                                                                      |
|----------------------------------------|---------------------------------------------------------------------------------------------------------------------------------|
| `roles/secretmanager.admin`            | `secrets.sh store` and `delete`: creating the secrets and adding versions.  Also covers `show` and `list`                       |
| `roles/dns.admin`                      | `issue-cert.sh acme` only: acme.sh creating and deleting the `_acme-challenge` TXT record for the DNS-01 challenge              |

If the cert is only stored by a user who does not manage secrets' lifecycle, the narrower `roles/secretmanager.secretVersionAdder`
is enough to add a version to an existing secret, but not to create the secret or run `list`.

The Secret Manager API must be enabled first, by a user holding `roles/serviceusage.serviceUsageAdmin`, which `create.sh` does
if `GCP_SECRETS_ENABLED=true`, or by hand:

```shell
gcloud services enable secretmanager.googleapis.com --project=$GCP_PROJECT_ID
```

The stack deployer needs `roles/secretmanager.secretAccessor` (see above) to restore the secrets on deploy.

## Note

This list is derived from the resources the `gcp-polaris` stack manages, as `GcpCloudProvider` and `GcpPolarisStack`
define them, and from the failures seen deploying it.  It has not been verified from a clean project, so add any role
a deployment is found to need.
