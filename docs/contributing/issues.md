# Creating issues

Issues are tracked on GitHub, in the [afon issues](https://github.com/emrys-health/afon/issues).
Every change starts from an issue, and every commit references one (see [pull requests](pull_requests.md#commits)).

## Before creating an issue

- **Search** the [open and closed issues](https://github.com/emrys-health/afon/issues?q=is%3Aissue) first.
  If one already covers it, add your details, or a 👍, there rather than opening a duplicate.
- **Check the docs**, eg the troubleshooting sections of [smoke tests](../smoke_tests.md) and
  [operations](../operations.md), in case it is a known problem with a known fix.
<!-- TODO: the advisory link below 404s until private vulnerability reporting is enabled (Settings > Advanced Security),
     which is planned once the repo is made public.  The same link is in .github/ISSUE_TEMPLATE/config.yml -->
- **Security vulnerabilities must not be reported in a public issue**: use GitHub's
  [private vulnerability reporting](https://github.com/emrys-health/afon/security/advisories/new) instead.
- **Never include secrets** in an issue: credentials, tokens, passwords, keys, the content of `secret*.properties`
  or `users.csv`, or any real (eg patient) data.  Redact them from logs and config before pasting, eg as `<redacted>`.

## Issue types

Pick the type that best fits, and use its template below.

| Type                            | When to use it                                                                                    | Label         |
|---------------------------------|---------------------------------------------------------------------------------------------------|---------------|
| [Bug](#bug-report)              | Something does not work as documented, or fails, eg an action errors, a deploy fails, a test fails | `bug`         |
| [Enhancement](#enhancement)     | An improvement to something that already exists, eg a new option, better logging, faster deploy   | `enhancement` |
| [New feature](#new-feature)     | A capability Afon does not have yet, eg support for a new cloud, query engine, IDE or catalog      | `feature`     |

If unsure whether something is an enhancement or a new feature, ask yourself whether it changes an existing component
(enhancement) or adds a new one (new feature).  A maintainer may relabel it, which is fine.

## Writing a good issue

- **Title**: a short summary of the problem or proposal, eg *`manage-stack delete` leaves the EKS load balancer
  behind*, rather than *Delete broken*.
- **One issue per problem**: several unrelated problems in one issue are hard to track and close.
- **Be specific**: exact commands, versions, cloud and stack, and the exact error message, rather than a paraphrase.
- **Use the template**: its sections are there because the answers are needed to act on the issue.

## Bug report

~~~markdown
## Summary
A clear and concise description of the bug.

## Steps to reproduce
1. Configuration used (properties changed from the defaults, with any secrets redacted):
2. Command run, eg `./afon.sh -a manage-stack deploy aws-full`:
3. ...

## Expected behaviour
What you expected to happen.

## Actual behaviour
What actually happened, including the full error message.

## Logs
Relevant extract of `$AFON_HOME/logs/afon.log`, or of the console output, with secrets redacted.

<details><summary>Log</summary>

```
paste log here
```

</details>

## Environment
- Afon version or commit: (eg `afon-cli` version, or `git rev-parse --short HEAD`)
- How Afon is run: (source build with `afon.sh` / `afon-cli` distribution / Docker image)
- OS: (eg macOS 15, Ubuntu 24.04)
- Java version: (`java -version`)
- Cloud(s): (aws / azure / gcp)
- Stack(s): (eg `aws-full`, `aws-polaris`)
- Component(s): (eg Polaris, Keycloak, Spark Connect, Trino, Jupyter, CLI)

## Workaround
Any workaround you found, if any.

## Additional context
Anything else that may help, eg whether it worked in a previous version.
~~~

## Enhancement

```markdown
## Summary
A clear and concise description of the improvement.

## Current behaviour
How the existing component, action or option behaves today, with a link to the relevant docs or code.

## Proposed behaviour
How it should behave after the change.

## Motivation
**As a** [role, eg TRE administrator, data partner, researcher, developer]
**I want** [the improvement]
**So that** [the benefit]

## Acceptance criteria
- [ ] Criterion 1
- [ ] Criterion 2

## Impact
- Backwards compatible? (yes / no: what breaks, eg config properties, CLI options, deployed resources)
- Clouds affected: (aws / azure / gcp / all)
- Docs to update:

## Alternatives considered
Other approaches you considered, and why you prefer this one.
```

## New feature

```markdown
## Summary
A clear and concise description of the feature.

## Problem
The problem this feature solves, and who has it.  Why can it not be solved with what Afon has today?

## User story
**As a** [role, eg TRE administrator, data partner, researcher, developer]
**I want** [the feature]
**So that** [the benefit]

## Proposed solution
How you think the feature could work: the components involved, any new CLI actions, configuration properties or
stacks, and how it fits the [architecture](../architecture.md).

## Acceptance criteria
- [ ] Criterion 1
- [ ] Criterion 2
- [ ] Criterion 3

## Testing
How the feature can be tested: which unit tests, whether it needs acceptance tests (against local containers) or
smoke tests (against a deployed stack).  See [testing](testing.md).

## Impact
- Clouds affected: (aws / azure / gcp / all)
- New dependencies, and their licenses:
- Security considerations: (eg new credentials, grants, network exposure)
- Cost considerations: (eg new cloud resources)

## Alternatives considered
Other approaches you considered, and why you prefer this one.

## Definition of done
- [ ] All acceptance criteria are met.
- [ ] Code is reviewed and approved.
- [ ] Tests are written and pass.
- [ ] Documentation is updated.

## Willing to contribute?
- [ ] I would like to implement this feature myself, with guidance from the maintainers.
```

## What happens next

1. A maintainer triages the issue: labels it, asks for any missing details, and may close it if it is a duplicate or
   out of scope, explaining why.
2. For enhancements and new features, the approach is discussed in the issue.  Please wait for a maintainer to agree
   it before starting on a pull request, so that your work is not wasted.
3. Once agreed, the issue is assigned to whoever works on it.  Comment on the issue if you would like to work on it.
   The commit hook checks that the issue referenced by a commit is assigned to you (see
   [pull requests](pull_requests.md#commits)).
