# Runner provisioning

Ansible playbook that installs the command-line tools required by the
[Acceptance tests (Linux)](../../.github/workflows/acceptance-tests-linux.yml) workflow on a
self-hosted Linux runner.

The runner itself is installed by the `github` role, which drives
[runner.sh](../github/runner.sh) rather than restating what it does. The config files come from the
repository secrets pushed by [secrets.sh](../github/secrets.sh).

## What it installs

| Role          | Tools                                                                         | Why                                                                     |
|---------------|-------------------------------------------------------------------------------|-------------------------------------------------------------------------|
| `shell_tools`  | git, curl, wget, unzip, zip, tar, jq, perl, rsync, coreutils, ca-certificates | `actions/checkout`, the config steps of `afon.sh`, `scripts/config/validate.sh` |
| `disk`        | grows the root partition and filesystem onto the volume, reclaims the apt cache | The container images, `~/.m2` and the workspace need tens of GB          |
| `lvm`         | lays out extra volumes as LVM at the sizes you declare — **off until you name a device** | Puts the images, `~/.m2` and the workspace on their own filesystem instead of the cramped root |
| `swap`        | a 4G swap file (EC2 ships with none)                                          | Stops a peak in javac or the test JVM taking the box down                |
| `java`        | JDK 17 (`openjdk` by default, `temurin` optionally)                           | JDK 17 is enforced by the maven build                                    |
| `maven`       | Maven from the Apache archive, linked as `/usr/local/bin/mvn`                 | `./afon.sh -a build -p acceptance`                                       |
| `docker`      | docker engine + buildx/compose plugins, runner user in the `docker` group     | The specs start KeyCloak, Polaris, Trino and Spark Connect via Testcontainers |
| `cloud_tools` | aws, az, gcloud, kubectl, tofu — **off by default**                           | Only needed if the runner also drives the ops/tofu actions of `afon.sh`  |
| `github`      | installs and registers the actions runner via `runner.sh` — **off by default** | Registering acts on the repo on GitHub, not just on this host            |
| `verify`      | runs each tool as the runner user, asserts the JDK version and the runner size | Same checks as the workflow's *Verify runner tooling* step               |

## Pinned versions

The versions of maven, docker (`docker-ce`, held with `dpkg_selections`), kubectl, tofu and the aws cli are not set here:
they are read from the linux pins in [pinned.sh](../local/pinned.sh), which `afon-cli.sh local-setup` also uses, so that the
runner and local setups are the same.  To change a version, change it there.  The `verify` role checks the installed
versions against the pins and fails on a mismatch (`-e verify_pinned=false` to only report).
The azure and gcloud clis (and the gke auth plugin) are installed from their apt repos at the pinned versions, and held.

The workflow also provisions its own Temurin 17 through `actions/setup-java`; the JDK installed here
is what you get when running the build on the runner by hand.

## Runner sizing

Tools alone are not enough: the specs run a `local[4]` Spark driver on the runner *and* the KeyCloak,
Polaris, Trino and Spark Connect containers next to it. A runner short of memory does not fail in a
spec — it dies earlier, in javac, with `OutOfMemoryError: Java heap space`, because the default max
heap is a quarter of the machine's RAM.

|        | Minimum | Comfortable        |
|--------|---------|--------------------|
| Memory | 8 GB    | 16 GB (t3.xlarge)  |
| Disk   | 30 GB   | 60 GB gp3          |
| vCPU   | 2       | 4                  |

The `verify` role checks this and fails with the shortfall, rather than leaving it to be discovered
mid-build. After enlarging the EBS volume in the AWS console, re-run the playbook — the `disk` role
grows the partition and filesystem onto the new space.

## Declaring the storage layout

The stock Ubuntu cloud image gives the runner a small root filesystem and a 1 GB `/boot` it never
uses, which is no place for several GB of container images. Rather than repartitioning a live root
disk — which cannot be done while it is mounted — attach one or more extra EBS volumes and declare
what you want built on them. The `lvm` role pools them into one LVM volume group and carves it
into the volumes you name:

```yaml
lvm_devices:                  # empty by default, so the role does nothing until you name one
  - /dev/xvdd
lvm_group: afon-vg
lvm_volumes:
  - name: data
    size: 100%VG                  # or '60g', '50%VG' — lvcreate syntax
    mount: /data
    fstype: ext4
```

LVM rather than plain partitions so the sizes stay yours to change: raise a `size` and the next run
grows the volume *and* its filesystem, online. Lowering one is ignored rather than shrinking a
filesystem out from under its data, and attaching another volume and adding it to `lvm_devices`
extends the group.

Use `100%VG` — meaning the whole group — not `100%FREE`, which targets only the extents free at that
moment and strands the rest of the group once the volume exists. Several volumes means giving them
explicit sizes.

The role then moves the space-hungry directories onto that filesystem. This part does not depend on
LVM at all — it happens whenever `lvm_data_mount` (`/data`) is a mounted filesystem, so a volume you
formatted and mounted by hand is used exactly as it stands, and `lvm_devices` can stay empty. When
nothing is mounted there the relocations are skipped rather than binding onto the root filesystem:

| Directory                        | How                                            |
|----------------------------------|------------------------------------------------|
| `/var/lib/docker`                | `data-root` in `/etc/docker/daemon.json`, which is what the daemon supports |
| `/var/lib/containerd`            | `root` in `/etc/containerd/config.toml`.  Docker uses the containerd image store, so this — not the docker data root — is where the images actually are |
| `~/.m2`                          | bind mount from `/data/m2`                     |
| `actions-runner`                 | bind mount from `/data/actions-runner`, covering `_work`, `_diag` and the ~700MB of runner binaries |

Anything already pulled or checked out is copied across first, and the runner service is stopped for
the move — its own working directory is what is being relocated.

Existing content is copied first and the original kept as `<dir>.pre-bind` — delete those once you
are happy, or set `lvm_keep_moved_data=false`.

**A declared device must be a whole, unused disk.** The role asserts this and refuses anything
carrying a partition table, a filesystem or a mount, so pointing it at the wrong device stops the
play rather than destroying data. Devices already in the volume group are recognised, so re-runs are
no-ops.

With the layout in place, tell `verify` where the room now has to be:

```yaml
verify_disk_checks:
  - {path: /, min_free_gb: 5}
  - {path: /data, min_free_gb: 40}
```

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

Install a single part of the setup with
`--tags shell|disk|lvm|swap|java|maven|docker|cloud|github|verify`, for example:

```bash
cd scripts/ansible && ansible-playbook runner-setup.yml --tags docker,verify
```

Include the cloud CLIs:

```bash
cd scripts/ansible && ansible-playbook runner-setup.yml -e install_cloud_tools=true
```

## Installing the runner

The `github` role downloads, unpacks, registers and services the runner by driving the `install`,
`configure` and `service` actions of [runner.sh](../github/runner.sh), passing the version, install
dir, repo and registration token to it as environment variables. The script stays the one definition
of how a runner is installed, for running by hand as much as from here.

Registering creates a runner against the repo on GitHub, so it never happens as a side effect of a
routine run — ask for it:

```bash
cd scripts/ansible && ansible-playbook runner-setup.yml --tags github -e github_install_runner=true
```

Registration tokens expire after an hour, so the role mints one per run with `gh` on your machine —
the same tool [secrets.sh](../github/secrets.sh) needs, and no GitHub credential ever reaches the
runner. To use one from
[the runners page](https://github.com/emrys-health/afon-core/settings/actions/runners/new) instead,
pass `-e github_runner_token=...`.

The runner reports as `github_runner_name`, defaulting to the inventory host's short name — set it to
match if you are re-registering a host that GitHub already knows under another name, or you will end
up with two registrations. It applies the `self-hosted`, `Linux` and `X64` labels the workflow selects
on by itself; `github_runner_labels` adds to those.

Both steps are guarded: an installed runner is not re-downloaded, and a registered one is not
re-registered.

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
- A long-lived runner fills up — `~/.m2` and the container images both grow. `-e prune_docker=true`
  reclaims dangling images and stopped containers without touching the tagged images the next run
  needs.
- The build's heap is set by `MAVEN_OPTS` in the workflow, not by this playbook, so it does not
  silently depend on the instance size.
