# Points kubectl at the AKS cluster of the given stack, by its id or its role (identity, catalog or tre), eg:
# afon-cli.sh azure-update-kube-config tre
STACK_ARG="${1:-}"
source $AFON_ROOT/scripts/config/afon-source.sh azure
source $AFON_ROOT/scripts/azure/stack-source.sh "$STACK_ARG"

az aks get-credentials --resource-group "$AZURE_RESOURCE_GROUP" --name "$STACK_CLUSTER_NAME" --overwrite-existing
# The cluster uses Azure RBAC, so kubectl needs the kubeconfig converted to use the az login, as the AzureCloudProvider does
kubelogin convert-kubeconfig -l azurecli
