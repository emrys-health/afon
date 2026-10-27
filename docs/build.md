# Build from source

Code is hosted on the GitHub [afon](https://github.com/emrys-health/afon) project.
We assume read-only access to the project has been granted.

## Clone 

To clone project:
```shell
git clone https://github.com/emrys-health/afon.git
```

## Git hooks

The repo ships a `commit-msg` hook that checks the issue referenced at the start of a commit
message (eg `[#203] ...`) is assigned to you on GitHub, to catch pasting the wrong issue number
before it lands in history.  It requires the [GitHub CLI](https://cli.github.com/) (`gh`),
authenticated via `gh auth login`; without it the check is skipped rather than blocking commits.

Enable it once per clone:
```shell
git config core.hooksPath .githooks
```

A [companion GitHub Action](../.github/workflows/issue-assignee-check.yml) runs the same check
on push, as a backstop for commits made without the hook enabled — it's advisory only (shows as
a failing check on the commit/PR) since this repo's plan doesn't support branch protection.

## Build

We assume [setup](setup_local.md) has been done. 

To build all components, running acceptance tests:
```shell
cd afon
./afon.sh -a build
```

The acceptance build profile is active by default but can be explicitly activated as well:
```shell
cd afon
./afon.sh -a build -bp acceptance
```

To build all components without running acceptance tests:
```shell
cd afon
./afon.sh -a build -bp unit
```

See [afon_script](afon_script.md) for more details on options and [acceptance tests](acceptance_tests.md).

### Configuration

The build requires the [configuration](configuration.md) to be setup.

If you want to build with the configuration relative to single cloud:

```shell
cd afon
./afon.sh -a build -bp unit -c <cloud>
```

**NOTE:** acceptance tests require the configuration for all clouds.

### Afon home

The Afon home can be set via the `--home` option or via env variable:

- `AFON_HOME`: the Afon home dir, defaulting to $HOME/.afon.

### Logging 

The script supports two different loggings, one to file and one to console.  

The logging levels can be set via the `--logging` and `--logging-console` options or via env variables:

- `AFON_LOGGING`: the file logging level, defaulting to INFO.
- `AFON_LOGGING_CONSOLE`: the console logging level, defaulting to OFF.

### Generated docs

The docs related to the operations (in module `afon-ops`) are generated and kept in sync as part of the build.  

They can be found in `afon-ops/target/docs`.

The generated docs are included in the `afon-distribution`.

### CLI

To build the `afon-cli` jar and export it to an external directory, **without installing** in your local maven repository:
```shell
./afon.sh -bg "clean package" -bp cli -ba "-Dafon.cli.dir=<cli-dir>"
```
If the `-Dafon.cli.dir` is omitted, the `afon-cli` artifacts will be exported to `AFON_HOME/cli`.  
If you just need to build and not export the artifacts, you can deactivate the `export` profile:
```shell
./afon.sh -bg "clean package" -bp cli,-export
```
The `export` profile is active by default because it's the most common use case when building the CLI.

To build a binary distribution with the `afon-cli` files and the docs:
```shell
./afon.sh -bg "clean package" -bp cli,distribution
```
You'll find the file `afon-cli` zip file in the `afon-distribution/target` dir.

**NOTE:** The `afon-cli` executable jar can be rather big, so you may not want to install it in your local maven repository,
it being a duplication of artifacts already present.  For this reason, the `afon-cli` is not part of the maven build
default reactor and needs to be activated via the `cli` profile.   Additionally, the cli artifacts (jar and script)
can be exported for use outside the maven build and source checkout.

### GitHub Workflows

The [GitHub Workflows](https://github.com/emrys-health/afon/actions) are run on a Linux Runner.

To set up the runner via ansible:
```shell
./afon.sh -a ansible
```
Requires ssh access to the runner. 

The workflows require the config to be uploaded to GitHub secrets:
```shell
./afon.sh -a github-secrets push
```
This pushes the content of each config files to a separate GitHub secret.

**NOTE:** upon any change in the config, the secrets need to be pushed again.

To work with more than one [config env](configuration.md#config-env), push each one to its own GitHub environment,
and retrieve it again on another machine, with `-- push --env <env>` and `-- pull --env <env>`.  See
[acceptance tests](acceptance_tests.md#config-secrets) for details.


