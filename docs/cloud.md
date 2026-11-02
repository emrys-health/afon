# Cloud infrastructure

## Authentication

**IMPORTANT:** Ensure you have done a login with the cloud cli before executing the create or destroy scripts.

```shell
afon-cli.sh <cloud>-login
```
where cloud is one of `{aws|azure|gcp}`.

To show the account used by the cloud cli, once logged in:
```shell
afon-cli.sh <cloud>-account 
```
where cloud is one of `{aws|azure|gcp}`.  On `azure` it also sets the configured subscription to your account.

## Create

To create the cloud infrastructure a stack needs, run:

```shell
afon-cli.sh <cloud>-create <stack>
```
where cloud is one of `{aws|azure|gcp}` and the stack is the one the env is for, given by its id or by its role in the cloud (`identity`, `catalog` or `tre`), eg:
```shell
afon-cli.sh aws-create aws-full
afon-cli.sh gcp-create tre
```
The roles are mapped to stacks by `stacks.yaml` (see [Define your own stacks](stacks.md#define-your-own-stacks)), so they work whatever the stack ids are.

What is created follows from the stack:

- **Every stack:** the storage buckets, the IAM roles and the service accounts.
- **A stack with Git:** the bucket (or container) of Gitea, `<CLOUD>_GITEA_BUCKET`, which must be set.
  On GCP also the disk that holds Gitea's repositories.  It is kept when the stack is deleted, so that a new cluster finds the repositories again (see [volumes that outlive the cluster](cluster.md#volumes-that-outlive-the-cluster)).  AWS and Azure create it when the stack is deployed, but the GCP provider cannot look up an existing disk, so on GCP it has to be created here first, or the deploy fails.
- **Polaris' logs:** their bucket, `<CLOUD>_POLARIS_LOG_BUCKET`, if it is set.
- **GCP, for the stacks that use the buckets of Gitea or of Polaris' logs:** an HMAC key that they write to the buckets with, unless one is configured. Its secret is shown only once: see [create the env](guides/gcp/config.md#8-create-the-env).

The DNS zones are not created here, but by `dns-create-zones <cloud> <stack>` (see [DNS configuration](dns_config.md)).

On `azure`, a boolean argument says whether you are running as admin, by default `false`: if not, an admin must run the script `azure/create-admin` for you first.
Eg `afon-cli.sh azure-create false tre`.

The scripts can also be run from the source tree, eg `./afon.sh -a aws-create tre`.  Either way, they look the stack up in `stacks.yaml`
(through `afon-cli.sh` or `afon.sh`), so that it is the stack, not its id, that decides what is created.

## Destroy

To destroy the cloud infrastructure
```shell
afon-cli.sh <cloud>-destroy
```
where cloud is one of `{aws|azure|gcp}`.  On `azure` the boolean arg determines if you're running as admin.  If not, the script `azure/destroy-admin` must be run by an admin.  By default, `as-admin=false`.
