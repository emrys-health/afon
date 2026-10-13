# Smoke tests

The smoke tests check a deployed stack end to end, as a data scientist uses it: from a Jupyter notebook, through
Spark Connect, to Polaris, authenticated with the user's own OIDC token.

The smoke tests run against a real deployment (currently the AWS full stack), so they also cover the deployed Spark Connect server, Keycloak and the Polaris grants configured from `users-acl.csv`.
They complement the [acceptance tests](acceptance_tests.md), which run against local containers.

## What is tested

The shared Spark Connect server registers no catalogs and holds no Polaris credentials. Each Spark session declares
the catalogs it uses, authenticated with the user's OIDC token, and Polaris authorises that user through their own
principal role (see [architecture](architecture.md#spark-integration)).

The notebook [SparkConnectOidcTest.ipynb](../smoke/notebooks/SparkConnectOidcTest.ipynb) checks that, each check
being an assertion, so that the run fails on any regression:

| Check                                                                                                                                                                   | Expected                                   |
|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------|
| 1. The user's token reads a table in a catalog they are authorised for                                                                                                  | rows are read                              |
| 2. A session that declares no catalogs uses `aws`, `aws_forbidden` or `federatedCatalogName`, the default catalogs the server previously used to register for all users | `CATALOG_NOT_FOUND`: the server no longer provides them |
| 3. A catalog with an invalid token, or with no token at all                                                                                                             | `NotAuthorizedException`                   |
| 4. A valid token on a catalog the user is not authorised for (`aws_forbidden`)                                                                                          | `ForbiddenException` (403)                 |

Check 2 switches to each catalog by name (`spark.catalog.setCurrentCatalog`), as referring to a table in a catalog
Spark does not know would be read as a namespace in its default catalog, with a misleading error.

Check 4 lists the catalog's namespaces rather than reading a table: Polaris answers 404 for a table that does not
exist before checking privileges, so reading a table in an empty catalog would not test the authorisation.

## Requirements

- A deployed AWS full stack, and a `kubectl` context pointing at its cluster.
- In `$AFON_HOME/config` (default `~/.afon/config`): `AWS_POLARIS_DOMAIN_NAME`, `OIDC_DOMAIN_NAME`,
  and `USER_ID`, naming one of the users in [users.csv](configuration.md), whose password is read from there.
  The test runs as the `USER_ID` user, which you can check with `./afon.sh -a config-user`
  (see [config-user](configuration.md#command-config-user)).
- [users-acl.csv](configuration.md) authorising the test user for `AFON_CATALOG` (default `aws`), but not for
  `AFON_FORBIDDEN_CATALOG` (default `aws_forbidden`). Other users may be authorised for any catalog, as each user has
  their own Polaris principal role. For example, testing as `will`:

  ```
  mauro,all
  phil,all
  will,aws:aws_trino:azure:gcp
  ```

  After changing the ACL, apply it to Polaris:

  ```shell
  ./afon.sh -a configure-users aws all
  ```

- `AFON_CATALOG` containing the table `AFON_TABLE` (default `namespaceph.test_table`, created at setup).
- `curl`, `python3` and [uv](https://docs.astral.sh/uv/): the notebook runs in the common Python environment
  `scripts/python/.venv`, which is created and synced on first use (it includes `pyspark-client`).

## Running from your machine

```shell
smoke/run-oidc-notebook.sh [cloud]
```

`cloud` defaults to `aws`, and selects the Polaris domain from `<CLOUD>_POLARIS_DOMAIN_NAME`. The script:

1. reads `<CLOUD>_POLARIS_DOMAIN_NAME` and `OIDC_DOMAIN_NAME` from `$AFON_HOME/config/*.properties`, for the notebook;
2. checks that OIDC provider is serving requests, with `./afon.sh -a check-oidc`, failing fast if it is not;
3. gets the OIDC access token of `USER_ID` from OIDC provider, with `./afon.sh -a show-oidc-token <cloud> user`;
4. port-forwards `pod/spark-connector-0-driver` on port 15002, via the current `kubectl` context;
5. runs the notebook with `./afon.sh -a jupyter-execute` (see [scripts/jupyter/execute.sh](../scripts/jupyter/execute.sh));
6. stops the port-forward, whether the run passed or failed.

The executed notebook, with its outputs, is written to `smoke/target/SparkConnectOidcTest.ipynb`, leaving the original
clean. The script exits non-zero if any check fails. The token is never printed.

To get the same token yourself, eg to run the notebook some other way, use:

```shell
./afon.sh -a show-oidc-token aws user
```

Without `user`, `show-oidc-token` gives the token of the Polaris admin (`POLARIS_ID`) instead, which is not what
the smoke test needs: the admin is not one of the users whose access `users-acl.csv` configures.

## Running in JupyterHub

Open the notebook in a user's Jupyter pod and run all cells. `OIDC_TOKEN` is already injected into the pod by
JupyterHub, and the defaults apply: Spark Connect at `sc://spark-connector-0-driver-svc:15002`. This tests the logged
in user, so their ACL must meet the requirements above.

## Settings

The notebook reads these environment variables, all optional apart from `OIDC_TOKEN`:

| Variable                 | Default                                   |
|--------------------------|-------------------------------------------|
| `OIDC_TOKEN`             | (required) the user's access token        |
| `SPARK_REMOTE`           | `sc://spark-connector-0-driver-svc:15002` |
| `POLARIS_DOMAIN_NAME`    | `emryspolaris.click`                      |
| `OIDC_DOMAIN_NAME`       | `emrysoauth.click`                        |
| `AFON_CATALOG`           | `aws`                                     |
| `AFON_FORBIDDEN_CATALOG` | `aws_forbidden`                           |
| `AFON_TABLE`             | `namespaceph.test_table`                  |

`smoke/run-oidc-notebook.sh` sets the first four from your configuration, and `AFON_CATALOG` and `AFON_FORBIDDEN_CATALOG` from the
cloud it is given, eg `azure` and `azure_forbidden` for `smoke/run-oidc-notebook.sh azure`, unless they are set already.  The defaults
in the table, `aws` and `aws_forbidden`, are those of the notebook when run on its own.  The table `AFON_TABLE` is written by the
setup of the stack to the catalog of its own cloud, so it is not in the `aws` catalog of the Polaris of another cloud.

## Troubleshooting

- **`kubectl` fails with `Unauthorized`, or the port-forward cannot start**: check `kubectl config current-context`
  points at the stack's cluster. Do not source the secrets files into your shell before running: they also export
  cloud credentials, which change the identity `kubectl` uses.
- **Check 1 fails**: the test user is not authorised for `AFON_CATALOG`, or the table does not exist.
- **Check 2 fails**: the Spark Connect server still registers catalogs of its own. The Spark Connect manifest is
  applied only when the stack is created, so a stack deployed before these changes keeps its old configuration until
  it is redeployed, or until the `SparkApplication` is re-applied (see *Problems starting Spark Connect* in the
  generated setup documents).
- **Check 4 fails**: the test user is authorised for `AFON_FORBIDDEN_CATALOG`, either in `users-acl.csv` or because
  the ACL has not been applied to Polaris since it changed (`./afon.sh -a configure-users aws all`).

## Regression suite

```shell
smoke/run-regression-suite.sh [--cli] [--env <env>] [--setup <stacks> [--teardown]] [--suite <file>]
                              [--tests <tests>] [--logging-console <level>] [--list] [<cloud>]
```

runs the tests listed in a suite file, by default [smoke/regression-full.csv](../smoke/regression-full.csv), in
order, each as an afon command, against the full stack of the `<cloud>`, `aws` by default, or `azure`: it sets the kubectl
context to the cluster of that stack, with the afon command `<cloud>-update-kube-config full`, and runs the tests with
`TRE_CLOUD=<cloud>`, as the cloud of the stack hosting the TRE, whatever the config says.  The tests are not specific to a
cloud, so the same file serves both:

```shell
smoke/run-regression-suite.sh azure
```

**NOTE:** the Polaris ingress is only restricted on AWS, by the ALB, to the TRE's egress IP, so on Azure the `network` test,
which checks that Polaris cannot be reached from outside of the cluster, is expected to fail.

The first line of the suite file is the header `name|command|args`, and each other line a test: its
name, its afon command, eg `network-smoke` for the ops class `NetworkSmokeMain`, and the command's args, if any,
separated by spaces. Fields are separated by `|`, rather than commas, so that args may contain commas. Blank lines and
lines starting with `#` are ignored. For example:

```
name|command|args
network|network-smoke|
spark-connect|spark-connect-smoke|
```

The suite runs every afon command, the tests' as well as those setting up, tearing down or reaching the stacks, with
`afon.sh`, as `./afon.sh -a <command> -- <args>`, or with `afon-cli.sh`, as `afon-cli.sh <command> <args>`, given
`--cli`. With `afon.sh`, the commands are checked when the suite file is read, against the afon-ops `*Main` classes of
the source tree, as `afon.sh` exits zero on an unknown command. With `--cli`, `afon-cli.sh` resolves them in its JVM,
independently of the source tree, and a test whose command is unknown fails.

- `--cli` runs the afon commands with `afon-cli.sh`: that of the cli distribution in `$AFON_CLI_DIR`, if set, else in
  `$AFON_HOME/cli`. It is implied when the suite runs from a cli distribution (see [Afon CLI](afon_cli.md#smoke-tests)),
  which has no `afon.sh`, the default `afon-cli.sh` then being that of the distribution.
- `--env` selects the config and cdktf envs, as `./afon.sh --env <env>` does, for every command the suite runs. As
  the env switch re-points the shared `$AFON_HOME/config` and `$AFON_HOME/cdktf` symlinks, do not run another afon
  command on a different env while the suite runs. Without `--env`, the suite uses the env of the current config.
- `--setup` sets up, ie deploys and configures, the comma-separated stacks first, in order, with the afon command
  `manage-stack setup <stack>`, eg `--setup aws-full,azure-polaris,gcp-polaris`. The tests are not run if a setup
  fails. The stacks, including one whose setup failed part way, are left up, and the commands deleting them are
  printed on exit, unless `--teardown` is given.
- `--teardown` tears down the set up stacks on exit, in reverse order, with the afon command
  `manage-stack delete <stack>`, whether the tests passed or failed, or a setup failed. The suite fails if a teardown
  fails, naming the stack to delete. The stacks are never torn down if the suite is interrupted, eg with Ctrl-C: that
  may stop a setup part way, with its tofu state still locked, so the commands deleting them are printed instead.
- `--suite` reads the tests from another file, of the same format, eg a subset of the tests for a single cloud.
- `--tests` runs only the comma-separated tests, eg `--tests network,spark-connect`. All are run by default.
- `--logging-console` sets the logging console level, one of `OFF|DEBUG|INFO|WARN|ERROR`, of every afon command the
  suite runs, by exporting `AFON_LOGGING_CONSOLE`. It defaults to that of `afon.sh`, `INFO`, or of `afon-cli.sh`,
  `OFF`.
- `--list` lists the tests.

The tests run against the AWS full stack's cluster: the Spark Connect tests read the Azure and GCP catalogs through its
Spark Connect server, so they also need the Azure and GCP Polaris stacks. On every run, after any setups, and whether
`--env` is given or not, the suite:

1. makes the env's AWS full stack cluster the current `kubectl` context, with the afon command
   `aws-update-kube-config full`, leaving it so after the suite;
2. restricts that cluster's Polaris ingress, as the network smoke test expects, by annotating `polaris-ingress` with
   `alb.ingress.kubernetes.io/inbound-cidrs`. As `AwsCloudProvider` does for Polaris when `AWS_POLARIS_ALLOWED_CIDRS` is set, it
   admits those CIDRs plus the cluster's egress IP. The egress IP is read from the cluster's NAT gateway, so that it is
   right even if `AWS_EGRESS_IP` is stale, with a warning if they differ, falling back to `AWS_EGRESS_IP` if the NAT
   gateway cannot be found. A redeploy undoes the annotation, which a freshly deployed stack does not have.

So the results only depend on the env's config and stacks, whoever runs the suite. All the selected tests are run, even if some fail. Their logging goes to the afon log file,
`$AFON_HOME/logs/afon-core.log`, and to the console as set by `--logging-console`. The script ends with a summary, and
exits non-zero if any test failed.

The results of each run are also written to `smoke/target/regression/results-<yyyymmdd-hhmmss>.txt`, without any
logging, so that they can be read even when the console is swamped, eg with `--logging-console INFO`, or a teardown
following the tests: the run's settings, then the times and outcomes of its stack setups, tests and teardowns, and its
summary, and the time the whole run took, including any setup and teardown. The suite prints the file's path as its
very last line, after any teardown. For example:

```
Regression suite run of yyyy-mm-dd hh:mm:ss 
env: emrys
cloud: aws (stack aws-full)
suite: smoke/regression-full.csv
tests: all
stacks set up: aws-full, then torn down
commands run with: ./afon.sh

stack aws-full set up in 17:01
test network passed in 02:11
test spark-connect passed in 00:15
tests: 2 passed, 0 failed
stack aws-full torn down in 07:10
regression suite completed in 27:03
```

## Other smoke tests

- `ops.demo.GeneratePySparkKeycloakMain` and `ops.demo.GeneratePySparkContextMain` generate PySpark code declaring
  all the configured catalogs, authenticated with `OIDC_TOKEN`, to paste into a notebook.
