# Build from source

Code is hosted on the GitHub [afon-core](https://github.com/emrys-health/afon-core) project.
We assume read-only access to the project has been granted.

## Clone 

To clone project:
```shell
git clone https://github.com/emrys-health/afon-core.git
```

## Build

We assume [setup](setup.md) has been done. 

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

### Generated docs

The docs related to the operations (in module `afon-ops`) are generated and kept in sync as part of the build.  

They can be found in `afon-docs/target/docs`.

To export all the docs (including the static) to an external dir:

```shell
./afon.sh -a docs-export --docs-dir ~/.afon/docs
```


