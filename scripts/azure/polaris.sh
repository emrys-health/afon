source $AFON_ROOT/scripts/config/afon-source.sh azure
source $AFON_ROOT/scripts/kube/polaris-source.sh

# Reports whether Polaris is up and serving on this env's Azure stack: login, AKS network, AKS cluster,
# Polaris pod, ingress external IP, TLS secret, then real HTTPS requests, anonymous and as the Polaris admin.
# See kube/status-source.sh for the layers and the exit codes: 0 status reported, 2 could not verify.

status_require_domain AZURE_POLARIS_DOMAIN_NAME "$AZURE_POLARIS_DOMAIN_NAME"
status_require az kubectl kubelogin curl

echo "- Checking Polaris for cluster '$CLUSTER_NAME' at $STATUS_DOMAIN"

status_require_login Azure azure-login az account get-access-token --output none

if VNETS=$(az network vnet list --resource-group "$AZURE_RESOURCE_GROUP" --query "[?name=='aks-vnet'].name" --output tsv 2>"$STATUS_ERR"); then
  status_check_network "AKS network" "$VNETS"
else
  status_cannot_verify "- Could not list the networks in resource group $AZURE_RESOURCE_GROUP:"
fi

status_get_credentials AKS "ResourceNotFound|could not be found|not found" \
  az aks get-credentials --resource-group "$AZURE_RESOURCE_GROUP" --name "$CLUSTER_NAME" --file "$STATUS_KUBECONFIG"

# The cluster uses Azure RBAC, so kubectl needs the kubeconfig converted to use the az login, as the
# AzureCloudProvider does when it deploys.
if [ "$STATUS_CLUSTER_UP" = "true" ]; then
  if ! kubelogin convert-kubeconfig -l azurecli --kubeconfig "$STATUS_KUBECONFIG" >/dev/null 2>"$STATUS_ERR"; then
    status_cannot_verify "- Could not convert the AKS kubeconfig to use the az login:"
  fi
fi

status_check_nodes
status_check_pod app=polaris
status_check_ingress polaris-ingress ip "external IP"
status_check_tls polaris-tls
polaris_check_https
polaris_check_authenticated azure
status_report
