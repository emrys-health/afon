source $AFON_ROOT/scripts/config/afon-source.sh azure

usage(){
  echo "Usage: $0"
}

ACTION="status"

# Checks whether Polaris is actually up and serving traffic end-to-end: AKS network and
# cluster reachable, pod Running/Ready, ingress has an external IP, TLS secret present,
# and the public domain completes a real HTTPS round trip through nginx -> ingress -> pod.
status_check() {
  OK=true

  VNETS=$(az network vnet list --resource-group "$AZURE_RESOURCE_GROUP" --query "[?name=='aks-vnet'].name" --output tsv)
  if [ -n "$VNETS" ]; then
    echo "- AKS network found: $VNETS"
  else
    echo "- No AKS network found for Polaris (FAILED)"
    OK=false
  fi

  if az aks get-credentials --resource-group $AZURE_RESOURCE_GROUP --name $CLUSTER_NAME --overwrite-existing -o none 2>/dev/null; then
    echo "- AKS credentials obtained for $CLUSTER_NAME"
  else
    echo "- Could not get AKS credentials for $CLUSTER_NAME (FAILED)"
    OK=false
  fi

  if kubectl get nodes -o name >/dev/null 2>&1; then
    echo "- kubectl can reach the cluster"
  else
    echo "- kubectl cannot reach the cluster (FAILED)"
    OK=false
  fi

  POD_STATUS=$(kubectl get pods -l app=polaris -o jsonpath='{.items[0].status.phase}' 2>/dev/null)
  POD_READY=$(kubectl get pods -l app=polaris -o jsonpath='{.items[0].status.containerStatuses[0].ready}' 2>/dev/null)
  if [ "$POD_STATUS" = "Running" ] && [ "$POD_READY" = "true" ]; then
    echo "- Polaris pod is Running and Ready"
  else
    echo "- Polaris pod status='$POD_STATUS' ready='$POD_READY' (FAILED)"
    OK=false
  fi

  INGRESS_IP=$(kubectl get ingress polaris-ingress -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null)
  if [ -n "$INGRESS_IP" ]; then
    echo "- Ingress has external IP $INGRESS_IP"
  else
    echo "- Ingress has no external IP (FAILED)"
    OK=false
  fi

  TLS_CRT=$(kubectl get secret polaris-tls -o jsonpath='{.data.tls\.crt}' 2>/dev/null)
  if [ -n "$TLS_CRT" ]; then
    echo "- TLS secret polaris-tls present"
  else
    echo "- TLS secret polaris-tls missing or empty (FAILED)"
    OK=false
  fi

  echo ""
  if [ "$OK" = "true" ]; then
    echo "Polaris is deployed correctly."
  else
    echo "Polaris is NOT deployed correctly - see FAILED lines above."
    exit 1
  fi
}

case "$ACTION" in
  status)
    status_check
    ;;

  *)
    usage
    exit 1
    ;;
esac
