#!/usr/bin/env bash
# Writes the kubeconfig of the AKS cluster of the given stack, $AFON_CDKTF/kube/<stack-id>.config, not ~/.kube/config, by its id or its role (identity, catalog or tre), eg:
# afon-cli.sh azure-update-kube-config tre
STACK_ARG="${1:-}"
source $AFON_ROOT/scripts/config/afon-source.sh azure
source $AFON_ROOT/scripts/azure/stack-source.sh "$STACK_ARG"

source $AFON_ROOT/scripts/kube/kubeconfig-source.sh
KUBECONFIG_FILE=$(kubeconfig_path "$STACK_ID")
mkdir -p "$(dirname "$KUBECONFIG_FILE")"
az aks get-credentials --resource-group "$AZURE_RESOURCE_GROUP" --name "$STACK_CLUSTER_NAME" --file "$KUBECONFIG_FILE" --overwrite-existing
# The cluster uses Azure RBAC, so kubectl needs the kubeconfig converted to use the az login, as the AzureCloudProvider does
kubelogin convert-kubeconfig -l azurecli --kubeconfig "$KUBECONFIG_FILE"
kubeconfig_current "$STACK_ID"
echo "kubeconfig of $STACK_ID: export KUBECONFIG=$KUBECONFIG_FILE"
