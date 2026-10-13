# Points kubectl at the AKS cluster of the stack of the given type, eg: afon-cli.sh azure-update-kube-config keycloak
STACK_TYPE_ARG="${1:-}"
source $AFON_ROOT/scripts/config/afon-source.sh azure

# The cluster name <stack-type>-<CLUSTER_NAME>, as the AzureCloudProvider names it
STACK_TYPE="${STACK_TYPE_ARG#azure-}"
if [ -z "$STACK_TYPE" ]; then
  echo "Requires argument <stack-type>, eg keycloak, polaris or full, for stack azure-<stack-type>"
  exit 1
fi
if [ -z "${CLUSTER_NAME:-}" ]; then
  echo "CLUSTER_NAME is not set, cannot name the cluster of stack azure-$STACK_TYPE"
  exit 1
fi
STACK_CLUSTER_NAME="$STACK_TYPE-$CLUSTER_NAME"

az aks get-credentials --resource-group "$AZURE_RESOURCE_GROUP" --name "$STACK_CLUSTER_NAME" --overwrite-existing
# The cluster uses Azure RBAC, so kubectl needs the kubeconfig converted to use the az login, as the AzureCloudProvider does
kubelogin convert-kubeconfig -l azurecli
