generate-cleanup-script

Generates the cleanup script for a stack, to delete what is left of it in the cloud, into
<afon.home>/<stack-id>-cleanup.sh.

Usage:
  afon-cli.sh generate-cleanup-script <stack-id>

<stack-id> is <cloud>-<stack-type>, eg aws-keycloak.
