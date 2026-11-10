# Configuration

## Entry points

**NOTE:** The commands below apply to all [entry points](afon_entry_points.md).

## Config dir

All configuration is read from the config dir, assumed to be a child dir named `config` in the Afon home dir,
which by default points to `$HOME/.afon`.  The default config dir is then `$HOME/.afon/config`.

## Config Env

Afon supports different envs for configuration, eg to allow for an `dev` and a `stg` env.  

### Command config-init
You can initialise the config files for a given env by running:

```shell
afon-cli.sh config-init -e <env>
```
This copies config files from templates to the `config-<env>` dir and creates a symlink `config -> config-<env>`.  

**NOTE**: The config-init command will create a new config dir, if not found, but it also can work with an existing config dir.
However, in case of an existing config dir, any existing files will not be overwritten and will need to be removed manually.

Once initialised, the config values need to be filled in.

### Command config-switch

You can switch between config envs using the `config-switch` action:
```shell
afon-cli.sh config-switch -e <env> # config symlink for <env> 
```
This will create or move the symlink `config -> config-<env>`.

**NOTE**: If you're using multiple config envs, the config dir must be a symlink and not a dir.  

### Command config-validate 

Once completed, you can validate the config files by running: 
```shell
afon-cli.sh config-validate [-c <cloud>]
```
The validation checks that all the template properties have a corresponding non-empty property in the config dir.
The optional `-c <cloud>` allows you to check only the cloud-specific config. 

### Command config-import and config-export

You can also import or export the config files as a zip: 
```shell
afon-cli.sh config-{import|export} <path-to-config-zip> -e <env>
```
This can be useful when sharing a pre-defined configuration.

**NOTE**: The config-import command assumes that the `config-<env>` dir does not exist and is created in the process.  
If a config dir is already present, it will stop.

### Command config-vault

You can store snapshots of a config env in the secret store of a cloud under a tag, and pull them back by it: AWS Secrets Manager, Azure Key Vault or GCP Secret Manager.  The cloud is required, chosen with the global `-c|--cloud <aws|azure|gcp>` of `afon.sh` and `afon-cli.sh` (or `AFON_CLOUD`), so that it is always clear which store is used.  There is no default.  Only `push` also accepts `-c all`, which pushes the same config to the stores of all three clouds, one after the other (a failure on one does not stop the others, but the exit status is then 1); `--region`, `--vault` and `--project` are not given then, as each cloud uses its config or defaults:
```shell
afon-cli.sh config-vault push <tag> -e <env> -c aws         # stores config-<env>, tagged with <tag>
afon-cli.sh config-vault list -e <env> -c aws               # lists the snapshots of the env
afon-cli.sh config-vault pull <tag> -e <env> -c aws         # writes the missing files of the snapshot to config-<env>
afon-cli.sh config-vault pull <tag> -e <env> -c aws -- --force  # also overwrites the files of config-<env> that differ
afon-cli.sh config-vault delete <tag> -e <env> -c aws       # removes the snapshot, recoverable for 30 days
afon-cli.sh config-vault push <tag> -e <env> -c azure       # the same in Azure Key Vault, likewise with -c gcp
afon-cli.sh config-vault push <tag> -e <env> -c all         # pushes to the stores of aws, azure and gcp
```
The env is the global one of `afon.sh` or `afon-cli.sh`: `--env`, `--config-env`, or `AFON_ENV`, which also switch
the `config` symlink to `config-<env>`.  Without one, it is the env that `config` currently points to.  Likewise the afon home is the global `--home` or `AFON_HOME`.

The `<tag>` is the arg after `push`, `pull` and `delete`, and is required by them, so that each snapshot has a tag that is easy to retrieve it by.  It is free-form text without spaces, eg a release, a git tag or a name, of letters, digits and `/ _ + = . @ -`, as AWS allows no others.

`pull` compares the files of the snapshot with those of `config-<env>`, and reports those that differ and those that
are missing.  If only some are missing, it writes them.  If any differ, it stops without writing anything, unless
given `--force`, which overwrites them.  If none differ or are missing, it leaves `config-<env>` untouched.  Config files that the snapshot does not have are
left alone.  With `--dry-run` it only reports.

Each snapshot is one secret `afon/<env>/<tag>` holding all the config files, empty ones included, and tagged with
`afon:env` and `afon:tag`.  Pushing again with the same tag needs `--force`, which replaces the snapshot, and
`delete --force` deletes it at once, without the recovery window.  Add `--dry-run` to see what would be done.  With `afon.sh`, these options of the script go after a `--`, as `afon.sh` does not take them for its own.

It needs the `aws` CLI, logged in with the `secretsmanager` permissions to create, update, tag, list, read and delete secrets, and `jq`.  The region is `--region`, else the default of the `aws` CLI, as `pull` may run before
there is any afon config to read it from.

The cloud changes where and how the snapshot is kept:

| | `aws` | `azure` | `gcp` |
|---|---|---|---|
| Store | Secrets Manager | Key Vault | Secret Manager |
| Secret name | `afon/<env>/<tag>` | `afon-<env>-<tag>` | `afon-<env>-<tag>` |
| Characters of the tag | letters, digits, `/ _ + = . @ -` | letters, digits, `-` | letters, digits, `_ -` |
| Tagged with | tags `afon:env`, `afon:tag` | tags `afon-env`, `afon-tag` | annotations `afon-env`, `afon-tag` |
| Max size | 64 KB | 25 KB | 64 KiB |
| `delete` | recovery window of 30 days | soft-deleted | at once |
| `delete --force` | at once | purged at once (needs the purge permission) | same |
| `push --force` | replaces the value | adds a version | adds a version |
| Where | `--region`, else the `aws` default | `--vault`, else `AZURE_KEY_VAULT_NAME` | `--project`, else `GCP_PROJECT_ID` |
| Needs | `aws` CLI | `az` CLI, role Key Vault Secrets Officer on the vault | `gcloud` CLI, role Secret Manager Admin, Secret Manager API enabled |

On `azure` and `gcp` a dot or underscore in the tag is converted to a dash, as the name of a secret cannot have dots there, nor underscores on `azure`, so a version like `1.0-beta-9` is stored with the tag `1-0-beta-9` (the secret is `afon-<env>-1-0-beta-9`).  The `1.0-beta-9`, `1_0-beta-9` and `1-0-beta-9` forms all find it with `push`, `pull` and `delete`.  The same goes for the env in the name.  `aws` keeps the tag as given.

A snapshot over the size limit is gzipped and base64-encoded by `push`, which `pull` recognises and decompresses; it fails only if it is still over the limit.

All need `jq`.  On Azure, the Key Vault is created by `azure-keyvault create`; on GCP, the Secret Manager API is enabled by `gcp-create` if `GCP_SECRETS_ENABLED=true` in `afon-gcp.properties`, by `gcp-secrets store`, or by a project admin.  Unlike `AZURE_KEY_VAULT_NAME`, which names the Key Vault, `GCP_SECRETS_ENABLED` is a plain on/off switch for the use of Secret Manager by the stack, and `config-vault` does not need it: it needs only `GCP_PROJECT_ID` (or `--project`) and the API enabled.  Unless `--vault` or `--project` is given, the Key Vault and the project are read from `AZURE_KEY_VAULT_NAME` and `GCP_PROJECT_ID` in `afon-azure.properties` and `afon-gcp.properties` of `config-<env>`. No config is needed, eg to `pull` into a new env: the Key Vault is then `<env>-afon-key-vault`, as in the templates, and the project that of the `gcloud` CLI.

### Command config-status

To show the status of the current config, you can use the `config-status` action:
```shell
afon-cli.sh config-status
```
If found, it will output the content of the file `afon.md` in the config dir, which can be used to 
provide information to the user of the config content.  It will also list the files in the config dir.

### Creating an env from an existing one

A new env, eg `emrys3` for the smoke tests, is quickest to make from an env that works, eg `emrys2`: copy its config dir and change what is specific to the env.  This lists what, and the setup in the clouds that goes with it.

#### The config

Copy the dir, and set `ENV_NAME` in `afon.properties` to the new env, which is the only change in the files if the env's values follow it:

```shell
cp -r ~/.afon/config-emrys2 ~/.afon/config-emrys3
```

Write the values that carry the env's name with [interpolation](#property-value-interpolation), so that the files of two envs differ only in
`ENV_NAME`: `CLUSTER_NAME=${ENV_NAME}-cluster`, `AWS_POLARIS_DOMAIN_NAME=aws-polaris.${ENV_NAME}.click`,
`AZURE_STORAGE_ACCOUNT=${ENV_NAME}afonstorage`, `AWS_BUCKET_NAME_aws=${ENV_NAME}-afon-bucket`, and so on.  A name that is not derived from
the env's name, eg a domain registered under another name, is set by hand.  Check that nothing of the old env is left:
`grep -rn emrys2 ~/.afon/config-emrys3`, which should show only the values listed below as having to change.

**Can stay the same**, as they belong to the cloud account, the project or the people, not to the env:

| Properties                                                                                     | Because                                                                   |
|------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------|
| `AWS_ACCOUNT_ID`, `AWS_REGION`; `AZURE_SUBSCRIPTION`, `AZURE_REGION`, `AZURE_RESOURCE_GROUP`; `GCP_PROJECT_ID`, `GCP_REGION` | the envs share the cloud accounts: their resources are told apart by name |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_S3_ROLE_ARN`, `AWS_GLUE_ROLE_ARN`            | the Polaris service user and its roles are per account                    |
| `AZURE_TENANT_ID`, `AZURE_APP_ID`, `AZURE_APP_SECRET`, `AZURE_SP_ID`, `AZURE_CA_*`, `AZURE_DATABRICKS_TOKEN` | the app registration is per tenant                          |
| `GOOGLE_APPLICATION_CREDENTIALS`, `GCP_SERVICE_ACCOUNT`, `GCP_HMAC_ACCESS_ID`, `GCP_HMAC_SECRET` | the service account is per project, and the HMAC key belongs to it       |
| `POLARIS_DB_HOST`, `POLARIS_DB_*`, `GUACAMOLE_DB_*`, `GITEA_DB_*`                              | the Postgres instance may be shared: each env gets databases of its own, named after its Polaris domain (`polaris_db_<domain>`), its cluster (`guacamole-<cluster>`) and its cloud and cluster (`gitea_<cloud>_<cluster>`) |
| `POLARIS_ID`, `POLARIS_SECRET`, `USER_ID`, `KEYCLOAK_BOOTSTRAP_ADMIN_*`, `OAUTH2_CLIENT_ID`, `OAUTH2_CLIENT_SECRET`, `OAUTH2_COOKIE_SECRET`, `JUPYTER_VNC_PASSWORD`, `SNOWFLAKE_*` | a client or a login is created with these values by the env's own setup, or only used |
| `<CLOUD>_COSIGN_PUBLIC_KEY`, `<CLOUD>_COSIGN_KEY`, `<CLOUD>_IMAGE_REGISTRY`                      | the images are signed once, with the key of the cloud, into its registry, whichever env deploys them: Kyverno verifies the same signatures with the same public key.  A separate key, and so a separate registry or a re-signing of the images, is only for an env that is to admit images that the others do not |
| `users.csv`, `users-acl.csv`, `whitelist.yaml` (as it names the domains by property)          | the users of the env, and the domains it may resolve                      |

**Must change**, or the new env would use, or clash with, the old one:

| Properties                                                                                     | Change to                                                                  |
|------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------|
| `ENV_NAME`                                                                                     | the new env                                                                |
| `OIDC_DOMAIN_NAME`, `<CLOUD>_OIDC_DOMAIN_NAME`, `<CLOUD>_POLARIS_DOMAIN_NAME`, `<CLOUD>_JUPYTER_DOMAIN_NAME` | domain names of the new env (`${ENV_NAME}` in them is enough)         |
| `<CLOUD>_BUCKET_NAME_<id>` in `catalogs-<cloud>.properties`                                    | bucket names of the new env: S3 and GCS names are global, so the old ones would be the old env's buckets, and Azure storage account names too |
| `AZURE_STORAGE_ACCOUNT`, `AZURE_KEY_VAULT_NAME`, `<CLOUD>_GITEA_BUCKET`, `<CLOUD>_POLARIS_LOG_BUCKET` | names of the new env, which `${ENV_NAME}` gives, as in the templates       |
| `AZURE_CONNECTION_STRING` (`secret-azure.properties`)                                          | **blank** it in the copy: it has the key of the old env's storage account, so a copy would write to that account. `azure-create` then says that it is not set, which is expected on the first run, as the string is only known once the account exists.  Set it afterwards to the output of `az storage account show-connection-string`, and check it with `azure-check-storage`.  Until then, what needs it does not work: the Azure monitoring (Loki), which stops with a message that says so, Gitea's blob container and `AzureReader` |
| `KEYCLOAK_DB_URL`                                                                              | a database of its own, `jdbc:postgresql://${POLARIS_DB_HOST}:5432/keycloak-${ENV_NAME}`: it is the one value that names the database directly, so two envs on the same Postgres would otherwise share the KeyCloak realm and its users.  The database is created when KeyCloak starts.  An env that already runs on another name, eg `keycloak-2`, keeps it, as a new name starts on an empty database: its users are created again from `users.csv` by the next setup |
| `<CLOUD>_EGRESS_IP`                                                                            | **blank** them in the copy: one address serves one cluster's outbound traffic, so two envs deployed together on a cloud cannot both pin the same one, and left blank, the cluster gets an address of its own from the cloud (see [the egress IP of each cloud](cluster.md#egress-ip-aws)).  Give the new env an address of its own only if something allow-lists it: `<cloud>-egress-ip create <name>`.  Once an address is set in the config, whitelist it on the Postgres instance, with `db-whitelist` |
| `<CLOUD>_POLARIS_ALLOWED_CIDRS`                                                                | only if the new env is to be reached from other addresses                  |

#### In the clouds

Once the config is in place, with `--env <new env>` (or `config-switch`), and logged in to each cloud as an admin:

1. **DNS:** register the domain, if it is a new one.  Create the zone of each domain name with `dns-create-zones <cloud> <stack>`, then delegate
   them from the parent domain with `dns-delegate <cloud> <stack>` (at the registrar, or with `--route53`), and check with `<cloud>-dns-zones`.
2. **The cloud resources:** `<cloud>-create` create the buckets, the Azure storage account and Key Vault, and the access
   to them, eg the IAM whitelist entry of each AWS catalog bucket.  Then set `AZURE_CONNECTION_STRING` (blank in the copy), as above.
3. **Certificates:** issue the certificate of each domain name, `<cloud>-issue-cert`; check with
   `<cloud>-issue-cert --check`.  
4. **Google OAuth:** add the redirect URIs of the new Jupyter domain names to the Google OAuth client `OAUTH2_CLIENT_ID`.
5. **The config in the vaults:** `config-vault push <tag> -c <cloud>` for each cloud, so that the env can be pulled elsewhere, eg by the smoke tests. The Key Vault of Azure is new, so it holds nothing yet.

#### For the CI smoke tests

To run the [smoke tests](github_workflows.md#smoke-tests-workflows) on the new env:

1. Push its config to the vault of each cloud under the tag the workflows pull (`current` by default).
2. Let the cloud identity use the new env's resources: `afon-cli.sh --env <env> github-cloud-identity create <cloud>`.  On Azure it grants the identity
   Key Vault Secrets User on the new Key Vault and access to the new storage account, so it is needed.  The AWS and GCP identities are per account and project,
   so the command changes nothing there if those are shared with the old env.
3. Give the GitHub environments `smoke-tests-<cloud>` the values of the new env: `afon-cli.sh --env <env> github-cloud-env create <cloud>`.  Those that
   differ are the Key Vault (`AZURE_KEY_VAULT_NAME`) and, if the account or project differs, the others.
4. Set the repository variable `SMOKE_TESTS_CONFIG_ENV` to the new env.

#### Summary for a cloud

For a `<cloud>` (`aws`, `azure` or `gcp`), a `<stack>` of it (eg `<cloud>-full`) and the new `<env>`, logged in to the cloud as an admin.  Every command takes
`--env <env>`, so that the `config` symlink stays on the env that is active.  Steps 1 to 4 are done once per cloud, and each needs the one before it.

| # | Step | Command |
|---|------|---------|
| 1 | Create the DNS zone of each domain name | `afon-cli.sh --env <env> dns-create-zones <cloud> <stack>` |
| 2 | Delegate them from the parent domain, at the registrar or with `--route53` | `afon-cli.sh --env <env> dns-delegate <cloud> <stack>` |
| 3 | Check that the delegation is in place | `afon-cli.sh --env <env> <cloud>-dns-zones` |
| 4 | Create the cloud resources | `afon-cli.sh --env <env> <cloud>-create <stack>` |
| 5 | Issue the certificates | `afon-cli.sh --env <env> <cloud>-issue-cert` (`aws`), or `<cloud>-issue-cert acme` (`azure`, `gcp`) |
| 6 | Check that they are all there | `afon-cli.sh --env <env> <cloud>-issue-cert --check` |
| 7 | Push the config to the vault of the cloud | `afon-cli.sh --env <env> config-vault push current -c <cloud>` |
| 8 | Let the CI identity use the resources | `afon-cli.sh --env <env> github-cloud-identity create <cloud>` |
| 9 | Give the GitHub environment the values | `afon-cli.sh --env <env> github-cloud-env create <cloud>` |

Then set the repository variable `SMOKE_TESTS_CONFIG_ENV` to the env, once a run of each cloud has passed with a dispatch input `config_env=<env>`.

What differs by cloud:

- **`aws`:** the DNS zones are Route 53 hosted zones.  `aws-issue-cert` requests an ACM certificate with DNS validation for each domain, and takes `all`, `keycloak`,
  `jupyter` or `polaris`.  `aws-create` adds the IAM whitelist entry of each catalog bucket.  The IAM role of the CI identity is per account.
- **`azure`:** `azure-create` also creates the storage account and the Key Vault, and needs `AZURE_APP_ID` and `AZURE_SP_ID` in `secret-azure.properties` (the app
  is shared, see above).  It ends by saying that `AZURE_CONNECTION_STRING` is not set, as it is blank in the copy: get the string of the new account with
  `az storage account show-connection-string --resource-group <AZURE_RESOURCE_GROUP> --name <AZURE_STORAGE_ACCOUNT> --query connectionString -o tsv`,
  set it in `secret-azure.properties` before step 7, and check it with `afon-cli.sh --env <env> azure-check-storage`.  The certificates are cached in the Key
  Vault, so step 5 follows step 4.  `azure-create` also grants the user who runs it `Storage Blob Data Contributor` on the new storage account, which the cdktf
  store needs and the subscription roles do not include (it can take a few minutes to apply).  `github-cloud-identity` grants roles on the Key Vault and the storage account, so step 8 follows step 4 too.
- **`gcp`:** the certificates are cached in Secret Manager, which needs `GCP_SECRETS_ENABLED=true`.  The identity is per project, and it binds the cdktf and the
  logs buckets, which must exist.

Nothing else is needed from the cloud for an optional `<CLOUD>_EGRESS_IP`: left blank, the cluster gets an address of its own.  To pin one, allocate it with
`afon-cli.sh --env <env> <cloud>-egress-ip create <name>`, with a name of its own, as the default name is that of the other env, and set it in the config before step 7.
Whitelist each address that is set on the Postgres instance of `POLARIS_DB_HOST`, as the clusters connect to it from their egress IPs: `afon-cli.sh --env <env> db-whitelist [<cloud>]`
(`--dry-run` says what it would add; a cloud with no address is skipped, and one already allowed is left as it is), before the stack is deployed.

## Properties

The properties configuration is currently file-based.

The configuration properties are cloud-specific but will include some properties that are common to all cloud providers.  

All cloud-specific configuration files will include the cloud reference in their names, e.g. `afon-aws.properties`.

Afon uses two types configuration properties in the config dir:

**`afon.properties`**: the properties containing non-sensitive configuration, such as cluster name, bucket names, domain names, regions, endpoints:

- [afon.properties](../templates/config/afon.properties)
- [afon-aws.properties](../templates/config/afon-aws.properties)
- [afon-azure.properties](../templates/config/afon-azure.properties)
- [afon-gcp.properties](../templates/config/afon-gcp.properties)

**`whitelist.yaml`**: the domain names that the TRE's notebooks and Spark may resolve.  It is not mandatory yet: without it the deprecated `ALLOWED_DOMAINS_CSV` is used, if set, else no domains are allowed.

It has a `common` section, for the domains of any cloud, and a section per cloud (`aws`, `azure`, `gcp`), each with a list of `domains`.
Each domain is spelled out as an RFC 1123 compliant host name, one per entry, with no URL and no wildcard.  A `${NAME}` in an entry is
replaced by the value of that property, which is an `AfonProperty` or the key of a catalog (`<CLOUD>_BUCKET_NAME_<id>`, eg
`${AWS_BUCKET_NAME_aws_trino}.s3.${AWS_REGION}.amazonaws.com`, one entry per catalog bucket); any other name is an error.  An entry whose
property is not set is left out, with a warning, so a cloud that is not deployed to needs none of its properties.  GCP lists the shared
`storage.googleapis.com` instead of one entry per bucket, as GCS bucket names may contain `_`, so are not RFC 1123 compliant.  Write the
lists in block style, as `${NAME}` is not valid YAML in a `[flow, list]`.

- [whitelist.yaml](../templates/config/whitelist.yaml)

**`catalogs.properties`**: the properties containing catalogs configuration:

- [catalogs.properties](../templates/config/catalogs.properties)
- [catalogs-aws.properties](../templates/config/catalogs-aws.properties)
- [catalogs-azure.properties](../templates/config/catalogs-azure.properties)
- [catalogs-gcp.properties](../templates/config/catalogs-gcp.properties)

- **`secret.properties`**: the properties containing sensitive configuration, such as credentials and secrets:

- [secret.properties](../templates/config/secret.properties)
- [secret-aws.properties](../templates/config/secret-aws.properties)
- [secret-azure.properties](../templates/config/secret-azure.properties)
- [secret-gcp.properties](../templates/config/secret-gcp.properties)

**NOTE:**  When running in the JVM, you use the Polaris service account credentials in `secret-aws.properties`.
When running in CLI (e.g. tofu apply|destroy), you use the user admin credentials in ~/.aws/credentials.


## Property value interpolation

Property values can be interpolated to minimise duplication.

For example, in `afon.properties`:
```shell
ENV_NAME=dev
CLUSTER_NAME=${ENV_NAME}-cluster
OIDC_DOMAIN_NAME=${ENV_NAME}-oauth.click
```
And in `afon-aws.properties`:
```shell
AWS_POLARIS_DOMAIN_NAME=${ENV_NAME}-polaris.click # the reference can be defined in common file
```
Circular dependencies and undefined references are caught and result in failure to load properties.

### Users 

Afon supports two user configuration files in the config dir:

**`users.csv`** (list of users, one per line):

- [users.csv](../templates/config/users.csv)

**`users-acl.csv`** (list of users ACL, one per line):

- [users-acl.csv](../templates/config/users-acl.csv)

The `catalogs` field is a colon-separated list of entries, each one of:

- `all`: every catalog
- a catalog name, e.g. `aws`
- an exclusion, a catalog name prefixed by `!`, e.g. `!aws_forbidden`

Names may use the `*` wildcard, e.g. `aws_*` or `!*_forbidden`.
A catalog is authorised if it matches an inclusion and no exclusion: exclusions win over inclusions,
so a list of only exclusions authorises no catalog. For example:

```
will,all:!aws_forbidden:!azure_forbidden:!gcp_forbidden
jane,all:!*_forbidden
```

Setting up a stack gives KeyCloak the users of `users.csv`: those it lacks are created, and those it has get the password
from `users.csv`, so a password changed in KeyCloak is reset by the next setup, as the KeyCloak database outlives the stacks.

Gitea is given the users of `users.csv` every time its pod starts: those it lacks are created, and those it has get
the password from `users.csv`, so a password changed in Gitea is reset by its next restart.  A user without an
email is given `<username>@noreply.localhost`.  Users removed from `users.csv` stay in Gitea, with their repositories,
until deleted there.  The admin, `GITEA_ADMIN_USERNAME`, is left to its own properties, even if in `users.csv`.  A
change to `users.csv` takes effect on the next deploy, which restarts Gitea's pod.

The configured user, which clients authenticate as (e.g. to get its OIDC token, or to run Spark), is named by the
`USER_ID` property in `secret.properties` and must be one of the users in `users.csv`, whose password is read from there.

### Command config-updates

To update the config files to the current property names, run:
```shell
afon-cli.sh config-updates
```
It renames the properties that were renamed, and removes those that are no longer used (they are ignored if left), in every
`*.properties` file of the config dir.  It works the same on a config env, ie `$AFON_HOME/config` a symlink to
`config-<env>`, and on a standalone config dir: it says which it is updating, and never moves or relinks the dir.  A file that is a
symlink, eg to a file shared by the envs, is updated in place through the link.

It also moves the domain names of the deprecated `ALLOWED_DOMAINS_CSV` of `afon.properties` to a new `whitelist.yaml` in the config dir,
so that the same names are allowed as before, and removes the property (its previous value is in the backup).  The Polaris, Jupyter and
OIDC domain names of a cloud, as the config of the dir says, go in the section of that cloud, because the smoke tests only check the
`common` names and those of the cloud of the TRE (a domain name of another cloud only resolves, and admits the caller, while its stack
is deployed): `OIDC_DOMAIN_NAME` goes in the section of each cloud without an `<CLOUD>_OIDC_DOMAIN_NAME` of its own, or in `common` if
that is every cloud.  A catalog bucket goes in the section of the cloud whose storage endpoint it is at (`*.amazonaws.com`,
`*.blob.core.windows.net` or `*.googleapis.com`), so the smoke tests do not check, from the pods of one cloud, the storage of another.
A name that is the value of one of those domain name properties is written as the property, eg `${AZURE_POLARIS_DOMAIN_NAME}`, so
that it follows the property when it changes, eg to a subdomain (see [DNS configuration](dns_config.md#the-tre-whitelist)); any other
name is written as it is.  Any other name stays in `common`.  Every section is allowed on every cloud when the stacks are
deployed, as before: the sections only decide what the smoke tests check.  An existing `whitelist.yaml` is never overwritten: then the
command only says so, and the names are to be added by hand.  Arrange the generated domains further, with the catalog buckets, as in
the [template](../templates/config/whitelist.yaml).
Until then, `ALLOWED_DOMAINS_CSV` is still used if `whitelist.yaml` is missing.

A file is only touched if it changes, and then its previous contents are kept as `<file>.bak`, or `<file>.bak.<timestamp>` if
there is already one, so that a backup of an earlier run is never overwritten.  It is safe to run more than once.

**NOTE**: giving `--env` or `--config-env` (or setting `AFON_ENV`) switches the config dir first, like any other command, and moves a
standalone `config` dir to `config-<env>`.  To update a standalone config dir, give none of them.

### Command config-user

To update `secret.properties` to the current user config, if needed, and show the configured user:
```shell
afon-cli.sh config-user
```
It updates `secret.properties` from the former Polaris user config, in which the user's password and email were
duplicated from `users.csv`:

- renames `USER_POLARIS_ID` to `USER_ID`, or removes it if `USER_ID` is already set;
- removes the no longer used `USER_POLARIS_SECRET` and `USER_POLARIS_EMAIL`, and their comments.

If anything changed, `secret.properties` is first backed up as `secret.properties.bak`, and the changed lines are
shown with their values masked.  It is safe to run more than once: an up-to-date `secret.properties` is left untouched.

It then prints the `USER_ID` user's details from `users.csv` (never its password, only whether it is set) and its
catalogs from `users-acl.csv`.  It fails if `USER_ID` is not set or is not one of the users in `users.csv`.

**NOTE**: If you publish your config as GitHub secrets (see [acceptance tests](acceptance_tests.md#config-secrets)),
republish `secret.properties` after it is updated, with `./afon.sh -a github-secrets push`.
