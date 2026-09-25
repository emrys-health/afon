source $AFON_ROOT/scripts/config/afon-source.sh azure
source $AFON_ROOT/scripts/kube/polaris-source.sh

# Reports whether Polaris is up and serving on this env's Azure stack: login, AKS network, AKS cluster,
# Polaris pod, ingress external IP, TLS secret, then a real HTTPS request.
# See kube/polaris-source.sh for the layers and the exit codes: 0 status reported, 2 could not verify.

polaris_require_domain AZURE_POLARIS_DOMAIN_NAME "$AZURE_POLARIS_DOMAIN_NAME"
polaris_require az kubectl kubelogin curl

echo "- Checking Polaris for cluster '$CLUSTER_NAME' at $POLARIS_DOMAIN"

polaris_require_login Azure azure-login az account get-access-token --output none

if VNETS=$(az network vnet list --resource-group "$AZURE_RESOURCE_GROUP" --query "[?name=='aks-vnet'].name" --output tsv 2>"$POLARIS_ERR"); then
  polaris_check_network "AKS network" "$VNETS"
else
  polaris_cannot_verify "- Could not list the networks in resource group $AZURE_RESOURCE_GROUP:"
fi

polaris_get_credentials AKS "ResourceNotFound|could not be found|not found" \
  az aks get-credentials --resource-group "$AZURE_RESOURCE_GROUP" --name "$CLUSTER_NAME" --file "$POLARIS_KUBECONFIG"

# The cluster uses Azure RBAC, so kubectl needs the kubeconfig converted to use the az login, as the
# AzureCloudProvider does when it deploys.
if [ "$POLARIS_CLUSTER_UP" = "true" ]; then
  if ! kubelogin convert-kubeconfig -l azurecli --kubeconfig "$POLARIS_KUBECONFIG" >/dev/null 2>"$POLARIS_ERR"; then
    polaris_cannot_verify "- Could not convert the AKS kubeconfig to use the az login:"
  fi
fi

polaris_check_nodes
polaris_check_pod
polaris_check_ingress ip "external IP"
polaris_check_tls
polaris_check_https
polaris_report
