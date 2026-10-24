# Testing

Afon has three levels of tests, which differ in what they need to run, and so in who runs them:

| Level                      | What it tests                                                       | Runs against                          | Needs                                       | Run by                            |
|----------------------------|---------------------------------------------------------------------|---------------------------------------|---------------------------------------------|-----------------------------------|
| [Unit](#unit-tests)        | A class or module in isolation                                      | Nothing external                      | JDK 17, Maven                               | Every contributor, on every change |
| [Acceptance](#acceptance-tests) | Cross-cloud access via Polaris, including denial of forbidden access | Local containers and cloud storage    | Docker, configuration for all clouds        | Maintainers, contributors with cloud access |
| [Smoke](#smoke-test)      | A deployed stack end to end, as its users use it                    | A real deployment                     | A deployed stack, `kubectl`, configuration  | Maintainers, contributors with a deployment |

Every contribution must pass the unit tests.  Whether it also needs acceptance or smoke tests depends on what it
changes, see [which tests a change needs](#which-tests-a-change-needs).

## Unit tests

Unit tests live in each module's `src/test`, and run as part of the Maven build:

- **Java**: [JUnit 5](https://junit.org/junit5/), with [AssertJ](https://assertj.github.io/doc/) assertions,
  [Mockito](https://site.mockito.org/) for mocks, and [JUnit Pioneer](https://junit-pioneer.org/) eg to set env
  variables or system properties.
- **Scala**: [ScalaTest](https://www.scalatest.org/).

### Running

To build all modules, running the unit tests but not the acceptance tests:

```shell
./afon.sh -a build -bp unit
```

To run the tests of a single module, or a single test, with Maven directly:

```shell
mvn test -P-acceptance -pl afon-config
mvn test -P-acceptance -pl afon-rest -Dtest=HttpResponsesTest
```

`-pl` resolves the module's dependencies from your local Maven repository, so run the full build once first.

The CLI module is outside the default reactor, so is built and tested with the `cli` profile:

```shell
./afon.sh -bg "clean package" -bp cli,-export
```

### Writing

- Name the test class after the class it tests, with suffix `Test`, in the same package, eg
  [HttpResponsesTest](../../afon-rest/src/test/java/health/emrys/afon/rest/HttpResponsesTest.java) for `HttpResponses`.
- Name test methods after the behaviour they check, as a sentence, eg `serverErrorsMayBeRetried`, rather than after
  the method they call.
- Test one behaviour per test, including failure cases: invalid input, missing configuration, error responses.
- Unit tests must not depend on the network, a cloud account, Docker, or the developer's `$AFON_HOME`.  Put any
  configuration they need in `src/test/resources`, with placeholder values, never real ones.
- A bug fix comes with a test that fails without the fix.

## Acceptance tests

The acceptance tests, in module `afon-acceptance`, are ScalaTest specs verifying that cross-cloud data access via
Polaris works, and that access to forbidden buckets is denied, for AWS, Azure and GCP.  They start Keycloak, Polaris
and Trino locally via [Testcontainers](https://testcontainers.com/), and read and write Iceberg tables in the clouds'
storage.

See [acceptance tests](../acceptance_tests.md) for the test flow, the specs, and how they run in CI.

### Running

They need Docker, and the [configuration](../configuration.md) for the clouds they run against, including credentials,
so they cannot be run without access to cloud accounts.

```shell
./afon.sh -a build -bp acceptance              # all clouds
./afon.sh -a build -bp acceptance -c <cloud>   # a single cloud: aws, azure or gcp
```

The `acceptance` profile is active by default, so `./afon.sh -a build` runs them too.  They need a machine with at
least 8GB, and preferably 16GB, of memory.

### Writing

- Specs extend `AbstractAccessSpec`, or `AbstractAccessForbiddenSpec` for denial of access, in the package of their
  cloud, eg `health.emrys.afon.acceptance.aws`.
- Test both sides of access control: that authorised access succeeds, and that unauthorised access is denied.
- Specs share buckets and catalogs, so use names that do not clash with other specs, and clean up what they create.

### Contributors without cloud access

Changes to `afon-polaris`, `afon-spark`, `afon-iceberg`, `afon-security` or `afon-acceptance` may need the acceptance
tests.  If you cannot run them, say so in the pull request, and a maintainer will run them before merging.

## Smoke tests

The smoke tests check a deployed stack end to end: from a Jupyter notebook, through Spark Connect, to Polaris,
authenticated with the user's own OIDC token, as well as the networking, Trino and Guacamole of the stack.  They
complement the acceptance tests, covering what only exists in a real deployment: the deployed servers, Keycloak,
the Polaris grants configured from `users-acl.csv`, and the network restrictions.

See [smoke tests](../smoke_tests.md) for what is tested, the requirements and troubleshooting.

### Running

The notebooks of `scripts/smoke/notebooks.csv`, or those named after the cloud, eg `oidc`, against the cluster of the current `kubectl` context:

```shell
afon-cli.sh smoke-notebook <cloud>
```

The regression suite, running the tests listed in [scripts/smoke/tests-full.csv](../../scripts/smoke/tests-full.csv) against the
full stack of a cloud, `aws` by default, or `azure`, and optionally setting up the stacks first and tearing them down after:

```shell
afon-cli.sh smoke-test --list
afon-cli.sh smoke-test --env <env> --setup aws-full,azure-polaris,gcp-polaris --teardown
afon-cli.sh smoke-test --env <env> --setup azure-full --teardown azure
```

**NOTE**: deploying stacks creates cloud resources, which incur costs.  Tear them down when done.

### Writing

- Smoke tests are Java main classes in package `health.emrys.afon.ops.smoke`, with suffix `SmokeMain`, eg
  `NetworkSmokeMain`.
- They exit with a non-zero exit code on failure, so that the regression suite and CI detect it.
- Add a new smoke test to the regression suite file, as a line `name|class|args`.
- Never print tokens, passwords or other secrets, even on failure.

### Contributors without a deployment

Changes to `afon-cdk`, `afon-ops`, the deployment scripts, or anything deployed to a stack may need the smoke tests.
If you cannot run them, say so in the pull request, and a maintainer will run them against a test deployment before
merging.

## Which tests a change needs

| Change                                                                 | Unit | Acceptance | Smoke |
|------------------------------------------------------------------------|:----:|:----------:|:-----:|
| Documentation only                                                     |      |            |       |
| Configuration, CLI, scripts, utilities                                 |  ✓   |            |       |
| Catalog management, access control, Spark or Iceberg integration       |  ✓   |     ✓      |       |
| Stacks, deployed resources, networking, Keycloak, Spark Connect, Trino |  ✓   |            |   ✓   |
| A new cloud, catalog or query engine                                   |  ✓   |     ✓      |   ✓   |

If in doubt, ask in the issue.  State in the pull request which tests you ran, and on which clouds or stacks.
