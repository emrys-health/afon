# Smoke tests

The smoke tests check a deployed stack end to end, as a data scientist uses it: from a Jupyter notebook, through
Spark Connect, to Polaris, authenticated with the user's own OIDC token.

The smoke tests run against a real deployment (the AWS or the Azure full stack), so they also cover the deployed Spark Connect server, Keycloak and the Polaris grants configured from `users-acl.csv`.
They complement the [acceptance tests](acceptance_tests.md), which run against local containers.

## What is tested

The shared Spark Connect server registers no catalogs and holds no Polaris credentials. Each Spark session declares
the catalogs it uses, authenticated with the user's OIDC token, and Polaris authorises that user through their own
principal role (see [architecture](architecture.md#spark-integration)).

The notebook [SparkConnectOidcTest.ipynb](../scripts/smoke/notebooks/SparkConnectOidcTest.ipynb) checks that, each check
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

- A deployed full stack, `aws-full` or `azure-full`, and a `kubectl` context pointing at its cluster.
- In `$AFON_HOME/config` (default `~/.afon/config`): `<CLOUD>_POLARIS_DOMAIN_NAME`, eg `AZURE_POLARIS_DOMAIN_NAME`,
  `<CLOUD>_OIDC_DOMAIN_NAME` or else `OIDC_DOMAIN_NAME`, and `USER_ID`, naming one of the users in [users.csv](configuration.md), whose password is read from there.
  The test runs as the `USER_ID` user, which you can check with `./afon.sh -a config-user`
  (see [config-user](configuration.md#command-config-user)).
- [users-acl.csv](configuration.md) authorising the test user for `AFON_CATALOG` (default `aws`, or the catalog of the
  cloud given to `afon-cli.sh smoke-notebook`, eg `azure`), but not for `AFON_FORBIDDEN_CATALOG` (default `aws_forbidden`, or
  `<cloud>_forbidden`). Other users may be authorised for any catalog, as each user has
  their own Polaris principal role. For example, testing as `will`:

  ```
  mauro,all
  phil,all
  will,aws:aws_trino:azure:gcp
  ```

  After changing the ACL, apply it to Polaris of the cloud:

  ```shell
  ./afon.sh -a configure-users <cloud> all
  ```

- `AFON_CATALOG` containing the table `AFON_TABLE` (default `namespaceph.test_table`, created at setup).
- `curl`, `python3` and [uv](https://docs.astral.sh/uv/): the notebook runs in the common Python environment
  `scripts/python/.venv`, which is created and synced on first use (it includes `pyspark-client`).
  `afon-cli.sh smoke-notebook` sets it up and checks it first, with `afon-cli.sh python-setup`, which can also be run on its own: it
  fails early, saying so, if `uv` is not installed, or the env has no `python`, `jupyter` or the ipykernel `afon`.

## Running from your machine

```shell
afon-cli.sh smoke-notebook [--env <env>] [<cloud>]
afon-cli.sh smoke-notebook [--env <env>] [<cloud>] oidc
afon-cli.sh smoke-notebook --list
```

which runs all the notebooks listed in `scripts/smoke/notebooks.csv`, or only those named, separated by spaces or commas, eg `oidc`. All are run even if some fail, and the command fails if any does. The names are those listed in [scripts/smoke/notebooks.csv](../scripts/smoke/notebooks.csv), the file of the notebooks, `name|file|description`, with the files in `scripts/smoke/notebooks`.  The notebooks are read from that file by default; `--notebooks <file>` reads those of another CSV file instead.

`--env` selects the afon env, so that the domain names, and the config used by every afon command it runs, are those of `$AFON_HOME/config-<env>`; it defaults to `AFON_ENV`, else the current config.

The command can also be run with `./afon.sh -a smoke-notebook [<cloud>] oidc`.  It runs its afon commands with `afon.sh` from the source tree, else with the `afon-cli.sh` of the distribution it runs from, or of `AFON_CLI_DIR`.

`cloud` is `aws`, `azure` or `gcp` (default: `aws`), given first, and selects the Polaris domain from `<CLOUD>_POLARIS_DOMAIN_NAME` and the catalogs `<cloud>` and
`<cloud>_forbidden`, eg `azure` and `azure_forbidden`. The script:

1. reads `<CLOUD>_POLARIS_DOMAIN_NAME` and `<CLOUD>_OIDC_DOMAIN_NAME` (else `OIDC_DOMAIN_NAME`) from `$AFON_HOME/config/*.properties`, for the notebook;
2. checks that OIDC provider is serving requests, with `./afon.sh -a check-oidc <cloud>`, failing fast if it is not;
3. gets the OIDC access token of `USER_ID` from OIDC provider, with `./afon.sh -a check-oidc <cloud> --token user`;
4. port-forwards `pod/spark-connector-0-driver` on port 15002, via the current `kubectl` context;
5. runs the notebook with `./afon.sh -a jupyter-execute` (see [scripts/jupyter/execute.sh](../scripts/jupyter/execute.sh));
6. stops the port-forward, whether the run passed or failed.

The executed notebook, with its outputs, is written to `smoke/target/SparkConnectOidcTest.ipynb`, leaving the original
clean. The script exits non-zero if any check fails. The token is never printed.

To get the same token yourself, eg to run the notebook some other way, use:

```shell
./afon.sh -a check-oidc <cloud> --token user
```

Without `user`, `--token` gives the token of the Polaris admin (`POLARIS_ID`) instead, which is not what
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

`afon-cli.sh smoke-notebook` sets the first four from your configuration, and `AFON_CATALOG` and `AFON_FORBIDDEN_CATALOG` from the
cloud it is given, eg `azure` and `azure_forbidden` for `afon-cli.sh smoke-notebook azure oidc`, unless they are set already.  The defaults
in the table, `aws` and `aws_forbidden`, are those of the notebook when run on its own.  The table `AFON_TABLE` is written by the
setup of the stack to the catalog of its own cloud, so it is not in the `aws` catalog of the Polaris of another cloud.

## Troubleshooting

- **`kubectl` fails with `Unauthorized`, or the port-forward cannot start**: check `kubectl config current-context`
  points at the stack's cluster. Do not source the secrets files into your shell before running: they also export
  cloud credentials, which change the identity `kubectl` uses.
- **Check 1 fails**: the test user is not authorised for `AFON_CATALOG`, or the table does not exist.  With
  `TABLE_OR_VIEW_NOT_FOUND` for ``test_catalog`.`namespaceph`.`test_table` `` the notebook is reading another catalog than
  the one the stack's setup wrote the table to, which is that of its own cloud: run it through
  `afon-cli.sh smoke-notebook <cloud> oidc`, or set `AFON_CATALOG` and `AFON_FORBIDDEN_CATALOG`.
- **Spark fails with `UnknownHostException: <polaris domain>: Temporary failure in name resolution`**: the network policies
  only let the Spark driver resolve the names in `whitelist.yaml`, so list the Polaris and Jupyter domain names and the
  storage account of the cloud in its section there (see [Setup Azure full stack](stacks.md#setup-azure-full-stack)).
- **Check 2 fails**: the Spark Connect server still registers catalogs of its own. The Spark Connect manifest is
  applied only when the stack is created, so a stack deployed before these changes keeps its old configuration until
  it is redeployed, or until the `SparkApplication` is re-applied (see *Problems starting Spark Connect* in the
  generated setup documents).
- **Check 4 fails**: the test user is authorised for `AFON_FORBIDDEN_CATALOG`, either in `users-acl.csv` or because
  the ACL has not been applied to Polaris since it changed (`./afon.sh -a configure-users <cloud> all`).
- **The `network` test fails with `Managed to make an HTTP call to <polaris domain>`**: the machine running it is allowed to
  reach Polaris, because its public address is in `AWS_POLARIS_ALLOWED_CIDRS` or `AZURE_POLARIS_ALLOWED_CIDRS`. Remove it from
  there (see [the machine running the test](#the-machine-running-the-test-must-be-outside-the-allowed-cidrs)).

## Regression suite

```shell
afon-cli.sh smoke-test [--cli] [--env <env>] [--setup <stacks> [--teardown]] [--suite <file>]
                          [--tests <tests>] [--logging-console <level>] [--list] [<cloud>]
```

runs the tests listed in a suite file, by default [scripts/smoke/tests-full.csv](../scripts/smoke/tests-full.csv), in
order, each as an afon command, against the full stack of the `<cloud>`, `aws` by default, or `azure`: it sets the kubectl
context to the cluster of that stack, with the afon command `<cloud>-update-kube-config tre`, ie of the stack with the role `tre`, and runs the tests with
`TRE_CLOUD=<cloud>`, as the cloud of the stack hosting the TRE, whatever the config says.  The tests are not specific to a
cloud, so the same file serves both:

```shell
afon-cli.sh smoke-test azure
```

It can also be run with `./afon.sh -a smoke-test azure`.

**NOTE:** the `network` test, which checks that Polaris cannot be reached from outside of the cluster, passes on both clouds if the stack's
Polaris ingress is restricted, ie deployed with `AWS_POLARIS_ALLOWED_CIDRS` (`AZURE_POLARIS_ALLOWED_CIDRS` on Azure) set, see
[the network test on AWS and Azure](#the-network-test-on-aws-and-azure). The suite changes nothing in the cluster: if the ingress is not
restricted, it warns, and prints the `kubectl` commands that restrict it. `--restrict-polaris` runs them for the duration of the suite.

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
  `$AFON_HOME/cli`. It is implied when the suite runs from a cli distribution (see [Afon CLI](afon_cli.md#smoke-test)),
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
- `--restrict-polaris` restricts the Polaris ingress for the run, so that the `network` test passes on a stack whose deploy did not
  restrict it. It changes the cluster, so it is opt-in, and it restores it on exit, see below. Ignored if no selected test is the
  network smoke test.
- `--list` lists the tests.

The tests run against the full stack's cluster of the `<cloud>`: the Spark Connect tests read the catalogs of the clouds
through its Spark Connect server, so they also need the Polaris stacks of the other clouds. On every run, after any
setups, and whether `--env` is given or not, the suite:

1. makes the env's full stack cluster of the `<cloud>` the current `kubectl` context, with the afon command
   `<cloud>-update-kube-config tre`, ie of the stack with the role `tre`, leaving it so after the suite;
2. if a selected test is the network smoke test, checks, without changing the cluster, that the Polaris ingress of that cluster is
   restricted, ie has the annotation `alb.ingress.kubernetes.io/inbound-cidrs` (AWS) or
   `nginx.ingress.kubernetes.io/whitelist-source-range` (Azure), which on Azure only works with `externalTrafficPolicy: Local` on the
   nginx controller's service, as that keeps the callers' IPs. If it is not, the suite warns that the `network` test is expected to
   fail, and prints the `kubectl` commands that restrict the ingress, for you to run, with the CIDRs to restrict it to: the
   configured ones, plus the cluster's own addresses, so that its pods calling Polaris by its public domain name are not locked out.
   These are, on AWS, the cluster's egress IP, read from its NAT gateway, as `AWS_EGRESS_IP` may be stale, falling back to
   `AWS_EGRESS_IP`; on Azure, the pod CIDR and the outbound IP, read from the cluster's load balancer (needs `az` logged in), falling back to
   `AZURE_EGRESS_IP` if it is not found, and warning if the two differ, as on AWS. The
   commands only last until the next deploy, which is why the permanent fix is to deploy with the property set. The tests run either
   way.

With `--restrict-polaris` the suite runs those commands itself instead, after recording in a new
`smoke/target/regression/polaris-ingress-snapshot-<yyyymmdd-hhmmss>.txt`, named like the results files, how the ingress is: its
annotation, present or absent, and on Azure the controller's `externalTrafficPolicy`. When the script exits, however it exits (it fails,
is interrupted, or finishes), it restores exactly that, before any `--teardown`, whatever the properties say: a hand-restricted ingress gets
its own list back, and an unrestricted one loses the annotation. It leaves the ingress alone if someone changed the annotation since the suite
set it, and does nothing if the ingress is gone.

The snapshot file is kept as a record of the run: the suite adds an `outcome=` line, `restored`, or `not restored` with the reason, and
a `finished=` line. A file without an `outcome=` is **pending**: the run did not restore the ingress, because restoring failed (the script
warns, and keeps its exit status), or because it was killed, eg with `kill -9`. The next run with `--restrict-polaris` restores every pending
file first, oldest first, and stops if that fails.

#### The network test on AWS and Azure

The `network` test (`network-smoke`) runs the same three checks on both clouds, but how the first is satisfied differs:

| | AWS | Azure |
|---|---|---|
| Polaris ingress | ALB | nginx ingress controller, in the cluster, behind a public load balancer |
| Restricted by | `alb.ingress.kubernetes.io/inbound-cidrs`, from `AWS_POLARIS_ALLOWED_CIDRS` | `nginx.ingress.kubernetes.io/whitelist-source-range`, from `AZURE_POLARIS_ALLOWED_CIDRS`, with `externalTrafficPolicy: Local` on the controller's service |
| Always admitted | the cluster's egress IP (its NAT gateway) | the cluster's pod CIDR, and, with `--restrict-polaris`, its outbound IP |
| A caller outside of them | the ALB drops its packets: the call fails or times out | nginx answers with its own `403 Forbidden` page, without calling Polaris |
| The test passes when | the call fails or times out | the call times out, or gets nginx's own 403; any other response, eg a 403 from Polaris, fails it |

So on Azure a quick 403 is the expected outcome of check 1, not a failure, whereas on AWS any HTTP response is one.

##### The machine running the test must be outside the allowed CIDRs

Check 1 calls Polaris **from the machine running the test**, and passes only if that call is stopped. So the machine's public
address must not be among the CIDRs of the ingress, which are `AWS_POLARIS_ALLOWED_CIDRS` (`AZURE_POLARIS_ALLOWED_CIDRS` on
Azure) plus the cluster's egress IP (AWS) or pod CIDR and outbound IP (Azure). If it is, Polaris answers the call and the test fails
with `Managed to make an HTTP call to <polaris domain>. Received HTTP code <code>`, whatever that code is.

`manage-stack setup` of a stack with Polaris always ends by restricting it without the machine that ran it (the last step,
`whitelist_polaris_for_<cloud>`, which restores the restriction, whether or not the machine was admitted for the setup, see
[reaching Polaris from the setup steps](cluster.md#reaching-polaris-from-the-setup-steps)), so the test passes after a setup, unless the
machine's address is among the configured CIDRs.

What happens to the machine's own access to Polaris afterwards depends on how the ingress is restricted:

| | Not restricted | Restricted by the deploy, without the machine's address | With the machine's address | `--restrict-polaris` |
|---|---|---|---|---|
| `network` test | **fails**, with the commands to restrict it | passes | **fails** | passes |
| During the suite | open | restricted | restricted, machine admitted | restricted to the cluster's own addresses plus the property |
| After the suite | open, as before | restricted, as before | as before | as it was before the run |

So to run the `network` test from a machine and still reach Polaris from it afterwards, use `--restrict-polaris` on a stack that is
not restricted, or leave the property unset or without that machine's address. If a Polaris that should be reachable from your machine
times out after a run, check `kubectl get ingress polaris-ingress -n default -o yaml` for the annotation, and whether
a `smoke/target/regression/polaris-ingress-snapshot-*.txt` has no `outcome=` line, which means a run did not restore it: the next run with
`--restrict-polaris` does, or remove the annotation, for example on AWS:

```shell
kubectl annotate ingress polaris-ingress -n default alb.ingress.kubernetes.io/inbound-cidrs-
```

The other checks are cloud-neutral, as the network policies are the same. The Spark driver's allowed-domains check curls each name
in the common and TRE cloud sections of the current `whitelist.yaml`, not those of the other clouds, which only resolve while their
stack is deployed. So the test passes on either cloud whatever the other clouds' stacks, but a stack deployed before the list
changed fails it until it is redeployed, as its network policies hold the old list.

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
suite: scripts/smoke/tests-full.csv
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
