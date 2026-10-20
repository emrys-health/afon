# Points kubectl at the GKE cluster of the given stack, by its id or its role (identity, catalog or tre), eg:
# afon-cli.sh gcp-update-kube-config catalog
STACK_ARG="${1:-}"
source $AFON_ROOT/scripts/config/afon-source.sh gcp
source $AFON_ROOT/scripts/gcp/stack-source.sh "$STACK_ARG"

GCP_PROJECT_ID="${GCP_PROJECT_ID:?GCP_PROJECT_ID must be set in afon-gcp.properties}"
GCP_REGION="${GCP_REGION:?GCP_REGION must be set in afon-gcp.properties}"

gcloud container clusters get-credentials "$STACK_CLUSTER_NAME" --region "$GCP_REGION" --project "$GCP_PROJECT_ID"
# kubectl reaches the cluster through the plugin that the kubeconfig names, which gcloud does not install with the credentials
if ! command -v gke-gcloud-auth-plugin >/dev/null 2>&1; then
  echo "WARNING: gke-gcloud-auth-plugin not found, so kubectl cannot reach the cluster (install it with: afon-cli.sh local-setup -c gcp)"
fi
