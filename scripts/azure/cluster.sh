# Given the stack, by its id or its role (identity, catalog or tre), eg: afon-cli.sh azure-cluster identity
STACK_ARG="${1:-}"
source $AFON_ROOT/scripts/config/afon-source.sh azure
source $AFON_ROOT/scripts/azure/stack-source.sh "$STACK_ARG"

# Checks whether the AKS cluster of the given stack for given env is up, by asking the cluster
# itself: can kubectl reach <stack>-$CLUSTER_NAME?  The tofu state is deliberately not read, as it
# may belong to a stack that someone else brought up (or that this env's $AFON_CDKTF never knew about).
# Only the cluster is checked, not the other resources of the stack (network, identity, DNS records ...).
# 'az aks show' is what locates the cluster, and it also tells a cluster that does not exist
# (ResourceNotFound, or ResourceGroupNotFound) from one we cannot ask about (expired session, no
# permissions, no network).  Uses a throwaway kubeconfig, so your current kubectl context is left alone.
# Reports the status, so 'up' and 'down' are both a successful run: exit code 0 when the status was
# established (the cluster is up: kubectl reaches it, or down: no such cluster), 2 when it could not be
# verified, and 1 when no stack is given.  Never reported as down unless proven, so an expired Azure session can't be mistaken for a
# clean teardown.

CLUSTER_KUBECONFIG=$(mktemp)
CLUSTER_ERR=$(mktemp)
cluster_kubectl() { kubectl --kubeconfig "$CLUSTER_KUBECONFIG" --request-timeout=20s "$@"; }
cluster_exit() { rm -f "$CLUSTER_KUBECONFIG" "$CLUSTER_ERR"; exit "$1"; }

for CLUSTER_TOOL in az kubectl kubelogin; do
  if ! command -v "$CLUSTER_TOOL" >/dev/null 2>&1; then
    echo "$CLUSTER_TOOL not found, cannot verify the cluster state"
    cluster_exit 2
  fi
done

echo "- Checking the AKS cluster '$STACK_CLUSTER_NAME' (resource group $AZURE_RESOURCE_GROUP) via kubectl"

if az aks show --resource-group "$AZURE_RESOURCE_GROUP" --name "$STACK_CLUSTER_NAME" --output none 2>"$CLUSTER_ERR"; then
  echo "- AKS cluster '$STACK_CLUSTER_NAME' found"
elif grep -q "ResourceNotFound\|ResourceGroupNotFound\|could not be found" "$CLUSTER_ERR"; then
  echo "- No AKS cluster '$STACK_CLUSTER_NAME' in resource group $AZURE_RESOURCE_GROUP"
  echo "Cluster is down."
  cluster_exit 0
else
  echo "- Could not look up AKS cluster '$STACK_CLUSTER_NAME':"
  cat "$CLUSTER_ERR"
  cluster_exit 2
fi

# The cluster uses Azure RBAC, so the kubeconfig is converted to use the az login, as the AzureCloudProvider does
if ! { az aks get-credentials --resource-group "$AZURE_RESOURCE_GROUP" --name "$STACK_CLUSTER_NAME" --file "$CLUSTER_KUBECONFIG" --overwrite-existing >/dev/null 2>"$CLUSTER_ERR" \
       && kubelogin convert-kubeconfig -l azurecli --kubeconfig "$CLUSTER_KUBECONFIG" 2>>"$CLUSTER_ERR"; }; then
  echo "- Could not get the kubeconfig of AKS cluster '$STACK_CLUSTER_NAME':"
  cat "$CLUSTER_ERR"
  cluster_exit 2
fi

# The API server's own readiness, which needs no RBAC rights, so 'up' does not depend on who is asking.
if cluster_kubectl get --raw /readyz >/dev/null 2>"$CLUSTER_ERR"; then
  # Listing nodes needs more rights than /readyz, and is only there for information.
  if CLUSTER_NODES=$(cluster_kubectl get nodes -o name 2>/dev/null); then
    CLUSTER_NODES_INFO="$(echo "$CLUSTER_NODES" | grep -c .) node(s)"
  else
    CLUSTER_NODES_INFO="node count unavailable"
  fi
  echo "- Up: kubectl reaches the cluster, whose API server is ready ($CLUSTER_NODES_INFO)"
  echo "Cluster is up."
  cluster_exit 0
fi

# The cluster exists but its API server does not answer: being created or deleted, stopped, or not reachable
# from here.  Neither up nor down, so say what is known.
CLUSTER_STATUS=$(az aks show --resource-group "$AZURE_RESOURCE_GROUP" --name "$STACK_CLUSTER_NAME" --query "{provisioning:provisioningState, power:powerState.code}" --output tsv 2>/dev/null | tr '\t' ' ' || true)
echo "- AKS cluster '$STACK_CLUSTER_NAME' exists (status ${CLUSTER_STATUS:-unknown}) but kubectl cannot reach it:"
cat "$CLUSTER_ERR"
echo "Could not verify the cluster state."
cluster_exit 2
