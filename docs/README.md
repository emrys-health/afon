# Afon Documentation

Afon is an open source implementation of a data fabric that enables secure connections between organisations holding sensitive data. It is designed to be cloud-agnostic, modular and configurable based on researcher preferences and local technology stacks.

This builds on existing work from across the UK health data ecosystem, including work by NHS England, The Data for R&D program, DARE UK, SeRP and Research Data Scotland.

It was built to solve a gap in the health research data ecosystem; there is no mechanism that provides a simple, secure and compliant way for researchers to access and query row level data across multiple organisations.

# Who is Afon for

Afon is for TRE or Data Provider administrators and their support teams who want to deploy and manage a data fabric for a research project.

Afon is also for members of the health data community who want to contribute to the project or learn more about how it works. See [contributing](./contributing/README.md) for how to report issues, propose changes and open pull requests.

In any project the infrastructure can be divided into the organisation hosting the central **TRE** (a hosted Trusted Research Environment), where a researcher will log in and analyse data, and the **Data Provider** organisations hosting the data that will be used by the researchers (a data partner).

The TRE host will be deploying the full Afon stack for the choice of IDE, query engine and cloud.

A Data Provider partner can either host their own Polaris catalog (or compatible iceberg catalog) or use Afon to deploy one.

# How to use this repository

If you are looking to deploy the data fabric, the easiest way to do so is through the CLI (which does not require access to the source repo).  We assume this is the default position.

## Guides

- [Guides](./guides/README.md)

## Setup and configuration

- [Entry points](./afon_entry_points.md)
- [CLI binary distribution](./afon_cli.md)
- [Setup local env](./local_setup.md)
- [Docker images](./docker.md)
- [Configure properties and users](./configuration.md)
- [Theming](./theme.md)

## How to deploy

- [Cloud infrastructure](./cloud.md)
- [DNS Configuration](./dns_config.md)
- [Deploying stacks](./stacks.md)
- [Configuring catalogs](./catalogs.md)

## How to use

- [Jupyter](./jupyter.md)
- [Monitoring](./monitoring.md)
- [Operations](./operations.md)
- [Smoke Tests](./smoke_tests.md)
- [Snowflake](./snowflake.md)
- [Examples](./examples.md)

