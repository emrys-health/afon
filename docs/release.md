# Afon Release

## Maven release 

The Maven release is performed by executing the script:
```shell
./release.sh
```
It uses the [maven-release-plugin](https://maven.apache.org/maven-release/maven-release-plugin/) to 
create a git tag `afon-<version>` with a fixed release version, and to commit the next SNAPSHOT version.
The zip is then released by the [CLI Release workflow](#cli-release-workflow), triggered by the pushes.

It takes two inputs:
```shell
Version:    release version (semver)
Next:       next snapshot version (optional, defaults to the current project version)
```

For example, to release a preview version but remain on the same SNAPSHOT version: 
```shell
./release.sh 1.0-beta-1
```

To release a stable version and move to the next SNAPSHOT version:
```shell
./release.sh 1.0 1.1-SNAPSHOT
```

By default the script only prepares the release: it updates the release notes, creates the git tag, pushes the
commits and the tag, and reports the next SNAPSHOT version.  Two flags enable the remaining steps, which are usually
done by the CLI Release workflow instead (both default to false):
```shell
--perform   invoke release:perform, to build the released components off of the tag
--upload    invoke ./upload.sh, to upload the distribution zip to the "Afon Distribution" share drive
```
For example, to prepare, perform and upload a stable release locally:
```shell
./release.sh --perform --upload 1.0 1.1-SNAPSHOT
```

**NOTE:** The `deploy` stage of `release.sh` itself is disabled (`maven.deploy.skip`).  The CLI zip is released as a
GitHub Release by the [CLI Release workflow](#cli-release-workflow), and the Maven artifacts are deployed to GitHub
Packages by the [Maven Release workflow](#maven-release-workflow).

## CLI Release workflow

The [cli-release](../.github/workflows/cli-release.yml) workflow builds the afon-cli zip and releases it on GitHub.  Only the
zip is released, not the Maven module jars/poms.  It runs on a GitHub-hosted runner with the job's own `GITHUB_TOKEN`,
so it needs no secrets, and handles both kinds of version:

- **Release version**: pushing the tag `afon-<version>` checks that the tag matches the project version, builds the zip
  (with the unit tests) and creates the GitHub Release `<version>`, with the section of `release_notes.md` for that
  version (or generated notes if there is none) and the zip attached.  A version with a qualifier, eg `1.0-beta-1`, is
  marked as a pre-release.  A release version is only released from its tag.
- **Snapshot version**: a push to `main` that changes `afon-*/**`, `scripts/**` or `templates/**` builds the zip and
  publishes it as the asset of a rolling `snapshot` pre-release, which each run replaces.  Anyone can download it from the repository's releases page, without a GitHub
  login: `https://github.com/emrys-health/afon/releases/download/snapshot/afon-<version>-cli.zip`.

Running `release.sh` therefore triggers two runs of the workflow, one after the other (they share a concurrency
group): one for the push of `main`, which publishes the next SNAPSHOT version as the `snapshot` release, and one for the
pushed tag, which creates the GitHub Release.

It can also be run from the Actions tab (`workflow_dispatch`) on a branch, eg `main` for a snapshot, or on a release tag.
A release version can only be released from its tag.

## Maven Release workflow

The [maven-release](../.github/workflows/maven-release.yml) workflow mirrors the CLI Release workflow, but deploys the
Maven artifacts (module jars/poms) to [GitHub Packages](https://github.com/emrys-health/afon/packages) instead of
creating a GitHub Release.  It runs on a GitHub-hosted runner with the job's own `GITHUB_TOKEN`, so it needs no secrets.
It builds with the unit tests, using the `github`, `distribution`, `bin` and `src` profiles and
`-Dmaven.deploy.skip=false`: it leaves out the `cli` profile, whose zip is released by the CLI Release workflow, and the
acceptance tests, which need cloud credentials.  It handles both kinds of version:

- **Snapshot version**: deploys the `SNAPSHOT` artifacts, replacing the previous ones of that version.
- **Release version**: only from the tag `afon-<version>`, which must match the project version.  A release version
  cannot be deployed twice: GitHub Packages rejects the second deploy.

**NOTE:** The push triggers (`main` for snapshots, `afon-*` tags for releases) are disabled for now (commented out in the
workflow): run it from the Actions tab (`workflow_dispatch`), choosing `main` or a release tag.

### Consuming the artifacts

GitHub Packages requires authentication to download, even from a public repository.  Create a personal access token
with the `read:packages` scope, and add to `~/.m2/settings.xml` the server `github-maven-packages`
(username: your GitHub user, password: the token), and the repository
`https://maven.pkg.github.com/emrys-health/afon`, as in the `github` profile of [settings.xml](../settings.xml).

## Release notes

Before preparing the tag, `release.sh` updates [release_notes.md](../release_notes.md) with the closed issues of the
GitHub [milestone](https://github.com/emrys-health/afon/milestones) titled after the release version,
and commits it if changed.  Issues still open in the milestone are not included.  The milestone must therefore exist before releasing, and the `gh` CLI must be
authenticated with a token holding the `repo` scope.

The release notes are generated by [milestones.sh](../scripts/github/milestones.sh), which can also be run
on its own, e.g. to preview them:
```shell
afon-cli.sh github-milestones <version>                                     # writes ~/.afon/milestone-<version>.md
afon-cli.sh github-milestones -- <version> --state open                     # lists only the issues still open
afon-cli.sh github-milestones -- <version> release_notes.md --state closed  # also updates the version section in release_notes.md
```
Option `--state` is one of `all|open|closed` (or `done`, same as `closed`) and defaults to `all`.  Use `--` before
the script args when passing options, so that `afon.sh` does not interpret them as its own.
The version section in `release_notes.md` is replaced if present, or inserted above the previous releases.
The release notes are included in the binary distributions.
