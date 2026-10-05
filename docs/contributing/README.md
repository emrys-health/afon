# Contributing to Afon

Thank you for your interest in contributing to Afon.  Afon is an open source data fabric for secure, cross-organisation
access to sensitive (eg health) data, and contributions from the wider community, whether from TRE/SDE operators, data
partners, researchers or developers, are very welcome. By contributing to Afon, you are helping build the open digital infrastructure that keeps sensitive health data secure, accessible, and safe. Whether you are fixing a typo or building a new core feature we are very grateful for your contribution.

This guide covers:

- [Ways to contribute](#ways-to-contribute)
- [Contribution criteria](#contribution-criteria): what we expect of every contribution
- [Creating issues](issues.md): how to report a bug, propose an enhancement or a new feature, with templates
- [Creating pull requests](pull_requests.md): the workflow from fork to merge
- [Testing](testing.md): unit, acceptance and smoke tests, and which ones a contribution needs

## Ways to contribute

You don't need to write code to contribute.  All of the following are valuable:

- **Reporting bugs**, with enough detail for us to reproduce them.
- **Suggesting enhancements** to existing behaviour, or **proposing new features**.
- **Improving the documentation**: fixing errors, filling gaps, clarifying steps that tripped you up.
- **Testing** on a cloud, OS or configuration we have not covered, and reporting what you find.
- **Reviewing** open pull requests.
- **Fixing bugs or implementing features**, starting with the issues labelled `good first issue` or `help wanted`.



## Contribution criteria (How we maintain quality together)

These are the standard criteria for contributions to an open source project, and the ones every contribution to Afon
is reviewed against.

### Licensing

Afon is released under the [Apache License 2.0](../../LICENSE.txt).  By submitting a contribution you agree that it is
licensed under the same terms, as per section 5 of the license, and you confirm that:

- the contribution is your original work, or you have the right to submit it under the Apache License 2.0;
- it does not include code, data or other material under a license incompatible with Apache 2.0 (eg GPL or AGPL);
- any third-party dependency it adds is under a compatible license, and is named in the pull request.

### Issue first

Every change, except trivial ones such as typo fixes, starts from an [issue](issues.md).  This lets us agree on the
problem, and on the approach, before you invest time in the code, and gives the commits an issue to reference.
For a new feature, or any change to the architecture, wait for a maintainer to agree the approach in the issue before
starting work.

### Small, focused changes

A pull request should address a single issue.  Smaller pull requests are reviewed more quickly and more thoroughly.
Unrelated refactoring, reformatting or dependency updates belong in their own issue and pull request.

### Quality

- **Tests**: new behaviour comes with tests, and bug fixes with a test that fails without the fix.
  See [testing](testing.md).
- **The build passes**: `./afon.sh -a build -bp -acceptance` succeeds, with all unit tests passing.
- **Consistency**: code follows the conventions of the code around it: naming, structure, formatting, comment
  density and idiom.  Like components should look and behave alike.
- **Documentation**: any change of behaviour visible to users or operators (CLI actions, options, configuration
  properties, stacks) is reflected in the [docs](..).  Configuration properties added to the scripts are also added
  to the `AfonProperty` enum in `afon-config`.
- **No breaking changes** without discussion: changes to configuration properties, CLI actions or deployed resources
  that break existing deployments must be agreed in the issue, and called out in the pull request.


### Security

Afon handles credentials and controls access to sensitive data, so security is part of every review.

- **Never commit secrets**: credentials, tokens, keys, passwords, real configuration files or real user data.  Use
  placeholders in docs and test fixtures, as in the existing test resources.
- **Do not weaken access controls**, eg grants, network restrictions or authentication, without explicit agreement
  in the issue.
- **Do not report security vulnerabilities in public issues.**  Report them privately, via GitHub's
  [private vulnerability reporting](https://github.com/emrys-health/afon-core/security/advisories/new), so that they
  can be fixed before they are disclosed.

### Conduct

We expect all contributors to be respectful, constructive and inclusive, in issues, pull requests and reviews alike.
Assume good intent, critique the code rather than the person, and remember that maintainers and contributors are
often volunteering their time.  Harassment or abusive behaviour of any kind is not tolerated.

### Guidelines for AI-assisted Contributions

Contributors may use a variety of tools when preparing changes to Afon, including AI systems (e.g., large language models, code assistants or agents). Contributors using such systems are expected to adhere to the following principles:

- Regardless of how a change is produced, the individual submitting the pull request is considered the author of the contribution and is fully responsible for it.
- The pull request author must understand the implementation end-to-end and be able to explain and justify the design and code during review.
- Tools, including AI systems, are not considered contributors. Responsibility and authorship remain with the human submitting the change.
- Contributors are encouraged to disclose significant AI assistance in the pull request description for transparency.

## Getting started

If you have never contributed to Afon before, we recommend starting with issues labelled `good first issue` or `help wanted`. These are designed to be approachable for newcomers and provide a gentle introduction to the codebase. If you would like to speak to someone about how to contribute - please join the slack channel and we'll be happy to help - https://join.slack.com/t/afontalk/shared_invite/zt-4bg5ei3r6-AsPFebfb0~sa4Z46SOk3Mg

1. Read the [README](../../README.md) and the [architecture](../architecture.md) to understand what Afon does and
   how it is structured.
2. Set up your local environment: [setup](../setup_local.md).
3. Build from source: [build](../build.md).
4. Find, or [create](issues.md), an issue to work on, and comment on it to say you are working on it.
5. Follow the [pull request](pull_requests.md) workflow.

## Getting help

If something is unclear, ask in the issue you are working on, or open an issue with the `question` label.  If you
found a step in these docs hard to follow, that is worth an issue too.
