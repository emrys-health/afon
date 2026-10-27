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

You can store snapshots of a config env in AWS Secrets Manager under a tag, and pull them back by it:
```shell
afon-cli.sh config-vault push <tag> -e <env>                # stores config-<env>, tagged with <tag>
afon-cli.sh config-vault list -e <env>                      # lists the snapshots of the env
afon-cli.sh config-vault pull <tag> -e <env>                # writes the missing files of the snapshot to config-<env>
afon-cli.sh config-vault pull <tag> -e <env> -- --force     # also overwrites the files of config-<env> that differ
afon-cli.sh config-vault delete <tag> -e <env>              # removes the snapshot, recoverable for 30 days
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

### Command config-status

To show the status of the current config, you can use the `config-status` action:
```shell
afon-cli.sh config-status
```
If found, it will output the content of the file `afon.md` in the config dir, which can be used to 
provide information to the user of the config content.  It will also list the files in the config dir.

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
Any other name stays in `common`.  Every section is allowed on every cloud when the stacks are
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
