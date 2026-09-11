# Afon Runners

## Linux runners

ec2-18-134-198-0.eu-west-2.compute.amazonaws.com

Install the runner and the tools the acceptance tests workflow needs with the ansible playbook in
[../ansible](../ansible/README.md) — keep its `inventory.ini` in step with the hosts listed above:

```shell
cd scripts/ansible
ansible-playbook runner-setup.yml                                        # the tools
ansible-playbook runner-setup.yml --tags github -e github_install_runner=true  # the runner itself
```

The `github` role drives [runner.sh](runner.sh), which is still the way to install, configure or
service a runner by hand:

```shell
RUNNER_TOKEN=<token from the runners page> ./runner.sh configure
./runner.sh service status
```

## Disk layout

Each runner has two EBS volumes: a **root volume** (`/dev/sda1`, currently 60 GB — `xvda`/`/` on
the instance) for the OS and installed packages (JDK, npm, cloud CLIs, apt), and a **data volume**
(currently 60 GB, mounted at `/data`) that the `lvm` role bind-mounts `~/.m2` and `~/actions-runner`
onto, and that Docker/containerd are configured to use as their data root — see the `lvm` role's
[defaults](../ansible/roles/lvm/defaults/main.yml) for what actually gets relocated there.

Nothing relocates `/usr` itself, so root fills up as more tooling gets installed on it.
`/usr` alone can be several GB. If a job fails with the root partition full despite `/data`
having room, the fix is to grow the root **volume** in AWS (`aws ec2 modify-volume --volume-id
<id> --size <new-size>`), then grow the partition/filesystem onto it — either by hand
(`sudo growpart /dev/xvda 1 && sudo resize2fs /dev/xvda1`) or by re-running
`ansible-playbook runner-setup.yml --tags disk`, which does the same growpart/resize2fs. This is an
online resize — no reboot, no interruption to a runner mid-job.
