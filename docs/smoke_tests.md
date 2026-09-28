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
  `USER_POLARIS_ID` and `USER_POLARIS_SECRET`. The test runs as the `USER_POLARIS_ID` user.
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
3. gets the OIDC access token of `USER_POLARIS_ID` from OIDC provider, with `./afon.sh -a show-oidc-token <cloud> user`;
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

`smoke/run-oidc-notebook.sh` sets the first four from your configuration.

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

## Other smoke tests

- `./afon.sh -a exec -ec ops.smoke.SparkConnectSmoke -ea aws` reads the setup table through Spark Connect from Java,
  with an OIDC token for `USER_POLARIS_ID` (see `SparkConnectClient`).
- `ops.demo.GeneratePySparkKeycloakMain` and `ops.demo.GeneratePySparkContextMain` generate PySpark code declaring
  all the configured catalogs, authenticated with `OIDC_TOKEN`, to paste into a notebook.
