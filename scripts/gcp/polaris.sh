source $AFON_ROOT/scripts/config/afon-source.sh gcp

# Checks Polaris is up and serving on this env's GCP stack, layer by layer as azure/polaris.sh does:
# GKE network and cluster reachable, Polaris pod Running/Ready, ingress has an external IP, TLS secret
# present - then a real HTTPS request through DNS -> nginx -> ingress -> pod. The kubectl checks say
# where a failure is (eg no nodes, so a Pending pod); the request is what proves Polaris really serves.
# An unauthenticated catalog API call is enough for the request, as Polaris itself answers 401.
# Uses a throwaway kubeconfig, so your current kubectl context is left alone.
# Exit code: 0 Polaris is serving, 1 it is not, 2 could not verify.

POLARIS_PROJECT="afon-core" # as deployed by GcpKubernetesStack

if [ -z "$GCP_POLARIS_DOMAIN_NAME" ]; then
  echo "GCP_POLARIS_DOMAIN_NAME is not set, cannot verify Polaris"
  exit 2
fi
if ! command -v kubectl >/dev/null 2>&1; then
  echo "kubectl not found, cannot verify Polaris"
  exit 2
fi
if ! command -v gke-gcloud-auth-plugin >/dev/null 2>&1; then
  echo "gke-gcloud-auth-plugin not found, cannot verify Polaris (install with: gcloud components install gke-gcloud-auth-plugin)"
  exit 2
fi

POLARIS_OK=true
POLARIS_KUBECONFIG=$(mktemp)
polaris_kubectl() { kubectl --kubeconfig "$POLARIS_KUBECONFIG" "$@"; }

echo "- Checking Polaris for cluster '$CLUSTER_NAME' at $GCP_POLARIS_DOMAIN_NAME"

if POLARIS_NETWORKS=$(gcloud compute networks list --project "$POLARIS_PROJECT" --filter="name=gke-private-vpc-$CLUSTER_NAME" --format="value(name)" 2>/dev/null); then
  if [ -n "$POLARIS_NETWORKS" ]; then
    echo "- GKE network found: $POLARIS_NETWORKS"
  else
    echo "- No GKE network found for cluster '$CLUSTER_NAME' (FAILED)"
    POLARIS_OK=false
  fi
else
  echo "- Could not list GCP networks in project $POLARIS_PROJECT - not authenticated? (FAILED)"
  POLARIS_OK=false
fi

if KUBECONFIG="$POLARIS_KUBECONFIG" gcloud container clusters get-credentials "$CLUSTER_NAME" --region "$GCP_REGION" --project "$POLARIS_PROJECT" >/dev/null 2>&1; then
  echo "- GKE credentials obtained for $CLUSTER_NAME"
else
  echo "- Could not get GKE credentials for $CLUSTER_NAME (FAILED)"
  POLARIS_OK=false
fi

if POLARIS_NODES=$(polaris_kubectl get nodes -o name 2>/dev/null); then
  echo "- kubectl can reach the cluster ($(echo "$POLARIS_NODES" | grep -c .) node(s))"
else
  echo "- kubectl cannot reach the cluster (FAILED)"
  POLARIS_OK=false
fi

# '|| true' as kubectl exits non-zero when there is no such pod - the very case to report, not die on.
POLARIS_POD_STATUS=$(polaris_kubectl get pods -l app=polaris -o jsonpath='{.items[0].status.phase}' 2>/dev/null || true)
POLARIS_POD_READY=$(polaris_kubectl get pods -l app=polaris -o jsonpath='{.items[0].status.containerStatuses[0].ready}' 2>/dev/null || true)
if [ "$POLARIS_POD_STATUS" = "Running" ] && [ "$POLARIS_POD_READY" = "true" ]; then
  echo "- Polaris pod is Running and Ready"
else
  echo "- Polaris pod status='$POLARIS_POD_STATUS' ready='$POLARIS_POD_READY' (FAILED)"
  POLARIS_OK=false
fi

POLARIS_INGRESS_IP=$(polaris_kubectl get ingress polaris-ingress -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)
if [ -n "$POLARIS_INGRESS_IP" ]; then
  echo "- Ingress has external IP $POLARIS_INGRESS_IP"
else
  echo "- Ingress has no external IP (FAILED)"
  POLARIS_OK=false
fi

POLARIS_TLS_CRT=$(polaris_kubectl get secret polaris-tls -o jsonpath='{.data.tls\.crt}' 2>/dev/null || true)
if [ -n "$POLARIS_TLS_CRT" ]; then
  echo "- TLS secret polaris-tls present"
else
  echo "- TLS secret polaris-tls missing or empty (FAILED)"
  POLARIS_OK=false
fi
rm -f "$POLARIS_KUBECONFIG"

POLARIS_CODE=$(curl -s -o /dev/null -m 15 -w "%{http_code}" "https://$GCP_POLARIS_DOMAIN_NAME/api/catalog/v1/config") || true # prints 000 when it can't connect
case "$POLARIS_CODE" in
  200|401)
    echo "- Polaris answered HTTPS with HTTP $POLARIS_CODE"
    ;;
  000)
    echo "- No HTTPS response from $GCP_POLARIS_DOMAIN_NAME (DNS or connection failed) (FAILED)"
    POLARIS_OK=false
    ;;
  *)
    echo "- HTTPS gave HTTP $POLARIS_CODE - reachable, but Polaris is not answering (FAILED)"
    POLARIS_OK=false
    ;;
esac

echo ""
if [ "$POLARIS_OK" = "true" ]; then
  echo "Polaris is deployed and serving."
else
  echo "Polaris is NOT deployed correctly - see FAILED lines above."
  exit 1
fi
