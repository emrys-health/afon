# Acceptance tests

The acceptance tests in `afon-acceptance` verify that cross-cloud access works correctly — including that access to "forbidden" buckets is properly denied.

The acceptance tests can be run against all cloud providers or just a single one:
```shell
./afon.sh -a build -bp acceptance # builds components running acceptance tests for all cloud providers
./afon.sh -a build -bp acceptance -c <cloud> # builds components running acceptance tests for cloud <cloud>
```
## Acceptance test flow

The `AbstractAccessSpec` demonstrates the full lifecycle:

1. **Create catalog** — POST to `/api/management/v1/catalogs` with cloud-specific JSON
2. **Verify catalog** — GET to confirm Polaris acknowledges it
3. **Grant catalog admin** — PUT to assign admin roles
4. **Create namespace** — `CREATE NAMESPACE IF NOT EXISTS aws.acceptancetests`
5. **Write data** — `spark.createDataFrame(...).writeTo(tableNameFQN).create()`
6. **Read data** — `spark.read.table(tableNameFQN)` and verify row count
7. **Register table** — GET to confirm Polaris knows about the table
8. **Cross-cloud join** — `SELECT * FROM aws_table AS x INNER JOIN gcp_table AS y ON x.id = y.id`

Test specs include:

- [AwsAccessSpec](../afon-acceptance/src/test/scala/health/emrys/afon/acceptance/aws/AwsAccessSpec.scala) — write and read Iceberg tables via AWS Polaris to s3
- [AwsAccessForbiddenSpec](../afon-acceptance/src/test/scala/health/emrys/afon/acceptance/aws/AwsAccessForbiddenSpec.scala) — verify that access to forbidden buckets is denied for AWS
- [AzureAccessSpec](../afon-acceptance/src/test/scala/health/emrys/afon/acceptance/azure/AzureAccessSpec.scala) — write and read via AWS Polaris to Azure
- [AzureAccessForbiddenSpec](../afon-acceptance/src/test/scala/health/emrys/afon/acceptance/azure/AzureAccessForbiddenSpec.scala) — verify that access to forbidden buckets is denied for Azure
- [GcpAccessSpec](../afon-acceptance/src/test/scala/health/emrys/afon/acceptance/gcp/GcpAccessSpec.scala) — write and read via AWS Polaris to GCP
- [GcpAccessForbiddenSpec](../afon-acceptance/src/test/scala/health/emrys/afon/acceptance/gcp/GcpAccessForbiddenSpec.scala) — verify that access to forbidden buckets is denied for GCP
- [AwsGlueExternalTableSpec](../afon-acceptance/src/test/scala/health/emrys/afon/acceptance/aws/AwsGlueExternalTableSpec.scala) — cross-cloud: federated AWS Glue catalog access
- [AzureExternalTableSpec](../afon-acceptance/src/test/scala/health/emrys/afon/acceptance/azure/AzureExternalTableSpec.scala) — cross-cloud: Spark talks to AWS Polaris which federates to Azure Polaris
- [GcpExternalTableSpec](../afon-acceptance/src/test/scala/health/emrys/afon/acceptance/gcp/GcpExternalTableSpec.scala) — cross-cloud: Spark talks to AWS Polaris which federates to GCP Polaris

## Continuous integration

The [Acceptance tests (Linux)](../.github/workflows/acceptance-tests-linux.yml) workflow runs the same
`./afon.sh -a build -bp acceptance` command on the self-hosted Linux runner. It is triggered on push and
pull request to `main`, nightly at 03:00 UTC, and manually via `workflow_dispatch` — the manual trigger
takes a `cloud` input (`all`, `aws`, `azure` or `gcp`) which maps to the `--cloud` flag.

Because the specs write to shared buckets and catalogs, the workflow uses a `concurrency` group so that
only one run executes at a time.

### Runner requirements

The runner must have a Docker daemon reachable by the runner user, since the specs start KeyCloak,
Polaris and Trino via Testcontainers. Java 17 is provisioned by `actions/setup-java`. The runner
itself is installed and registered by [scripts/github/runner.sh](../scripts/github/runner.sh), either
by hand or through the `github` role of the ansible playbook below.

The runner also has to be big enough to hold the workload: the specs run a `local[4]` Spark driver
alongside the four containers, which needs 8GB of memory (16GB comfortably) and 30GB of disk. Below
that the build dies in javac with `OutOfMemoryError: Java heap space` before any spec runs, since the
default max heap is a quarter of the machine's RAM.

The tools the workflow needs — docker, maven, a JDK 17, and the shell tools used by `afon.sh`
(git, curl, unzip, perl, jq) — are installed by the ansible playbook in
[scripts/ansible](../scripts/ansible/README.md):

```shell
cd scripts/ansible && ansible-playbook runner-setup.yml
```

To report what a runner currently has installed and whether it is big enough, without changing it:

```shell
cd scripts/ansible && ansible-playbook runner-setup.yml --tags verify
```

### Config secrets

The workflow reconstructs `$AFON_HOME/config` from repository secrets — one base64-encoded secret per
config file, named after the file:

| Config file               | Secret                    |
|---------------------------|---------------------------|
| `afon.properties`         | `AFON_PROPERTIES`         |
| `afon-aws.properties`     | `AFON_AWS_PROPERTIES`     |
| `afon-azure.properties`   | `AFON_AZURE_PROPERTIES`   |
| `afon-gcp.properties`     | `AFON_GCP_PROPERTIES`     |
| `secret.properties`       | `SECRET_PROPERTIES`       |
| `secret-aws.properties`   | `SECRET_AWS_PROPERTIES`   |
| `secret-azure.properties` | `SECRET_AZURE_PROPERTIES` |
| `secret-gcp.properties`   | `SECRET_GCP_PROPERTIES`   |
| `catalogs.properties`     | `CATALOGS_PROPERTIES`     |
| `catalogs-aws.properties` | `CATALOGS_AWS_PROPERTIES` |
| `catalogs-azure.properties` | `CATALOGS_AZURE_PROPERTIES` |
| `catalogs-gcp.properties` | `CATALOGS_GCP_PROPERTIES` |
| `users.csv`               | `USERS_CSV`               |
| `users-acl.csv`           | `USERS_ACL_CSV`           |
| `whitelist.yaml`          | `WHITELIST_YAML`          |

base64 is used so that multi-line values such as `SNOWFLAKE_PRIVATE_KEY` and
`GOOGLE_APPLICATION_CREDENTIALS` survive the round trip byte for byte.

The `USER_ID` in `secret.properties` must name one of the users in `users.csv`, which the tests authenticate as,
with its password read from there.  Check it with `./afon.sh -a config-user`
(see [config-user](configuration.md#command-config-user)) before publishing.

Publish them from a local config dir using action `github-secrets`:
```shell
./afon.sh -a github-secrets
```
which needs the `gh` CLI (see [gh prerequisites](#gh-prerequisites)):

```shell
./afon.sh -a github-secrets -- push --dry-run # reports what would be published
./afon.sh -a github-secrets push               # publishes all config files, overwriting existing secrets
./afon.sh -a github-secrets list               # lists the secrets set on the repo
```

The script args are given as positional args after the action.  Any that starts with `-` needs a `--` before it, as
above, so that `afon.sh` does not take it for one of its own options.  They can also be given in one quoted string with
`-sa`, eg `-sa "push --dry-run"`.

Empty config files (eg an unused `catalogs-<cloud>.properties`) get no secret, as GitHub secrets cannot be empty.

#### gh prerequisites

The action drives the GitHub CLI, so it needs:

- **A recent `gh` on the `PATH`**, as it uses `gh secret`, `gh variable` and `gh api`.  The `gh variable` commands are
  the newer ones, so an old `gh` fails on the bundle (see [Retrieving the config](#retrieving-the-config)).
- **A login**: `gh auth login`, or a `GH_TOKEN` env variable for non-interactive use.  Check it with `gh auth status`.
  A classic token needs the `repo` scope.  A fine-grained token needs read and write on the repo permissions
  Secrets, Variables and Administration, the latter to create the GitHub environment.  If the org enforces SAML SSO,
  the token must also be authorized for it.
- **Admin access to the repo**, to create the GitHub environment on the first `push --env <env>`.  Once it exists, write
  access is enough to set its secrets and variables.
- **A plan that supports environments** when using `--env`: GitHub environments are available for public repos, but
  private repos need a paid plan (Pro, Team or Enterprise).  Without one, `--env` fails at the environment step,
  whereas `push` to the repository secrets still works.
- **`openssl` with PBKDF2 support** (OpenSSL 1.1.1+ or LibreSSL 3.1+, which includes the one that comes with macOS) and a
  passphrase, for the bundle only.  `push --no-bundle` needs neither.

The target repo does not depend on the current dir, so the action works from any dir, including the cli dir that
`afon-cli.sh` runs from.  It is the first of: option `--repo <owner/repo>`, env variable `GH_REPO`, else
`emrys-health/afon`, and is reported as `Using repo ...`.  A fork needs to give its own.

The quickest check that the login, scopes and repo access are fine is the read-only action `list`:
```shell
./afon.sh -a github-secrets -- list --env <env>
```
which does not check the creation of the environment, which only happens on the first `push` to it.

#### Config envs

Without an env, `push` publishes whatever the `config` dir currently points to as **repository** secrets, so
there is room for only one config.  To hold several [config envs](configuration.md#config-env), publish each
`config-<env>` dir to the [GitHub environment](https://docs.github.com/en/actions/managing-workflow-runs-and-deployments/managing-deployments/managing-environments-for-deployment)
of the same name, which is created if needed (this needs admin access to the repo):

```shell
./afon.sh -a github-secrets -- push --env <env>  # publishes config-<env> as secrets of environment <env>, whichever env is active
./afon.sh -a github-secrets -- list --env <env>  # lists the secrets of environment <env>
./afon.sh -a github-secrets -- delete --env <env>  # removes the secrets and the bundle
```
The secrets keep the names in the table above.  The env can also be given by the global `--config-env`, `--env` or
`AFON_ENV` of `afon.sh`, but note that these also switch the `config` symlink to that env, whereas the `--env` after
`--` is the script's own and leaves the active env alone.

The workflows have a `config_env` input, which runs the job in the GitHub environment of that name.  Its
secrets take precedence over the repository ones of the same name, so anything not published to the environment
(eg the `AWS_DEPLOY_*` credentials) falls back to the repository secret.  Left blank, as on `push` triggers,
the job uses the repository secrets, as before.  The AWS stack workflow keeps a separate tofu state per env.

#### Retrieving the config

GitHub secrets are write-only, so the config cannot be read back from them.  To let a dev pull a config env onto
their machine, `push` also stores all the config files as one encrypted bundle in the GitHub variable
`AFON_CONFIG_BUNDLE` (of the environment, or of the repo if no env is given).  `pull` reads it and decrypts it,
and needs neither the workflows nor the secrets:

```shell
export AFON_SECRETS_PASSPHRASE=...                          # or leave it unset to be prompted
./afon.sh -a github-secrets -- push --env <env>          # publishes the secrets and the bundle
./afon.sh -a github-secrets -- pull --env <env>          # writes the files to config-<env>
./afon.sh -a github-secrets -- pull --env <env> --force  # overwrites the files if the config dir is not empty
./afon.sh -a github-secrets -- push --env <env> --no-bundle  # publishes the secrets only
```
The bundle is a tar of the config files, encrypted with AES-256 under the passphrase (PBKDF2, 600000 iterations).
Unlike secrets, variables can be read by anyone with read access to the repo, so **the bundle is only as safe as
its passphrase**: `push` insists on at least 16 characters, and it should be random, kept in a password manager and
shared with the team out of band.  Anyone who has the passphrase can read the config, and changing it means pushing
again.  The bundle includes the files that are empty, which have no secret, so `pull` restores the config dir as it
was.  `pull` only takes plain files from the bundle.  Both need `openssl` with PBKDF2 support (OpenSSL 1.1.1+ or
LibreSSL 3.1+, which includes the one that comes with macOS).

The workflow writes the config into `$GITHUB_WORKSPACE/.afon/config` rather than the runner's home, so
that it is scoped to the run, and deletes it again in an `always()` step.

