# Runner provisioning

Ansible playbook that installs the command-line tools required by the
[Acceptance tests (Linux)](../../.github/workflows/acceptance-tests-linux.yml) workflow on a
self-hosted Linux runner.

It provisions the tools only — installing and registering the runner itself stays with
[runner.sh](../github/runner.sh), and the config files come from the repository secrets pushed by
[secrets.sh](../github/secrets.sh).

## What it installs

| Role          | Tools                                                                         | Why                                                                     |
|---------------|-------------------------------------------------------------------------------|-------------------------------------------------------------------------|
| `base_tools`  | git, curl, wget, unzip, zip, tar, jq, perl, rsync, coreutils, ca-certificates | `actions/checkout`, the config steps of `afon.sh`, `scripts/config/validate.sh` |
| `java`        | JDK 17 (`openjdk` by default, `temurin` optionally)                           | JDK 17 is enforced by the maven build                                    |
| `maven`       | Maven from the Apache archive, linked as `/usr/local/bin/mvn`                 | `./afon.sh -a build -p acceptance`                                       |
| `docker`      | docker engine + buildx/compose plugins, runner user in the `docker` group     | The specs start KeyCloak, Polaris, Trino and Spark Connect via Testcontainers |
| `cloud_tools` | aws, az, gcloud, kubectl, tofu — **off by default**                           | Only needed if the runner also drives the ops/tofu actions of `afon.sh`  |
| `verify`      | runs each tool as the runner user and asserts the JDK version                 | Same checks as the workflow's *Verify runner tooling* step               |

The workflow also provisions its own Temurin 17 through `actions/setup-java`; the JDK installed here
is what you get when running the build on the runner by hand.

## Usage

Requires `ansible` on your machine (`brew install ansible`) and SSH access to the runner host with
passwordless sudo.

Check the target hosts in [inventory.ini](inventory.ini) — they should match
[runners.md](../github/runners.md) — then:

```bash
cd scripts/ansible && ansible-playbook runner-setup.yml
```

Dry run first, showing what would change:

```bash
cd scripts/ansible && ansible-playbook runner-setup.yml --check --diff
```

Report the versions currently installed, without changing anything:

```bash
cd scripts/ansible && ansible-playbook runner-setup.yml --tags verify
```

Install a single part of the setup with `--tags base|java|maven|docker|cloud|verify`, for example:

```bash
cd scripts/ansible && ansible-playbook runner-setup.yml --tags docker,verify
```

Include the cloud CLIs:

```bash
cd scripts/ansible && ansible-playbook runner-setup.yml -e install_cloud_tools=true
```

## Settings

Site-level settings live in [group_vars/github_runners.yml](group_vars/github_runners.yml) — the
runner user, the java flavour, the maven version and the cloud tools flag. Everything else is a role
default under `roles/*/defaults/main.yml`, overridable with `-e`.

The maven tarball is verified by TLS only; set `maven_checksum` to the `sha512:<hex>` value published
alongside the tarball on `archive.apache.org` to pin it.

## Notes

- Targets Debian/Ubuntu runners; the playbook fails fast on anything else.
- Adding the runner user to the `docker` group only takes effect on a new session, so the playbook
  restarts any `actions.runner.*` service it finds. Pass `-e restart_runner_service=false` to bounce
  it yourself instead.
- The first workflow run after provisioning is slow: Testcontainers pulls the KeyCloak, Polaris,
  Trino and Spark images.
