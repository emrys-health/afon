# Security Policy

Afon handles credentials and controls access to sensitive data, so we take security reports seriously.

## Supported versions

Security fixes are made to the latest release only.  Afon is still in beta (`1.0-beta-*`): please upgrade to the
latest [release](https://github.com/emrys-health/afon/releases) before reporting, to check the issue is still present.

## Reporting a vulnerability

**Do not report security vulnerabilities in public issues, pull requests or discussions.**

Report them privately through GitHub's
[private vulnerability reporting](https://github.com/emrys-health/afon/security/advisories/new)
(the *Report a vulnerability* button on the repository's *Security* tab).  Only the maintainers can see your report.

Please include, as far as you can:

- the affected component (eg a CLI action, a module, a deployed service) and version;
- the steps to reproduce the issue, or a proof of concept;
- the impact: what an attacker could read, change or reach;
- any suggested fix or mitigation.

Do not include real credentials, tokens or user data in your report.

## What to expect

- We aim to acknowledge your report within 5 working days.
- We will keep you informed as we confirm the issue, work on a fix and plan the release.
- Once a fix is released we publish a security advisory, crediting you if you wish.  Please give us a reasonable time
  to fix the issue before disclosing it publicly.

## Scope

In scope: the code, scripts, templates and default configuration in this repository.

Out of scope: vulnerabilities in third-party components that Afon deploys or depends on (eg Spark, Trino, Polaris,
Keycloak, Gitea) which are not caused by how Afon configures them: please report these to the upstream project.
Misconfiguration of your own deployment is also out of scope, but if Afon's defaults or documentation led you to it,
that is worth reporting.
