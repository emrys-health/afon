# Build from source

Code is hosted on the GitHub [afon-core](https://github.com/emrys-health/afon-core) project.
We assume read-only access to the project has been granted.

## Clone 

To clone project:
```shell
git clone https://github.com/emrys-health/afon-core.git
```

## Build

We assume [setup](setup_local.md) has been done. 

To build all components, running acceptance tests:
```shell
cd afon-core
./afon.sh -a build
```

The acceptance profile is active by default but can be explicitly activated as well:
```shell
cd afon-core
./afon.sh -a build -p acceptance
```

To build all components without running acceptance tests:
```shell
cd afon-core
./afon.sh -a build -p -acceptance
```

See [acceptance tests](acceptance_tests.md) for more details.

### Configuration

The build requires the [configuration](configuration.md) to be setup.

If you want to build with the configuration relative to single cloud:

```shell
cd afon-core
./afon.sh -a build -c <cloud> -p -acceptance
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

They can be found in `afon-docs/target/docs`.

To export all the docs (including the static) to an external dir:

```shell
./afon.sh -a docs-export --docs-dir ~/.afon/docs
```

### Executable CLI jar

Module `afon-cli` packages the `afon-ops` module (and all its dependencies) into a single executable jar, 
so the ops commands can be run without access to source checkout:

```shell
afon-cli/src/main/scripts/afon-cli.sh
```
The script `afon-cli/src/main/scripts/afon-cli.sh` wraps the java invocation, providing all the necessary
JVM args, and locates the jar via the env variable `AFON_CLI_DIR`, defaulting to the script dir.  In case
the script dir is located in its source dir, it assumes you are building from source and looks for the 
jar in the `afon-cli/target` dir.

The script supports the following env variables: 

- `AFON_HOME`: the Afon home dir, defaulting to $HOME/.afon.
- `AFON_LOGGING`: the file logging level, defaulting to INFO.
- `AFON_LOGGING_CONSOLE`: the console logging level, defaulting to OFF.

Helpfully, the script can be invoked from anywhere.

Running the script with no command (or `help` or `-h`) lists all available commands, derived from the `afon-ops` `*Main` classes.

The `afon-cli` executable jar can be rather big, so you may not want to install it in your local maven repository, 
it being a duplication of artifacts already present.  For this reason, the `afon-cli` is not part of the maven build 
default reactor and needs to be activated via the `cli` profile.   Additionally, the cli artifacts (jar and script) 
can be exported for use outside the maven build and source checkout. 

To build the `afon-cli` jar and export it to an external directory, **without installing** in your local maven repository:
```shell
./afon.sh -g "clean package" -p cli --build-args "-Dafon.cli.dir=<export-path>"
```
If the `-Dafon.cli.dir` is omitted, the `afon-cli` artifacts will be exported to `AFON_HOME/cli`.  
If you just need to build and not export the artifacts, you can deactivate the `export` profile: 
```shell
./afon.sh -g "clean package" -p cli,-export
```
The `export` profile is active by default because it's the most common use case when building the CLI.

### GitHub Workflows

The [GitHub Workflows](https://github.com/emrys-health/afon-core/actions) are run on a Linux Runner.

To set up the runner via ansible:
```shell
./afon.sh -a ansible
```
Requires ssh access to the runner. 

The workflows require the config to be uploaded to GitHub secrets:
```shell
./scripts/github/secrets.sh push 
```
This pushes the content of each config files to a separate GitHub secret.

**NOTE:** upon any change in the config, the secrets need to be pushed again.




- 

