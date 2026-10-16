# Sourced by the Azure scripts acting on the stack of a given type, given as $1, eg keycloak for stack azure-keycloak.
# Sets STACK_TYPE and STACK_CLUSTER_NAME, ie the cluster name <stack-type>-<CLUSTER_NAME>, as the AzureCloudProvider names it.
STACK_TYPE="${1:-}"
STACK_TYPE="${STACK_TYPE#azure-}"
if [ -z "$STACK_TYPE" ]; then
  echo "Requires argument <stack-type>, eg keycloak, polaris or full, for stack azure-<stack-type>"
  exit 1
fi
if [ -z "${CLUSTER_NAME:-}" ]; then
  echo "CLUSTER_NAME is not set, cannot name the cluster of stack azure-$STACK_TYPE"
  exit 1
fi
STACK_CLUSTER_NAME="$STACK_TYPE-$CLUSTER_NAME"
