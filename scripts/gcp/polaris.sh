source $AFON_ROOT/scripts/config/afon-source.sh gcp
source $AFON_ROOT/scripts/kube/polaris-source.sh

# Reports whether Polaris is up and serving on this env's GCP stack: login, GKE network, GKE cluster,
# Polaris pod, ingress external IP, TLS secret, then a real HTTPS request.
# See kube/polaris-source.sh for the layers and the exit codes: 0 status reported, 2 could not verify.

POLARIS_PROJECT="afon-core" # as deployed by GcpKubernetesStack

polaris_require_domain GCP_POLARIS_DOMAIN_NAME "$GCP_POLARIS_DOMAIN_NAME"
polaris_require gcloud kubectl curl
if ! command -v gke-gcloud-auth-plugin >/dev/null 2>&1; then
  polaris_cannot_verify "gke-gcloud-auth-plugin not found, cannot verify Polaris (install it with: afon-cli.sh setup-local -c gcp)"
fi

echo "- Checking Polaris for cluster '$CLUSTER_NAME' at $POLARIS_DOMAIN"

polaris_require_login GCP gcp-login gcloud auth print-access-token

if NETWORKS=$(gcloud compute networks list --project "$POLARIS_PROJECT" --filter="name=gke-private-vpc-$CLUSTER_NAME" --format="value(name)" 2>"$POLARIS_ERR"); then
  polaris_check_network "GKE network" "$NETWORKS"
else
  polaris_cannot_verify "- Could not list the GCP networks in project $POLARIS_PROJECT:"
fi

polaris_get_credentials GKE "404|not found" \
  env KUBECONFIG="$POLARIS_KUBECONFIG" gcloud container clusters get-credentials "$CLUSTER_NAME" --region "$GCP_REGION" --project "$POLARIS_PROJECT"

polaris_check_nodes
polaris_check_pod
polaris_check_ingress ip "external IP"
polaris_check_tls
polaris_check_https
polaris_report
