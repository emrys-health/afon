generate-cleanup-script

Generates the cleanup script for a stack, to delete what is left of it in the cloud, into
<afon.home>/<stack-id>-cleanup.sh.

Usage:
  afon-cli.sh generate-cleanup-script <stack-id>

<stack-id> is <cloud>-<name>, eg aws-keycloak. It can also be <cloud>-<role>, the stack of the cloud with the role catalog, identity or tre in stacks.yaml, eg aws-tre, unless a stack is defined under that id.
