# Shows, or cancels, the rolling upgrades on the node scale sets of the AKS cluster of the given stack, by its id or its role
# (identity, catalog or tre), eg:
# afon-cli.sh azure-rolling-upgrades tre            # status is the default
# afon-cli.sh azure-rolling-upgrades tre status
# afon-cli.sh azure-rolling-upgrades tre cancel
#
# Azure itself starts a "VM Agent Rolling Upgrade" on the scale set of a new cluster, within the hour or so after it is created (the
# operation upgradeVMAgent, which has no caller in the activity log).  While it runs, Azure refuses to delete the scale set
# (OperationNotAllowed: "Virtual Machine Scale Set updates are not allowed while there is a Rolling Upgrade in progress"), so the load
# balancer and the network security group that it uses, and then the cluster's managed resource group, cannot be deleted either: the
# delete of the cluster fails, while tofu destroy goes on waiting for it.  Cancelling the upgrade lets the delete go through.
STACK_ARG="${1:-}"
ACTION="${2:-status}"
source $AFON_ROOT/scripts/config/afon-source.sh azure
source $AFON_ROOT/scripts/azure/stack-source.sh "$STACK_ARG"

usage(){
  echo "Usage: ${BASH_SOURCE[0]} <stack-id|identity|catalog|tre> [status|cancel]"
  echo "Shows, or cancels, the rolling upgrades that are running on the scale sets of the AKS cluster of the stack: while one runs,"
  echo "Azure refuses to delete the scale set, and so the cluster. The default is status."
}

case "$ACTION" in
  status|cancel) ;;
  *) echo "- The action should be status or cancel, not '$ACTION'"; usage; exit 1 ;;
esac

if [ -z "${AZURE_RESOURCE_GROUP:-}" ]; then
  echo "- AZURE_RESOURCE_GROUP is not set - set it in afon-azure.properties"
  exit 1
fi

# The resource group that AKS makes for the cluster, where its scale sets are
NODE_GROUP="$(az aks show --resource-group "$AZURE_RESOURCE_GROUP" --name "$STACK_CLUSTER_NAME" --query nodeResourceGroup --output tsv 2>/dev/null || true)"
if [ -z "$NODE_GROUP" ]; then
  echo "- No AKS cluster $STACK_CLUSTER_NAME in $AZURE_RESOURCE_GROUP, so no rolling upgrade is blocking its delete"
  exit 0
fi
echo "- Cluster $STACK_CLUSTER_NAME, with its scale sets in $NODE_GROUP"

SCALE_SETS="$(az vmss list --resource-group "$NODE_GROUP" --query "[].name" --output tsv 2>/dev/null || true)"
if [ -z "$SCALE_SETS" ]; then
  echo "- It has no scale set"
  exit 0
fi

# Prints the rolling upgrade of scale set $1 as: <status>|<start>|<done>|<in progress>|<pending>|<failed>, or nothing if there was none
rolling_upgrade() {
  az vmss rolling-upgrade get-latest --resource-group "$NODE_GROUP" --name "$1" \
    --query "join('|', [runningStatus.code, runningStatus.startTime, to_string(progress.successfulInstanceCount), to_string(progress.inProgressInstanceCount), to_string(progress.pendingInstanceCount), to_string(progress.failedInstanceCount)])" \
    --output tsv 2>/dev/null || true
}

# Prints the status of scale set $1, and returns whether a rolling upgrade is running on it
show_status() {
  local line status start done running pending failed
  line="$(rolling_upgrade "$1")"
  if [ -z "$line" ]; then
    echo "- $1: no rolling upgrade has run"
    return 1
  fi
  IFS='|' read -r status start done running pending failed <<< "$line"
  echo "- $1: rolling upgrade $status, started $start ($done instances done, $running in progress, $pending pending, $failed failed)"
  [ "$status" = "RollingForward" ]
}

RUNNING=
for SCALE_SET in $SCALE_SETS; do
  if show_status "$SCALE_SET"; then
    RUNNING=true
    if [ "$ACTION" = "cancel" ]; then
      echo "- Cancelling the rolling upgrade of $SCALE_SET"
      az vmss rolling-upgrade cancel --resource-group "$NODE_GROUP" --name "$SCALE_SET" -o none || exit 1
      show_status "$SCALE_SET" || true
    fi
  fi
done

if [ -n "$RUNNING" ] && [ "$ACTION" = "status" ]; then
  echo "- A rolling upgrade is running: Azure refuses to delete the scale set, and so the cluster, until it ends. To cancel it:"
  echo "afon-cli.sh azure-rolling-upgrades ${STACK_ARG} cancel"
fi
