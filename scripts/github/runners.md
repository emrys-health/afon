# Afon Runners

## Linux runners

ec2-16-60-232-177.eu-west-2.compute.amazonaws.com

Install and register the runner itself with [runner.sh](runner.sh), then provision the tools the
acceptance tests workflow needs with the ansible playbook in [../ansible](../ansible/README.md) —
keep its `inventory.ini` in step with the hosts listed above.
