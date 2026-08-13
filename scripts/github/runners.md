# Afon Runners

## Linux runners

ec2-35-177-168-204.eu-west-2.compute.amazonaws.com

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
