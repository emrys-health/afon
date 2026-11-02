# GCP config and common setup

The config and setup steps common to the GCP env of a [TRE](tre.md) (stack `gcp-full`) and of a [data provider](data.md)
(stack `gcp-polaris`).  Do these first, then follow the doc of your side.

## 1. Pre-requisites

- A GCP project, `GCP_PROJECT_ID`, and a user with the roles to create the env's elements and deploy the stacks (see [roles](../../../scripts/gcp/roles.md)).
  There is no admin-only part, as on Azure: one user can do both.
- A registered domain name for each endpoint the stack serves (see [DNS configuration](../../dns_config.md)), eg
  `<env>polarisgcp.click`.  Its DNS zone is created by `dns-create-zones gcp`, after which the domain must be
  delegated to the Google nameservers.
- A Linux or macOS machine.

## 2. Install the Afon CLI

Unzip the latest `afon-<version>-cli.zip` to `$HOME/.afon/cli` and add it to the path, in `~/.bash_profile`:
```shell
export AFON_CLI_DIR=$HOME/.afon/cli
PATH=$AFON_CLI_DIR:$PATH
export PATH
```
Check with `afon-cli.sh`, which lists the commands.

## 3. Install the local tools

```shell
afon-cli.sh local-setup -c gcp
```
Installs git, jdk, maven, docker, opentofu, kubernetes-cli, the `gcloud` CLI and `gke-gcloud-auth-plugin`, which kubectl needs to reach the cluster.

## 4. Create and fill in the config

Choose the env name, eg `xyz`.  This creates `~/.afon/config-xyz` (and the dir for the `tofu` state):
```shell
afon-cli.sh config-init -e xyz -c gcp
afon-cli.sh cdktf-init -e xyz -c gcp
```
Set in `afon.properties`:
```shell
ENV_NAME=xyz
```
Set in `afon-gcp.properties` (the properties common to every GCP stack):
```shell
GCP_PROJECT_ID=<project>
GCP_REGION=europe-west2
GCP_SERVICE_ACCOUNT=my-vendable-sa@<project>.iam.gserviceaccount.com   # created by gcp-create
GCP_USER_ACCOUNT=<your account>                                        # optional: granted impersonation rights on it
GCP_SECRETS_ENABLED=true                                               # cache the TLS certs in Secret Manager
```
Set in `secret-gcp.properties` the application credentials, which Polaris reaches the buckets with, and, for the stacks with Git or with Polaris'
logs shipped, the HMAC key (see [§8](#8-create-the-env)):
```shell
GOOGLE_APPLICATION_CREDENTIALS=<the JSON of a service account key, on one line>
GCP_HMAC_ACCESS_ID=<accessId>   # set after gcp-create
GCP_HMAC_SECRET=<secret>
```
Set the Polaris user in `secret.properties` (`USER_ID`).  The stack-specific properties are in the docs of each side.

Validate, then switch to the env, which just symlinks `~/.afon/config` to `~/.afon/config-xyz`
(never switch env while a command is running):
```shell
afon-cli.sh config-validate -c gcp
afon-cli.sh config-switch -e xyz
```
Config can also be shared with `afon-cli.sh config-{import|export} <zip> -e xyz`.
See [configuration](../../configuration.md) for all the properties.

## 5. Login

Logs in to `gcloud` and, as the stacks authenticate with the Application Default Credentials, updates them too, then selects the project:
```shell
afon-cli.sh gcp-login
```
`gcp-account` selects the project again without logging in, eg after switching env.  Where there is no user to log in, eg in CI, point
`GOOGLE_APPLICATION_CREDENTIALS` at a credentials file instead.

## 6. DNS zones

After `gcp-create` (step 8), `afon-cli.sh dns-create-zones gcp <stack>` creates one Cloud DNS zone per domain the stack serves.  Then delegate each domain to it, at the registrar or,
for a subdomain, with `afon-cli.sh dns-delegate gcp <stack>` (see [DNS configuration](../../dns_config.md)), and check:
```shell
gcloud dns managed-zones describe <zone> --format="value(nameServers)"
dig +short A <domain>
```

## 7. TLS certificate in Secret Manager

Advisable, as ACME limits how many free certs can be issued in a week; with `GCP_SECRETS_ENABLED=true` a cached cert is restored instead of
requesting a new one.  Run after `gcp-create`, with the domains set in the config:
```shell
afon-cli.sh gcp-issue-cert        # free ACME issuer: issues and stores the cert of every domain configured
afon-cli.sh gcp-secrets list      # check the certs are stored
```

## 8. Create the env

```shell
afon-cli.sh gcp-create <stack-id>   # stack-id: gcp-full|gcp-keycloak|gcp-polaris, or its role tre|identity|catalog
```
It creates the service accounts, the catalogs' buckets and the roles on them.  The stack decides what else is created: for `gcp-full`, which has Git, the
Gitea bucket `GCP_GITEA_BUCKET`, an HMAC key and Gitea's Persistent Disk, and, if `GCP_POLARIS_LOG_BUCKET` is set, the bucket of Polaris' logs.
The secret of the HMAC key is shown **once**: set it and its access id as `GCP_HMAC_SECRET` and `GCP_HMAC_ACCESS_ID` in `secret-gcp.properties`.
(The key is that of the service account, and is also what ships Polaris' logs.)

## 9. Useful checks and teardown

```shell
afon-cli.sh gcp-cluster <stack|role>                # is the GKE cluster up?
afon-cli.sh gcp-update-kube-config <stack|role>     # point kubectl at the cluster
afon-cli.sh gcp-polaris                             # is Polaris serving?
afon-cli.sh gcp-keycloak                            # is KeyCloak serving?
afon-cli.sh gcp-destroy                             # deletes the buckets, the disks, the HMAC key and the service accounts
```
