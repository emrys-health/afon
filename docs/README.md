# Afon Documentation

Afon is an open source implementation of a data fabric that enables secure connections between organisations holding sensitive data. It is designed to be cloud agnostic, modular and configurable based on researcher preferences and local technology stacks.

This builds on existing work from across the UK health data ecosystem, including work by NHS England, The Data for R&D program, DARE UK, SeRP and Research Data Scotland.

It was built to solve a gap in the health research data ecosystem; there is no mechanism that provides a simple, secure and compliant way for researchers to access and query row level data across multiple organisations.



# Who is this repo for

This repository is for SDE or TRE administrators and their support teams who want to deploy and manage a data fabric for a research project.

This repository is also for members of the health data community who want to contribute to the project or learn more about how it works.
See [contributing](docs/contributing/README.md) for how to report issues, propose changes and open pull requests.

In any project the infrastructure can be divided into the organisation hosting the central TRE (a host), where a researcher will log in and analyse data, and the organisations hosting the data that will be used by the researchers (a data partner).

The host will be deploying the full Afon stack for the choice of IDE, query engine and cloud.

A data partner can either host their own Polaris catalog (or compatible iceberg catalog) or use this repository to deploy one.


# How to use this repository

If you are looking to deploy the data fabric, the easiest way to do so is through the CLI.  We assume this is the default position.

## For a host 


#### Set up

- Setup local env: [setup](./setup_local.md)
- CLI binary distribution: [cli](./afon_cli.md)
- Configure properties and users: [configuration](./configuration.md)
- Theming: [theme](./theme.md)

## How to deploy

- Cloud infrastructure: [cloud](./cloud.md)
- DNS Configuration: [dns_config](./dns_config.md)
- Deploying stacks: [stacks](./stacks.md)
- Deploy catalogs: [catalogs](./catalogs.md)

---
### How to use

- Jupyter: [jupyter](./jupyter.md)
- Monitoring: [monitoring](./monitoring.md)
- Operations: [operations](./operations.md)
- Smoke tests: [smoke tests](./smoke_tests.md)
- Snowflake: [snowflake](./snowflake.md)
- Examples: [examples](./examples.md)


## For a data provider 


#### Set up

- Setup local env: [setup](./setup_local.md)
- CLI binary distribution: [cli](./afon_cli.md)
- Configure properties and users: [configuration](./configuration.md)
- Theming: [theme](./theme.md)

## How to deploy

- Cloud infrastructure: [cloud](./cloud.md)
- DNS Configuration: [dns_config](./dns_config.md)
- Deploying stacks: [stacks](./stacks.md)
- Deploy catalogs: [catalogs](./catalogs.md)

---
### How to use

- Jupyter: [jupyter](./jupyter.md)
- Monitoring: [monitoring](./monitoring.md)
- Operations: [operations](./operations.md)
- Smoke tests: [smoke tests](./smoke_tests.md)
- Snowflake: [snowflake](./snowflake.md)
- Examples: [examples](./examples.md)


