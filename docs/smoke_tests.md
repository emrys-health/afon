# Smoke tests

The smoke tests check a deployed stack end to end, as a data scientist uses it: from a Jupyter notebook, through
Spark Connect, to Polaris, authenticated with the user's own OIDC token.

The smoke tests run against a real deployment (the AWS, Azure or GCP full stack), so they also cover the deployed Spark Connect server, Keycloak and the Polaris grants configured from `users-acl.csv`.
They complement the [acceptance tests](acceptance_tests.md), which run against local containers.

## What is tested

The shared Spark Connect server registers no catalogs and holds no Polaris credentials. Each Spark session declares
the catalogs it uses, authenticated with the user's OIDC token, and Polaris authorises that user through their own
principal role (see [architecture](architecture.md#spark-integration)).

The notebook [spark-connect-oidc-test.ipynb](../scripts/smoke/notebooks/spark-connect-oidc-test.ipynb) checks that, each check
being an assertion, so that the run fails on any regression:

| Check                                                                                                                                                                   | Expected                                   |
|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------|
| 1. The user's token reads a table in a catalog they are authorised for                                                                                                  | rows are read                              |
| 2. A session that declares no catalogs uses `aws`, `aws_forbidden` or `federatedCatalogName`, the default catalogs the server previously used to register for all users | `CATALOG_NOT_FOUND`: the server no longer provides them |
| 3. A catalog with an invalid token, or with no token at all                                                                                                             | `NotAuthorizedException`                   |
| 4. A valid token on a catalog the user is not authorised for (`aws_forbidden`)                                                                                          | `ForbiddenException` (403)                 |

Check 1 reads the table that the `spark-polaris` smoke test writes to the stack's own catalog, so that test must have been run on the
stack first: the setup of a stack no longer writes it.

Check 2 switches to each catalog by name (`spark.catalog.setCurrentCatalog`), as referring to a table in a catalog
Spark does not know would be read as a namespace in its default catalog, with a misleading error.

Check 4 lists the catalog's namespaces rather than reading a table: Polaris answers 404 for a table that does not
exist before checking privileges, so reading a table in an empty catalog would not test the authorisation.

## Requirements

- A deployed full stack, `aws-full`, `azure-full` or `gcp-full`, and its kubeconfig, `$AFON_CDKTF/kube/<stack-id>.config`, given by `KUBECONFIG` (or by the id of the stack, `--stack <stack-id>` of `afon-cli.sh`, eg `--stack aws-full`, or `AFON_STACK=<stack-id>`), and, if none is given, that of the stack deployed last, `$AFON_CDKTF/kube/current.config`. `~/.kube/config` is not used. `afon-cli.sh <cloud>-update-kube-config tre` writes it and prints the `export`. `smoke-notebook` runs that command itself if `KUBECONFIG` is not set.
- In `$AFON_HOME/config` (default `~/.afon/config`): `<CLOUD>_POLARIS_DOMAIN_NAME`, eg `AZURE_POLARIS_DOMAIN_NAME`,
  `<CLOUD>_OIDC_DOMAIN_NAME` or else `OIDC_DOMAIN_NAME`, and `USER_ID`, naming one of the users in [users.csv](configuration.md), whose password is read from there.
  The test runs as the `USER_ID` user, which you can check with `./afon.sh -a config-user`
  (see [config-user](configuration.md#command-config-user)).
- [users-acl.csv](configuration.md) authorising the test user for `AFON_CATALOG` (default `aws`, or the catalog of the
  cloud given to `afon-cli.sh smoke-notebook`, eg `azure`), but not for `AFON_FORBIDDEN_CATALOG` (default `aws_forbidden`, or
  `<cloud>_forbidden`). Other users may be authorised for any catalog, as each user has
  their own Polaris principal role. For example, testing as `will`:

  ```
  phil,all
  will,aws:aws_trino:azure:gcp
  ```

  After changing the ACL, apply it to Polaris of the cloud:

  ```shell
  ./afon.sh -a configure-users <cloud> all
  ```

- `AFON_CATALOG` containing the table `AFON_TABLE` (default `admin.deployments`). The setup of a stack no longer writes it: it is written by the
  `spark-polaris` smoke test (see [The Spark write test](#the-spark-write-test)), so run that first, eg
  `afon-cli.sh smoke-test <cloud> --tests spark-polaris`, or `afon-cli.sh spark-polaris-smoke`. Without it, check 1 fails, as the table does not exist.
- `curl`, `python3` and [uv](https://docs.astral.sh/uv/), for `--local` only: the notebook then runs in the common Python environment
  `scripts/python/.venv`, which is created and synced on first use (it includes `pyspark-client`).
  `afon-cli.sh smoke-notebook` sets it up and checks it first, with `afon-cli.sh python-setup`, which can also be run on its own: it
  fails early, saying so, if `uv` is not installed, or the env has no `python`, `jupyter` or the ipykernel `afon`.

## Running the notebooks

```shell
afon-cli.sh smoke-notebook [--env <env>] [--local] [<cloud>]
afon-cli.sh smoke-notebook [--env <env>] [--local] [<cloud>] oidc
afon-cli.sh smoke-notebook --list
```

which runs all the notebooks listed in `scripts/smoke/notebooks.csv`, or only those named, separated by spaces or commas, eg `oidc`. All are run even if some fail, and the command fails if any does. The names are those listed in [scripts/smoke/notebooks.csv](../scripts/smoke/notebooks.csv), the file of the notebooks, `name|file|description`, with the files in `scripts/smoke/notebooks`.  The notebooks are read from that file by default; `--notebooks <file>` reads those of another CSV file instead.

By default the notebooks run in a notebook pod, as a user's do, so that they use the pod's own python and Spark Connect client, its network policy and its
certificate of Trino. The afon command `user-pod-notebook-smoke` does it, and writes the executed notebooks to `$AFON_HOME/smoke/notebooks`:

- In non-project mode, the pod is that of `USER_ID`: JupyterHub is asked, through its API, to start it. It then has no token of a sign-in, as the hub puts one in
  a pod only when the user signs in, so the token that Keycloak gives the user's client is given to the notebooks as `OIDC_TOKEN`, on the standard input of the command that
  runs them, so that it is in no command line. They are also told `POLARIS_DOMAIN_NAME`, `OIDC_DOMAIN_NAME`, `AFON_CATALOG` and `AFON_FORBIDDEN_CATALOG`, as they are from your machine.
- In project mode, the pod is that of the smoke member, signed in to the hub, see [Projects](projects.md#a-first-tre-project).

`--local` runs the notebooks from your machine instead, with the common python environment described under Requirements, and, in a project, with the smoke member's token from
Keycloak and port-forwards to the TRE's Spark Connect and Trino. It is quicker and needs no pod, but it does not test the pod.

`--env` selects the afon env, so that the domain names, and the config used by every afon command it runs, are those of `$AFON_HOME/config-<env>`; it defaults to `AFON_ENV`, else the current config.

The command can also be run with `./afon.sh -a smoke-notebook [<cloud>] oidc`.  It runs its afon commands with `afon.sh` from the source tree, else with the `afon-cli.sh` of the distribution it runs from, or of `AFON_CLI_DIR`.

`cloud` is `aws`, `azure` or `gcp` (default: `aws`), given first, and selects the Polaris domain from `<CLOUD>_POLARIS_DOMAIN_NAME` and the catalogs `<cloud>` and
`<cloud>_forbidden`, eg `azure` and `azure_forbidden`. The script:

1. reads `<CLOUD>_POLARIS_DOMAIN_NAME` and `<CLOUD>_OIDC_DOMAIN_NAME` (else `OIDC_DOMAIN_NAME`) from `$AFON_HOME/config/*.properties`, for the notebook;
2. checks that OIDC provider is serving requests, with `./afon.sh -a check-oidc <cloud>`, failing fast if it is not;
3. gets the OIDC access token of `USER_ID` from OIDC provider, with `./afon.sh -a check-oidc <cloud> --token user`;
4. port-forwards `pod/spark-connector-0-driver` (port 15002) to a free local port that `kubectl` picks, via `KUBECONFIG`, so that notebook runs for different clouds can run at the same time;
5. runs the notebook with `./afon.sh -a jupyter-execute` (see [scripts/jupyter/execute.sh](../scripts/jupyter/execute.sh));
6. stops the port-forward, whether the run passed or failed.

The executed notebook, with its outputs, is written to `$AFON_HOME/smoke/notebooks/spark-connect-oidc-test.ipynb`, leaving the original
clean. The script exits non-zero if any check fails. The token is never printed.

To get the same token yourself, eg to run the notebook some other way, use:

```shell
./afon.sh -a check-oidc <cloud> --token user
```

Without `user`, `--token` gives the token of the Polaris admin (`POLARIS_ID`) instead, which is not what
the smoke test needs: the admin is not one of the users whose access `users-acl.csv` configures.

In a project, `--token smoke-member` gives the token of its smoke member instead, signed in as a member is, with the
project and the MFA in it, which `user` does not have, as it is the admin's client-credentials token. It needs the
member's credentials from `manage-identity smoke-credentials`, see [Projects](projects.md#members):

```shell
afon-cli.sh manage-project-stack run cvd-2026 check-oidc <cloud> --token smoke-member
```

A one-time password is good for one sign-in, so when two sign-ins of the same member are within the same 30 seconds, the second is
refused the code it sends first, and sends one of the next time step.

## Running in JupyterHub

Open the notebook in a user's Jupyter pod and run all cells. `OIDC_TOKEN` is already injected into the pod by
JupyterHub, and the defaults apply: Spark Connect at `sc://spark-connector-0-driver-svc:15002`. This tests the logged
in user, so their ACL must meet the requirements above. The notebook sends `OIDC_TOKEN` to Spark Connect too, in the `x-afon-token` header, and checks that
the server refuses a session without it, or with an invalid one, and refuses `ADD JAR`, `reflect` and global temporary views.

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
  with `KUBECONFIG` set points at the stack's cluster. Do not source the secrets files into your shell before running: they also export
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
  reach Polaris, because its public address is in `AWS_POLARIS_ALLOWED_CIDRS`, `AZURE_POLARIS_ALLOWED_CIDRS` or `GCP_POLARIS_ALLOWED_CIDRS`. Remove it from
  there (see [the machine running the test](#the-machine-running-the-test-must-be-outside-the-allowed-cidrs)).

## Regression suite

```shell
afon-cli.sh smoke-test [--cli] [--env <env>] [--setup <stacks> [--teardown]] [--suite <file>]
                          [--tests <tests>] [--logging-console <level>] [--progress <seconds>] [--list] [<cloud>]
```

runs the tests listed in a suite file, by default [scripts/smoke/tests-full.csv](../scripts/smoke/tests-full.csv), in
order, each as an afon command, against the full stack of the `<cloud>`, `aws` by default, `azure` or `gcp`: it sets `KUBECONFIG` to the kubeconfig of that stack, with the afon command `<cloud>-update-kube-config tre`, ie of the stack with the role `tre`, and runs the tests with `TRE_CLOUD=<cloud>`, as the cloud of the stack hosting the TRE, whatever the config says.  The tests are not specific to a
cloud, so the same file serves all of them:

```shell
afon-cli.sh smoke-test azure
```

It can also be run with `./afon.sh -a smoke-test azure`.

**NOTE:** the `network` test, which checks that Polaris cannot be reached from outside of the cluster, passes on every cloud if the stack's
Polaris ingress is restricted, ie deployed with `AWS_POLARIS_ALLOWED_CIDRS` (`AZURE_` or `GCP_POLARIS_ALLOWED_CIDRS` on Azure and GCP) set, see [the network test on AWS, Azure and GCP](#the-network-test-on-aws-azure-and-gcp). The suite changes nothing in the cluster: if the ingress is not
restricted, it warns, and prints the `kubectl` commands that restrict it.

The first line of the suite file is the header `name|command|args`, and each other line a test: its
name, its afon command, eg `network-smoke` for the ops class `NetworkSmokeMain`, and the command's args, if any,
separated by spaces. Fields are separated by `|`, rather than commas, so that args may contain commas. Blank lines and
lines starting with `#` are ignored. For example:

```
name|command|args
network|network-smoke|
spark-polaris|spark-polaris-smoke|
spark-connect|spark-connect-smoke|
failed-login-loki|failed-login-loki-smoke|
gateway-redirect|gateway-redirect-smoke|
```

The `gateway-redirect` test (`gateway-redirect-smoke`) verifies without signing in that an unauthenticated request to
`https://<Jupyter domain>/` redirects (`302`) to `https://auth.<Jupyter domain>/...` and then to Keycloak with
`redirect_uri=https://auth.<Jupyter domain>/oauth2/callback`. The `failed-login-loki` test (`failed-login-loki-smoke`)
starts a sign-in at `https://<Jupyter domain>/<marker>`, follows the redirects through Pomerium to Keycloak, submits an
invalid login, and verifies that Loki records the marker in Pomerium's (`pomerium-*`) and Keycloak's logs.

`<cloud>` in a test's args is replaced by the `<cloud>` of the stack tested, so that the one file serves every cloud: the last test of the
suite, `notebooks|smoke-notebook|<cloud>`, runs the notebooks of [Running the notebooks](#running-the-notebooks) against that cloud, after the other tests,
and so after `spark-polaris`, which writes the table that the notebook `spark-connect-oidc-test` reads.

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
  `manage-stack setup <stack>`, eg `--setup aws-full,azure-polaris,gcp-polaris`, each logging to the file
  `<stack-id>.log` in `$AFON_HOME/logs`, whatever `--logging-file` says. The tests are not run if a setup
  fails. Its pods' logs are then saved (see
  [Diagnostics of a failure](#diagnostics-of-a-failure)). The stacks, including one whose setup failed part way, are left up, and the commands deleting them are
  printed on exit, unless `--teardown` is given.
- `--teardown` tears down the set up stacks on exit, logging to the `<stack-id>.log` of their setup, in reverse order, with the afon command
  `manage-stack delete <stack>`, whether the tests passed or failed, or a setup failed. The suite fails if a teardown
  fails, naming the stack to delete. The stacks are never torn down if the suite is interrupted, eg with Ctrl-C: that
  may stop a setup part way, with its tofu state still locked, so the commands deleting them are printed instead.
- `--suite` reads the tests from another file, of the same format, eg a subset of the tests for a single cloud.
- `--tests` runs only the comma-separated tests, eg `--tests network,spark-connect`. All are run by default.
- `--logging-console` sets the logging console level, one of `OFF|DEBUG|INFO|WARN|ERROR`, of every afon command the
  suite runs, by exporting `AFON_LOGGING_CONSOLE`. It defaults to that of `afon.sh`, `INFO`, or of `afon-cli.sh`,
  `OFF`.
- `--progress` sets the seconds between the lines `=== Still setting up stack aws-full, 03:00 so far` (likewise for tearing down a stack and for a
  test) that say that a long step is still running, as a deploy prints nothing for minutes when the console logging is off. It defaults to 60; 0 for none.
- `--list` lists the tests.

The tests run against the full stack's cluster of the `<cloud>`: the Spark Connect tests read the catalogs of the clouds
through its Spark Connect server, so they also need the Polaris stacks of the other clouds. On every run, after any
setups, and whether `--env` is given or not, the suite:

1. points `KUBECONFIG` at the kubeconfig of the env's full stack cluster of the `<cloud>`, with the afon command
   `<cloud>-update-kube-config tre`, ie of the stack with the role `tre`, for the suite only;
2. if a selected test is the network smoke test, checks, without changing the cluster, that the Polaris ingress of that cluster is
   restricted, ie has the annotation `alb.ingress.kubernetes.io/inbound-cidrs` (AWS) or
   `nginx.ingress.kubernetes.io/whitelist-source-range` (Azure and GCP), which there only works with `externalTrafficPolicy: Local` on the
   nginx controller's service, as that keeps the callers' IPs. If it is not, the suite warns that the `network` test is expected to
   fail, and prints the `kubectl` commands that restrict the ingress, for you to run, with the CIDRs to restrict it to: the
   configured ones, plus the cluster's own addresses, so that its pods calling Polaris by its public domain name are not locked out.
   These are, on AWS, the cluster's egress IP, read from its NAT gateway, as `AWS_EGRESS_IP` may be stale, falling back to
   `AWS_EGRESS_IP`; on Azure, the pod CIDR and the outbound IP, read from the cluster's load balancer (needs `az` logged in), falling back to
   `AZURE_EGRESS_IP` if it is not found, and warning if the two differ, as on AWS; on GCP, the cluster's pod range and node subnet, and `GCP_EGRESS_IP` if one is pinned (no lookup, so nothing needs `gcloud`). The
   commands only last until the next deploy, which is why the permanent fix is to deploy with the property set. The tests run either
   way.

#### The network test on AWS, Azure and GCP

The `network` test (`network-smoke`) runs the same three checks on every cloud, but how the first is satisfied differs:

| | AWS | Azure | GCP |
|---|---|---|---|
| Polaris ingress | ALB | nginx ingress controller, in the cluster, behind a public load balancer | as Azure |
| Restricted by | `alb.ingress.kubernetes.io/inbound-cidrs`, from `AWS_POLARIS_ALLOWED_CIDRS` | `nginx.ingress.kubernetes.io/whitelist-source-range`, from `AZURE_POLARIS_ALLOWED_CIDRS`, with `externalTrafficPolicy: Local` on the controller's service | as Azure, from `GCP_POLARIS_ALLOWED_CIDRS` |
| Always admitted | the cluster's egress IP (its NAT gateway) | the cluster's pod CIDR, and its outbound IP in the commands the suite prints | the cluster's pod range and node subnet, and `GCP_EGRESS_IP`, if pinned |
| A caller outside of them | the ALB drops its packets: the call fails or times out | nginx answers with its own `403 Forbidden` page, without calling Polaris | as Azure |
| The test passes when | the call fails or times out | the call times out, or gets nginx's own 403; any other response, eg a 403 from Polaris, fails it | as Azure |

So on Azure and GCP a quick 403 is the expected outcome of check 1, not a failure, whereas on AWS any HTTP response is one.

##### The machine running the test must be outside the allowed CIDRs

Check 1 calls Polaris **from the machine running the test**, and passes only if that call is stopped. So the machine's public
address must not be among the CIDRs of the ingress, which are `AWS_POLARIS_ALLOWED_CIDRS` (`AZURE_POLARIS_ALLOWED_CIDRS` on
Azure, `GCP_POLARIS_ALLOWED_CIDRS` on GCP) plus the cluster's egress IP (AWS), pod CIDR and outbound IP (Azure), or pod range, node subnet and pinned egress IP (GCP). If it is, Polaris answers the call and the test fails
with `Managed to make an HTTP call to <polaris domain>. Received HTTP code <code>`, whatever that code is.

`manage-stack setup` of a stack with Polaris always ends by restricting it without the machine that ran it (the last step,
`whitelist_polaris_for_<cloud>`, which restores the restriction, whether or not the machine was admitted for the setup, see
[reaching Polaris from the setup steps](cluster.md#reaching-polaris-from-the-setup-steps)), so the test passes after a setup, unless the
machine's address is among the configured CIDRs.

The suite never changes the ingress, so the machine's own access to Polaris is as the ingress is restricted:

| | Not restricted | Restricted by the deploy, without the machine's address | With the machine's address |
|---|---|---|---|
| `network` test | **fails**, with the commands to restrict it | passes | **fails** |
| Machine's access to Polaris | open | restricted | restricted, machine admitted |

So to run the `network` test from a machine, leave the property unset or without that machine's address. If a Polaris that should be
reachable from your machine times out after a restriction you applied by hand with the printed commands, check
`kubectl get ingress polaris-ingress -n default -o yaml` for the annotation, and remove it, for example on AWS:

```shell
kubectl annotate ingress polaris-ingress -n default alb.ingress.kubernetes.io/inbound-cidrs-
```

The other checks are cloud-neutral, as the network policies are the same. The Spark driver's allowed-domains check curls each name
in the common and TRE cloud sections of the current `whitelist.yaml`, not those of the other clouds, which only resolve while their
stack is deployed. So the test passes on either cloud whatever the other clouds' stacks, but a stack deployed before the list
changed fails it until it is redeployed, as its network policies hold the old list.

So the results only depend on the env's config and stacks, whoever runs the suite. All the selected tests are run, even if some fail. Their logging goes to the afon log file,
`$AFON_HOME/logs/<cloud>-full.log`, the log of the stack they run against (or the file named by `--logging-file`), so that runs for different clouds at the same time each have a log of their own, and to the console as set by `--logging-console`. The script ends with a summary, and
exits non-zero if any test failed.

#### The Spark write test

The `spark-polaris` test (`spark-polaris-smoke`) checks that Spark writes a table through the Polaris of the stack to the stack's own
catalog, `aws`, `azure` or `gcp` as the `TRE_CLOUD` says, and reads it back. It is the check that the setup of a stack used to end with:
a failing check now fails the smoke tests, and not a setup that has already deployed and configured a working stack.

The Spark is the session of the test's own process, signed in as the configured admin user, and Polaris is called from the machine
running the test, through a port-forward to its pod, or through its public domain name if `POLARIS_ACCESS=https`. The test writes the table that
`trino` and `spark-connect` read, so the suite runs it before them, after `keycloak` and `db`, which it needs. It fails if Polaris refuses the write.

It is skipped, and passes, in project mode (the config has `OIDC_PROJECT_CLAIM`): the TRE's Polaris admits only the tokens of the project, with its
audience, its `acr` and its project claim, which that user cannot present. Making the smoke tests work against it is separate work.

In a Data Provider's project stack (a project stack id, no `OIDC_PROJECT_CLAIM`, and the admin client of its Polaris) it is the same test, with the same code, writing where
a Data Provider writes: the `staging` namespace of the first of its own catalogs, which no project may read. It writes two tables, `smoke_test` and
`smoke_test_unreleased`, signed in as the admin client of its Polaris, which is what its Polaris admits besides the members of the projects it lets in,
and reaches Polaris through a port-forward as `POLARIS_ACCESS` says, as `manage-data` does. Run it in the project stack's home:

```shell
afon-cli.sh manage-project-stack run lon spark-polaris-smoke
```

Releasing `smoke_test` (`afon-cli.sh manage-project-stack run lon manage-data release cvd smoke_test`) gives the projects that `lon` has let in a table with
rows to read, from a notebook of the TRE (`scripts/smoke/notebooks/project-spark-reads-data-providers.ipynb` with Spark, `project-trino-reads-data-providers.ipynb` with Trino), and
leaves `smoke_test_unreleased` in `staging`, which they must be refused. In project mode, `smoke-notebook` runs those two notebooks in the pod of the smoke member, which it signs in to the hub (or, with `--local`, from your machine), and a member can run them in their own.
See [Projects](projects.md#a-first-tre-project).

Writing the tables is a test, never part of an operation: nothing that deploys or
operates a project writes data. The test writes a table only if it is in neither `staging` nor `released`, and `release` of a table that is in `released` and not in `staging`
says so and moves nothing, so the two can be repeated.

### Diagnostics of a failure

A stack that fails to set up, or a test that fails, is explained by what its pods logged, which the teardown deletes. So, before any teardown, the
suite saves the state of the stack's cluster in `$AFON_HOME/logs/<stack-id>-diagnostics.log`: for a failed test, that of the full stack of the
cloud, `<cloud>-full-diagnostics.log`; for a failed setup, that of the stack, which says so if its cluster does not exist yet. It holds all
the pods, the warning events and the tail of the log of every pod (`DIAGNOSE_LOG_LINES`, default 50 lines), with the log of the previous
container (30 lines) of those that restarted, and more for a failed `git` test. The logs store keeps every `*.log` of that dir, so
`logs-store pull` retrieves it. Nothing of it goes to the console, which a public repo's job log shows to all, and the values of passwords,
secrets, tokens and keys, bearer tokens and JWTs in it are masked (`mask_secrets`). A pod whose log cannot be read is noted in it, and never
fails the run or stops the teardown.

The results of each run are also written to `$AFON_HOME/smoke/tests/results-<stack-id>-<yyyymmdd-hhmmss>.txt`, without any
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

#### The notebook in a Jupyter pod

The `jupyter-pod-aws` test (`jupyter-pod-notebook-smoke`, [JupyterPodNotebookSmokeMain](../afon-ops/src/main/java/health/emrys/afon/ops/smoke/JupyterPodNotebookSmokeMain.java))
runs [demo/notebooks/AwsSmokeTest.ipynb](../demo/notebooks/AwsSmokeTest.ipynb) in a user's own Jupyter pod, as the user would, rather than from your machine
as `smoke-notebook` does: with the pod's Python and Spark Connect client, its network policy and its certificate of Trino. So it checks Spark Connect,
with the token in its header, a Python UDF on the executors, whose Python must be that of the pod, and Trino over HTTPS, from where a user runs them.

It asks JupyterHub to spawn the pod of the configured user (`USER_ID`), as the `network` test does. A pod spawned so has no `OIDC_TOKEN`, which the
hub only injects at a user's sign-in, so the test gets the user's token from Keycloak and gives it to the notebook as `OIDC_TOKEN`, with the name of the cloud's catalog as `CATALOG`. The token, the
catalog and the notebook are sent on the standard input of an exec through the Kubernetes API (`KubeClient`), so the token is in no command line, and the notebook runs in a temporary directory of the pod,
removed afterwards, with `jupyter nbconvert --execute`. Every cell is run, even after one fails, and the test fails if any cell raised an error. The
executed notebook, with its outputs, is written to `smoke/notebooks/AwsSmokeTest.ipynb` in the JVM's temporary directory (`java.io.tmpdir`).

The notebook is AWS's: its catalog `test_aws` is the `aws` warehouse of the Polaris it names, and it reads the table `admin.deployments` that
`spark-polaris` writes, so the suite runs it after that test. Deselect it when testing another cloud. Another notebook is run with
`./afon.sh -a jupyter-pod-notebook-smoke -- <notebook>`. Like `spark-polaris`, it is skipped, and passes, in project mode, as the TRE's Polaris admits
only the tokens of its members.

## Other smoke tests

- `ops.demo.GeneratePySparkKeycloakMain` and `ops.demo.GeneratePySparkContextMain` generate PySpark code declaring
  all the configured catalogs, authenticated with `OIDC_TOKEN`, to paste into a notebook.

## Image signing

`afon-cli.sh smoke-signing [<cloud>] [<image>]` ([signing.sh](../scripts/smoke/signing.sh)) runs the [image signing](stacks.md#image-signing)
flow on your machine, with a throwaway local registry and a throwaway key, so that it needs no deployed stack, cloud registry or KMS:

1. starts a registry, the image of `docker-images.yaml`, on a free local port;
2. generates a key pair with no password, and gets its public key with `docker-image --public-key`;
3. signs one image (`postgres-alpine` unless one is named) with `docker-image --sign`, which copies it to the registry first;
4. checks with `docker-image --verify --signatures` that the image verifies, and that the others, which were not signed, do not;
5. removes the registry and the key, also when a step fails.

It needs `docker`, `cosign` and `curl` (see [local setup](local_setup.md)), and network access to pull the image. The cloud only gives the names of the
properties: the registry, the key and the public key are set for the run, so your config and your own keys are neither read nor changed. It exits
with 1 if the image does not verify, or if the unsigned images do.
```shell
afon-cli.sh smoke-signing azure
afon-cli.sh smoke-signing aws keycloak     # signs keycloak instead
```

This is a stand-in until each cloud provides its KMS key and registry: it will then be replaced by a test in the cluster, which signs with the
KMS key, pushes to the registry of the cloud and checks that Kyverno admits the signed image and reports the unsigned ones.
