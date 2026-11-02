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
| `roles/compute.viewer`                    | Only for a stack with Git, ie `gcp-full`: looking up the Persistent Disk of Gitea's repositories, which `create.sh` creates.  The stack fails to plan if there is none |
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

### Authentication

The stacks authenticate with Application Default Credentials (ADC), resolved when `tofu` runs and renewed by the
Google auth library, as the AWS and Azure stacks use the credentials of the AWS SDK and of `az login`.  No token is written
into the plan, so a plan synthesised earlier can still be applied, destroyed or inspected.

Log in once with `afon-cli.sh gcp-login`, which runs `gcloud auth login --update-adc`.  It logs in the `gcloud` commands of
the scripts and, with `--update-adc`, also writes the same login to the ADC used by the Google provider, so both run as
the same user.  `gcloud auth application-default login` alone only sets the ADC, not the account of the `gcloud` commands.

Where the user cannot log in, eg in CI, point `GOOGLE_APPLICATION_CREDENTIALS` at a credentials file instead, eg one
issued by Workload Identity Federation.  The Google provider and `gcloud` both read it.

The provider bills the API usage to the stack's own project, `GCP_PROJECT_ID`, which is why the deployer needs
`roles/serviceusage.serviceUsageConsumer`.

If Google asks to log in again while a long command is running, with `invalid_rapt` or a reauthentication error, the
Google Workspace session control forces a new login after a set time: run `gcp-login` again.

### Symptoms of a missing role

| Error                                                                                         | Missing                                      |
|-----------------------------------------------------------------------------------------------|----------------------------------------------|
| `The user does not have access to service account "...-compute@developer.gserviceaccount.com"` | `roles/iam.serviceAccountUser`               |
| `namespaces is forbidden ... requires one of ["container.namespaces.create"]`                 | `roles/container.admin`                      |
| `Error 403: Forbidden` reading `data.google_dns_managed_zone`                                 | `roles/dns.admin`, or no zone in the project |
| `PERMISSION_DENIED ... serviceusage.services.enable` from `secrets.sh store`                  | `roles/serviceusage.serviceUsageAdmin`, or have an admin enable `secretmanager.googleapis.com` |
| `PERMISSION_DENIED ... secretmanager.secrets.create`                                          | `roles/secretmanager.admin`                  |
| `does not have storage.hmacKeys.create access to the Google Cloud project` from `gcp-create`  | `roles/storage.hmacKeyAdmin`, see [HMAC key](#hmac-key) |
| `Policy update access denied` ... `setIamPolicy` from `gcp-destroy`                           | `roles/resourcemanager.projectIamAdmin`      |

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
| `roles/storage.admin`                   | Creating the buckets, and setting their IAM policies, and, for the stacks with Git or Logging, creating and deleting the [HMAC key](#hmac-key) (`roles/storage.hmacKeyAdmin` is enough for the key alone) |
| `roles/compute.storageAdmin`            | For the stacks with Git: creating and deleting the Persistent Disk of Gitea's repositories, which outlives the stacks |
| `roles/serviceusage.serviceUsageAdmin`  | Enabling the project's APIs, including `secretmanager.googleapis.com` if `GCP_SECRETS_ENABLED=true` |

### HMAC key

The stacks with Git (Gitea) or with Logging, if `GCP_POLARIS_LOG_BUCKET` is set (the log shipper of Polaris), reach their buckets through
the S3 compatible API of GCS, which only takes an HMAC key, not a Google login.  The key belongs to the service account
`GCP_SERVICE_ACCOUNT`, so that whoever uses it has what that account has on the buckets.  It is set as
`GCP_HMAC_ACCESS_ID` and `GCP_HMAC_SECRET` in `secret-gcp.properties`.

Creating it, which `create.sh` does if the property has no access id, and deleting it, which `destroy.sh` does, need on the project:

| Permission                  | Role that carries it, among others             | For                                          |
|-----------------------------|------------------------------------------------|----------------------------------------------|
| `storage.hmacKeys.create`   | `roles/storage.hmacKeyAdmin`, `roles/storage.admin` | `gcloud storage hmac create`            |
| `storage.hmacKeys.update`, `storage.hmacKeys.delete` | the same         | `destroy.sh`, which deactivates the key, then deletes it |
| `storage.hmacKeys.get`      | the same                                       | `create.sh` and `destroy.sh` checking that the configured key exists |

```shell
gcloud projects add-iam-policy-binding $GCP_PROJECT_ID --member="user:<account>" --role="roles/storage.hmacKeyAdmin"
```

The deployer of the stacks needs none of it: the stacks only use the credentials, they do not manage the key.  So an admin may create the key and
hand the access id and secret over, with:

```shell
gcloud storage hmac create $GCP_SERVICE_ACCOUNT --project=$GCP_PROJECT_ID
```

**NOTE:** The secret is shown only once, when the key is created: GCP never shows it again.  A lost secret means a new key, and so deleting the old one.

If the role is granted and the creation is still refused, an organisation policy may forbid HMAC keys, eg `constraints/storage.restrictAuthTypes`: that
needs an organisation admin to change.  Without the key a stack with Git still deploys, but Gitea cannot use its bucket and crash-loops, and the log shipper of Polaris
is refused by the bucket: leave `GCP_POLARIS_LOG_BUCKET` blank to skip it.

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
