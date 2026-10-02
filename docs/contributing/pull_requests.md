# Creating pull requests

All changes reach `main` via a pull request (PR), reviewed by at least one maintainer.  This page describes the
workflow from fork to merge.

## Workflow

### 1. Agree on an issue

Make sure there is an [issue](issues.md) for the change, that its approach has been agreed for an enhancement or new
feature, and that it is assigned to you.

### 2. Fork and clone

External contributors work on a fork of [afon-core](https://github.com/emrys-health/afon-core):

```shell
gh repo fork emrys-health/afon-core --clone
cd afon-core
git remote -v   # origin is your fork, upstream is emrys-health/afon-core
```

or fork via the GitHub web UI and clone your fork.  Maintainers with write access may branch in the main repo instead.

Enable the git hooks, once per clone (see [build](../build.md#git-hooks)):

```shell
git config core.hooksPath .githooks
```

Then set up your environment and build, as per [setup](../setup_local.md) and [build](../build.md).

### 3. Create a branch

Branch from an up to date `main`, naming the branch after the issue:

```shell
git fetch upstream
git switch -c 123-short-description upstream/main
```

### 4. Make the change

- Keep the change focused on the issue, see the [contribution criteria](README.md#contribution-criteria).
- Follow the conventions of the surrounding code.
- Add or update [tests](testing.md).
- Update the [docs](..) for any change visible to users or operators.

### 5. Commit

#### Commits

Each commit message starts with the issue it belongs to, in square brackets, followed by a description of the change
in full sentences:

```
[#123] Added --teardown flag to the regression suite.  Stacks are torn down in reverse order on exit.
```

The `commit-msg` hook checks that the issue is assigned to you, to catch a wrong issue number before it lands in
history.  It needs the [GitHub CLI](https://cli.github.com/) and is skipped, with a warning, without it.

Keep commits meaningful: one logical change each, building and passing the tests.  Do not commit generated files
(eg anything under `target/`), IDE settings, local config or secrets.

### 6. Test

Before opening the PR, run at least the build with the unit tests:

```shell
./afon.sh -a build -bp -acceptance
```

and any other tests relevant to the change, as per [testing](testing.md).

### 7. Keep up to date

Rebase on `main` before opening the PR, and whenever it conflicts with `main`:

```shell
git fetch upstream
git rebase upstream/main
git push --force-with-lease
```

### 8. Open the pull request

```shell
gh pr create --repo emrys-health/afon-core --base main
```

or via the GitHub web UI.  Use the [description template](#pull-request-description) below.

Open the PR as a **draft** if you want early feedback on work in progress, and mark it as ready for review once done.

## Pull request description

```markdown
## Summary
What the PR changes, and why.

Closes #123

## Changes
- Main change 1
- Main change 2

## Testing
- [ ] Unit tests added or updated, and `./afon.sh -a build -bp -acceptance` passes
- [ ] Acceptance tests run, or not needed: (state which, and for which clouds)
- [ ] Smoke tests run, or not needed: (state which, and against which stack)

How the change was tested, eg the commands run and on which cloud.

## Impact
- Breaking changes: (none, or what breaks and the migration path, eg renamed config properties)
- New dependencies, and their licenses: (none, or list)
- Security considerations: (none, or eg new credentials, grants, network exposure)

## Documentation
- [ ] Docs updated, or not needed
- [ ] New configuration properties added to the `AfonProperty` enum, or none added

## Checklist
- [ ] The PR addresses a single issue, referenced above
- [ ] Commits reference the issue, as `[#123] ...`
- [ ] No secrets, credentials or real data are included
- [ ] I agree to license my contribution under the Apache License 2.0
```

`Closes #123` links the PR to the issue, and closes the issue when the PR is merged.

## Continuous integration

The [GitHub workflows](../github_workflows.md) run on a self-hosted runner holding the project's cloud configuration.
For security, workflows needing cloud credentials do not run automatically on PRs from forks: a maintainer runs the
acceptance and smoke tests the change needs, once it has been reviewed (see [testing](testing.md)).

The issue-assignee check runs on push, and is advisory only.

## Review

- A maintainer reviews the PR, usually within a few working days.  Please be patient, and ping the PR if it has had
  no response after a week.
- Reviews check the [contribution criteria](README.md#contribution-criteria): correctness, tests, consistency,
  documentation, security and licensing.
- Address review comments with new commits, rather than amending, so that reviewers can see what changed since their
  last review.  Reply to each comment, and leave it to the reviewer to resolve it.
- A disagreement is fine: explain your reasoning, and the maintainers make the final call.

## Merge

Once approved, and once the tests the change needs have passed, a maintainer merges the PR.  The issue is closed by
the merge, and the change appears in the next release's [release notes](../../release_notes.md).

A PR may be closed without merging if it is out of scope, duplicates other work, or has been inactive for a long time
after review comments.  You are welcome to reopen it, or open a new one, once it is addressed.
